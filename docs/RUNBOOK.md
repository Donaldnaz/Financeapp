# Incident and DR Runbook

This runbook covers active-active operations for the Java API across `us-east-1` and `us-west-2`.

## Service Overview

- Runtime: ECS Fargate in both regions
- Traffic: Route53 **latency-based** routing with health checks (weighted policy available via `routing_policy = "weighted"` for canary/blue-green)
- Data: DynamoDB Global Table
- Secrets: AWS Secrets Manager with cross-region replica
- Images: ECR registry replication from `us-east-1` to `us-west-2` (prefix `financeapp-dr-`)
- Logs/Metrics: CloudWatch and X-Ray

## Deployed Endpoints and IDs

- Dev API: `api-dev.ikenna-financeapp-dr.com` (active)
- Prod API: `api.ikenna-financeapp-dr.com` (decommissioned — see "Environment Status")
- Hosted zone ID: `Z02272842N6T33RBSSBSE`
- Dev health checks:
  - Region A (us-east-1): `a9f1d9ed-42cb-402d-aa30-64fd27262271`
  - Region B (us-west-2): `d58d2031-b712-4567-af71-3f572a717f23`

## Environment Status

| Env | Status | Notes |
|---|---|---|
| dev | **Active** in `us-east-1` + `us-west-2` | Latency routing, ECR replication, DDB Global Table all live |
| prod | **Destroyed** | `terraform destroy` ran cleanly; to bring back run `terraform apply` in `infra/terraform/environments/prod` |

## SLO and DR Targets

- Availability target: 99.9%
- RTO target (regional impairment): <= 5 minutes
- RPO target: <= 60 seconds

## Preconditions

- `terraform apply` completed in both regions
- DNS points to the Route53 weighted record
- App image deployed to ECS services in both regions
- Health endpoints return `200 OK` on both ALBs

## Standard Health Verification

1. DNS check (custom domain has no public NS delegation in dev — use ALB DNS directly):
   - `dig +short <api-domain>`
   - Primary ALB: `financeapp-dr-dev-primary-alb-125393765.us-east-1.elb.amazonaws.com`
   - Secondary ALB: `financeapp-dr-dev-secondary-alb-84473505.us-west-2.elb.amazonaws.com`
2. Regional health (both should return `200`):
   - `curl -sS http://<alb-primary-dns>/health`
   - `curl -sS http://<alb-secondary-dns>/health`
3. UI smoke:
   - `curl -sS http://<alb-primary-dns>/login` returns the HTML form (contains "Sign in")
4. Login + authenticated browse:
   ```bash
   ALB=http://<alb-primary-dns>
   curl -sS -i -c c.txt -X POST -d "username=alice&password=Password!1" $ALB/login
   # Expect: HTTP/1.1 302, Set-Cookie: jwt=...; HttpOnly; SameSite=Strict
   curl -sS -b c.txt $ALB/dashboard | grep -E "Alice|region|balance"
   ACCT=$(curl -sS -b c.txt $ALB/api/me | jq -r .accountId)
   ```
5. Banking smoke (signup → welcome bonus → transfer → audit):
   ```bash
   JWT=$(awk '$6=="jwt" {print $7}' c.txt)
   # Self-signup a fresh user with a strong password (alice already logged in above)
   USER=demo$(date +%s)
   curl -sS -c c2.txt -X POST $ALB/signup \
     --data-urlencode "username=$USER" --data-urlencode "displayName=Smoke User" \
     --data-urlencode 'password=S3cure-Banking!Pass' \
     --data-urlencode 'confirmPassword=S3cure-Banking!Pass'
   J2=$(awk '$6=="jwt" {print $7}' c2.txt)
   ACC2=$(curl -sS -H "Authorization: Bearer $J2" $ALB/api/me | jq -r .accountId)
   # Expect balance 1000.00 (welcome bonus)
   curl -sS -H "Authorization: Bearer $J2" $ALB/api/accounts/$ACC2 | jq .balance
   # Transfer 250 to alice — atomic TransactWriteItems with conditional balance check
   curl -sS -H "Authorization: Bearer $J2" -H "X-Request-Id: $(uuidgen)" \
     -H "Content-Type: application/json" \
     -d '{"toUsername":"alice","amount":"250.00","memo":"runbook smoke"}' \
     $ALB/api/transfers | jq
   # Insufficient-funds case returns 422 and leaves balances untouched
   curl -sS -i -H "Authorization: Bearer $J2" -H "X-Request-Id: $(uuidgen)" \
     -H "Content-Type: application/json" \
     -d '{"toUsername":"alice","amount":"9999.99"}' \
     $ALB/api/transfers | head -1
   # Card deposit + PayPal withdraw
   curl -sS -H "Authorization: Bearer $J2" -H "X-Request-Id: $(uuidgen)" \
     -H "Content-Type: application/json" \
     -d '{"amount":"25.00","description":"card top-up","paymentMethod":"DEMO_CARD"}' \
     $ALB/api/accounts/$ACC2/deposit | jq '.paymentMethod,.balanceAfter'
   curl -sS -H "Authorization: Bearer $J2" -H "X-Request-Id: $(uuidgen)" \
     -H "Content-Type: application/json" \
     -d '{"amount":"10.00","paypalEmail":"user@paypal.demo"}' \
     $ALB/api/accounts/$ACC2/withdraw | jq '.paymentMethod,.paymentReference'
   # Audit log shows SIGNUP, LOGIN_SUCCESS, TRANSFER_OUT, DEPOSIT_CARD, WITHDRAWAL_PAYPAL
   curl -sS -H "Authorization: Bearer $J2" $ALB/api/audit/me | jq '.items[] | .eventType'
   ```
6. Cross-region active-active proof (same JWT, secondary region):
   - `curl -sS -H "Authorization: Bearer $J2" http://<alb-secondary-dns>/api/accounts/$ACC2 | jq .balance` should report the post-transfer balance — proves DDB Global Table replication + cross-region JWT validation.
   - `curl -sS -H "Authorization: Bearer $J2" http://<alb-secondary-dns>/api/audit/me | jq '.items[].eventType'` should show the same audit events streamed from the other region.

## Dev deployment (two layers)

Set variables once in repo-root `.env` (from `.env.example`). Scripts load it automatically; the Spring app ignores deploy/Terraform keys in that file.

```bash
cp .env.example .env
# edit AWS_ACCOUNT_ID, ECR_*, ECS_*, optional TF_VAR_jwt_secret / TF_VAR_secret_value
```

### Application (every release)

Push to `main` triggers `.github/workflows/ci.yml`:

1. Java tests (`mvn verify`)
2. Build and push `financeapp-dr-dev-repo:latest` (and `:$GITHUB_SHA`) to ECR in `us-east-1`
3. `aws ecs update-service --force-new-deployment` on both dev ECS services (primary + secondary after ECR replication)

Terraform validate/lint is **not** part of the app CI pipeline — run locally or use the manual **Apply Infrastructure (Terraform)** workflow for infra changes.

Manual equivalent:

```bash
./deploy-dev.sh
```

`deploy-dev.sh` reads ECS/ECR settings from `.env` first, then Terraform outputs, then hardcoded fallbacks.

### Infrastructure (module or env changes only)

Use the Terraform wrapper — do **not** run apply on every app deploy:

```bash
./infra-deploy.sh plan dev
./infra-deploy.sh apply dev
# optional: override image at apply time
./infra-deploy.sh apply dev -var='container_image=920375856513.dkr.ecr.us-east-1.amazonaws.com/financeapp-dr-dev-repo:latest'
```

Secrets (set in `.env` or export before running):

```bash
# in .env:
# TF_VAR_jwt_secret=...
# TF_VAR_secret_value=...
./infra-deploy.sh apply dev
```

Inspect outputs:

```bash
./infra-deploy.sh output dev
PRIMARY_ALB=$(terraform -chdir=infra/terraform/environments/dev output -raw primary_alb_dns)
curl -sS "http://${PRIMARY_ALB}/health"
```

Teardown:

```bash
CONFIRM=destroy ./infra-deploy.sh destroy dev
```

GitHub Actions: **Apply Infrastructure (Terraform)** (`workflow_dispatch`, confirm with `apply`) or **Destroy DEV Infrastructure** (confirm with `destroy`).

## Inspecting and resetting state

- **Get a user's full audit trail (operator view):**
  ```bash
  USER_ID=01KS6SVQNYE26TWGPVH5EPXY03
  aws dynamodb query --table-name financeapp-dr-dev-transactions --region us-east-1 \
    --key-condition-expression "pk = :p AND begins_with(sk, :s)" \
    --expression-attribute-values '{":p":{"S":"AUDIT#USER#'$USER_ID'"},":s":{"S":"EVENT#"}}' \
    --output table
  ```
- **Reset a user's balance to a fresh $1,000:** delete the user's `ACCOUNT#<id>` `METADATA` row and let them sign up again, **or** delete the seed-bonus idempotency key and force a fresh ECS deployment:
  ```bash
  aws dynamodb delete-item --table-name financeapp-dr-dev-transactions --region us-east-1 \
    --key '{"pk":{"S":"IDEMPOTENCY#seed-bonus-alice"},"sk":{"S":"METADATA"}}'
  aws ecs update-service --cluster financeapp-dr-dev-primary-cluster \
    --service financeapp-dr-dev-primary-service --force-new-deployment --region us-east-1
  ```
  The seeder will re-apply the $1,000 bonus on the next startup (idempotent key prevents double-credit).
- **Credit a custom starting bonus to a demo user manually:** call `POST /api/accounts/{id}/deposit` with the desired amount and a unique `X-Request-Id`.

## Data Model Notes

- Table is **single-table** with `pk`+`sk` and one GSI (`gsi1-by-transaction-id`).
- Entity types: `ACCOUNT` (with `balance`), `TRANSACTION` (with `type`, `signedAmount`, `balanceAfter`, `paymentMethod`, `paymentReference`), `PAYMENT`, `IDEMPOTENCY`, `USER`, `AUDIT`.
- `PAYMENT#USER#<userId>` / `CARD#DEMO` — demo Visa card auto-provisioned on signup.
- `PAYMENT#USER#<userId>` / `PAYPAL#DEMO` — demo PayPal email linked on withdraw.
- `IDEMPOTENCY#<requestId>` items carry a `ttl` attribute (epoch seconds, +24h) and are auto-pruned by DynamoDB TTL.
- `AUDIT#USER#<userId>` items carry `ttl` (+90 days). Unknown-user login failures go to `AUDIT#USER#UNKNOWN`.
- `USER#<username>` items store BCrypt-12 `passwordHash` and `defaultAccountId` (no unused GSI1 keys).
- Balance updates use `SET balance = balance - :amt` with `attribute_exists(balance) AND balance >= :amt` as the condition — this is what makes overdraft prevention a database guarantee, not an application guarantee.
- Schema changes that touch `KeySchema` or `AttributeDefinitions` are **destructive** in Terraform — the table is destroyed and recreated, taking the global replica with it. Plan accordingly for any prod data. The banking additions reuse the existing `pk`/`sk`/`gsi1pk`/`gsi1sk` schema and the existing `ttl` attribute, so no infra change was required.

## Authentication, Signup, and Seeded Users

- App enforces auth via `SecurityConfig` + `JwtAuthFilter`. JWTs are HS256, signed with `APP_JWT_SECRET` (Terraform `-var jwt_secret=...`).
- Same secret in both regions → a token issued by `us-east-1` validates against `us-west-2` and vice versa.
- Cookie attributes: `HttpOnly; SameSite=Strict; Path=/; Max-Age=3600`. `Secure` flips on when `APP_COOKIE_SECURE=true` (set once the ALB has HTTPS).
- **Self-signup:** `POST /signup` (HTML form) or `POST /api/signup` (JSON). Server validates `@StrongPassword` (≥12 chars, mixed-case + digit + symbol, not in `top-1000-passwords.txt`). Passwords hashed with BCrypt cost 12.
- **Anti-timing-attack:** `POST /login` sleeps ~250 ms + jitter on failure and writes a `LOGIN_FAILURE` audit event (with the attempted username, even if unknown).
- `UserSeeder` runs on each app boot when `APP_SEED_ENABLED=true`. It is idempotent (skips `USER#<username>` items that already exist) and re-applies the $1,000 welcome bonus with a deterministic `X-Request-Id = seed-bonus-<username>` so the deposit never double-credits. Seeded users:
  - `alice`, `bob`, `carol`, `dave`, `eve` — password `Password!1` for all. (The strong-password policy applies only to `/signup`; the seeder writes directly via `createUser` to keep the demo simple.)
- **To re-seed (e.g. after corrupting the demo user):**
  1. Find the user row: `aws dynamodb get-item --region us-east-1 --table-name financeapp-dr-dev-transactions --key '{"pk":{"S":"USER#alice"},"sk":{"S":"METADATA"}}'`
  2. Delete it: `aws dynamodb delete-item ... --key '{"pk":{"S":"USER#alice"},"sk":{"S":"METADATA"}}'` (also delete from `us-west-2` or let replication propagate)
  3. Force a new ECS deployment in either region — the seeder will recreate `alice` and a fresh account on next startup.
- **To rotate the JWT secret:** `terraform -chdir=infra/terraform/environments/dev apply -var "jwt_secret=$(openssl rand -base64 48 | tr -d '/+=' | cut -c1-48)"`, then `aws ecs update-service --force-new-deployment` in both regions. All existing sessions will be invalidated, forcing a re-login.

## Regional Impairment Procedure

Use this during a game day or actual incident.

1. Confirm incident scope (one region unhealthy).
2. Verify CloudWatch alarms for ALB/ECS.
3. Force traffic away from unhealthy region:
   - Route53 health checks should auto-reduce traffic.
   - If needed, set affected region weight to `0`.
4. Confirm healthy region carries production traffic:
   - Error rate
   - P95 latency
   - ECS CPU/Memory
5. Validate data behavior:
   - Writes continue in healthy region.
   - Reads show expected consistency behavior.
6. Announce status and ETA in incident channel.

## Failback Procedure

1. Recover unhealthy region service.
2. Validate health endpoint and ECS stability.
3. Restore Route53 weight (for example 100/100).
4. Observe for 15-30 minutes:
   - Balanced traffic
   - No elevated 5xx
   - DynamoDB replication normal
5. Close incident and document lessons learned.

## Escalation Checklist

- Platform owner notified
- Application owner notified
- DNS owner available
- Data owner confirms replication status
- Incident notes captured in `DR-TEST-RESULTS.md`

## CI/CD Secrets Contract (GitHub Actions)

- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `TF_VAR_HOSTED_ZONE_ID`
- `TF_VAR_DEV_DOMAIN_NAME`
- `TF_VAR_PROD_DOMAIN_NAME`
- `TF_VAR_SECRET_VALUE_DEV`
- `TF_VAR_SECRET_VALUE_PROD`
- `TF_VAR_JWT_SECRET_DEV` (≥ 32 bytes; HS256 signing key)
- `TF_VAR_JWT_SECRET_PROD` (≥ 32 bytes; HS256 signing key)
