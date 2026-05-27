# iTrust — Portfolio Banking Demo

Full-stack Java Spring Boot banking app: server-rendered Thymeleaf UI + JSON API, backed by a DynamoDB Global Table for active-active multi-region disaster recovery on AWS. Demonstrates self-service signup, strong password policy, atomic peer-to-peer transfers, real running balances, and a per-user audit log — all replicated across `us-east-1` and `us-west-2`.

## What's inside

- **Web UI** (Thymeleaf + Tailwind via CDN): signup with live strength meter, login, dashboard with a real balance card, send-money page, **demo card deposit**, **PayPal withdraw**, paginated activity log, per-user audit log.
- **JSON API** under `/api/*`, JWT-protected, suitable for `curl`/Postman/automation.
- **Auth**: Spring Security + BCrypt (cost 12) + HS256 JWTs in an `HttpOnly; SameSite=Strict` cookie. CSRF protection on browser forms. Optional Google OAuth (`oauth` profile). Users persisted in DynamoDB.
- **Strong password policy** (server-validated):
  - Minimum 12 characters
  - At least one upper, one lower, one digit, one symbol
  - Bundled 1,000-entry common-password blocklist (`security/top-1000-passwords.txt`)
- **Atomic banking primitives**: `deposit`, `withdraw`, `transfer` implemented with a single DynamoDB `TransactWriteItems` call. The sender debit uses a conditional balance check, so insufficient-funds returns `422` and leaves all balances untouched.
- **Audit trail**: every signup, login success, login failure, deposit, withdrawal, and transfer is persisted with `ip`, `userAgent`, `region`, and a 90-day TTL. Anti-timing-attack `~250 ms` delay on failed logins.
- **Idempotency**: every write requires `X-Request-Id`; replays return the original record.
- **DR**: all state writes go through a DynamoDB Global Table that replicates across both regions in under a second.

## Public routes (no auth)

| Route | Purpose |
|---|---|
| `GET /login`, `POST /login` | Sign in (logs `LOGIN_SUCCESS` / `LOGIN_FAILURE`) |
| `GET /signup`, `POST /signup` | Self-service account creation with welcome bonus |
| `POST /logout` | Clears JWT cookie (confirmation modal in UI) |
| `POST /api/auth/login` | JSON login `{ username, password }` → `{ token, user }` + JWT cookie |
| `POST /api/auth/logout` | Clears JWT cookie (`204`) |
| `GET /api/auth/me` | Authenticated principal (alias of `/api/me`) |
| `GET /health` | JSON `{ "status": "UP" }` |
| `POST /api/signup` | JSON signup endpoint |
| `GET /actuator/health/**` | Spring probes |

## Authenticated routes

UI (Thymeleaf):

| Route | Description |
|---|---|
| `GET /dashboard` | Big balance card, recent 10 transactions, quick actions |
| `GET /accounts/{accountId}` | Paginated account history with `balanceAfter` column |
| `GET /transfer`, `POST /transfer` | Send money to another username (P2P) |
| `GET /deposit`, `POST /deposit` | Deposit via linked demo credit card |
| `GET /withdraw`, `POST /withdraw` | Withdraw to demo PayPal email |
| `GET /audit` | Per-user audit log (signup, logins, transfers, payments) |

JSON API (`/api/*`):

| Route | Description |
|---|---|
| `GET /api/me` | The authenticated principal |
| `GET /api/accounts/{accountId}` | Account summary with balance |
| `POST /api/accounts/{accountId}/deposit` | Deposit from demo card (header `X-Request-Id` required) |
| `POST /api/accounts/{accountId}/withdraw` | Withdraw to demo PayPal `{ amount, description, paypalEmail }` |
| `POST /api/transfers` | P2P transfer `{toUsername, amount, memo}` |
| `GET /api/accounts/{id}/transactions?cursor=&limit=` | Paginated history |
| `GET /api/transactions/{transactionId}` | GSI lookup by txn ID |
| `GET /api/audit/me?limit=&cursor=` | Per-user audit log |
| `POST /api/assistant/chat` | Banking assistant chat `{ messages: [{ role, content }] }` |
| `POST /api/assistant/confirm` | Confirm a pending assistant banking action `{ pendingActionId }` |

A user can only act on their own `accountId`; all other accounts return `403`.

## iTrust Assistant (Ollama or OpenAI)

The floating **iTrust Assistant** widget (bottom-right on authenticated pages) uses LLM tool calling against your ledger. By default it uses **free local Ollama** (open-source models). You can switch to paid **OpenAI** via `.env`.

### Default: free local Ollama

```bash
brew install ollama
ollama pull llama3.1
ollama serve
cp .env.example .env
./run-local.sh
```

### Optional: cloud OpenAI

Set in repo root `.env`:

```bash
ASSISTANT_PROVIDER=openai
ASSISTANT_API_KEY=sk-...
ASSISTANT_MODEL=gpt-4o-mini
ASSISTANT_BASE_URL=https://api.openai.com/v1
```

Or run: `cd app && ./run-with-cloud-llm.sh`

| Variable | Default | Purpose |
|---|---|---|
| `ASSISTANT_PROVIDER` | `ollama` | `ollama` (local/free) or `openai` (cloud) |
| `ASSISTANT_API_KEY` | `ollama` | Required for OpenAI (`sk-...`); ignored for Ollama |
| `ASSISTANT_MODEL` | `llama3.1` | Model name (`llama3.1` for Ollama, `gpt-4o-mini` for OpenAI) |
| `ASSISTANT_BASE_URL` | `http://localhost:11434/v1` | Ollama or OpenAI-compatible API base URL |
| `APP_ASSISTANT_ENABLED` | `true` | Set `false` to disable assistant endpoints |

Legacy `OPENAI_*` env vars still work as aliases for `ASSISTANT_*`.

### Google sign-in (optional)

1. Create an OAuth 2.0 Web client in [Google Cloud Console](https://console.cloud.google.com/apis/credentials).
2. Authorized redirect URI: `http://localhost:8080/login/oauth2/code/google` (add your deployed ALB URL for AWS).
3. Set environment variables and activate the `oauth` profile:

```bash
export GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
export GOOGLE_CLIENT_SECRET=your-client-secret
export SPRING_PROFILES_ACTIVE=oauth
./run-local.sh
```

The **Continue with Google** button appears on login/signup when `GOOGLE_CLIENT_ID` is set.

Example prompts after signing in as `alice` / `Password!1`:

- “What's my balance?”
- “Show my recent transactions”
- “Take me to withdraw” (returns a `/withdraw` link button)
- “Send $25 to bob” → review the pending action → click **Confirm** (or say “yes, confirm” in chat)

Assistant writes are always **propose → confirm**; the LLM never receives passwords or JWTs. Pending actions expire after about five minutes.

## Banking model

| Concept | Implementation |
|---|---|
| Account balance | Stored on the `ACCOUNT#<id>` / `METADATA` row; updated atomically inside `TransactWriteItems` |
| Transaction types | `DEPOSIT`, `WITHDRAWAL`, `TRANSFER_OUT`, `TRANSFER_IN` |
| Payment methods | `DEMO_CARD` (auto-provisioned on signup), `DEMO_PAYPAL` (linked on first withdraw), `WELCOME_BONUS`, `P2P` |
| Demo card | Stored at `PAYMENT#USER#<userId>` / `CARD#DEMO`; deterministic last4 from username |
| Demo PayPal | Stored at `PAYMENT#USER#<userId>` / `PAYPAL#DEMO`; email saved on withdraw |
| Transfer atomicity | One `TransactWriteItems` with 5 items: idempotency PUT, sender debit (`balance >= :amt` condition), receiver credit, sender TXN row, receiver TXN row |
| Insufficient funds | DDB `TransactionCanceledException` with `ConditionalCheckFailed` on sender debit → caught and rethrown as `InsufficientFundsException` → HTTP 422 |
| Welcome bonus | $1,000 credited to every new account via the same `deposit()` path; idempotent on a deterministic request ID |

### DynamoDB single-table layout

| Entity | pk | sk | gsi1pk | Key attrs |
|---|---|---|---|---|
| Account | `ACCOUNT#<id>` | `METADATA` | — | `balance` (N), `currency`, `displayName` |
| Transaction | `ACCOUNT#<id>` | `TXN#<ulid>` | `TXN#<id>` | `type`, `amount`, `signedAmount`, `balanceAfter`, `paymentMethod`, `paymentReference`, `counterpartyUsername` |
| User | `USER#<username>` | `METADATA` | — | `passwordHash` (BCrypt), `defaultAccountId` |
| PaymentMethod | `PAYMENT#USER#<userId>` | `CARD#DEMO` or `PAYPAL#DEMO` | — | `type`, `brand`, `maskedReference`, `last4`, `linkedAt` |
| Idempotency | `IDEMPOTENCY#<reqId>` | `METADATA` | — | `transactionId`, 24h TTL |
| Audit | `AUDIT#USER#<userId>` | `EVENT#<ulid>` | — | `eventType`, `ip`, `userAgent`, `region`, `details`, 90d TTL |

## Demo credentials

On startup with `APP_SEED_ENABLED=true` (the dev default), the app idempotently seeds five demo users into the DynamoDB Global Table, each with one USD account, a **demo credit card**, and a $1,000 starting balance applied via an idempotent `deposit()`.

| Username | Password | Display Name | Starting balance |
|---|---|---|---|
| alice | `Password!1` | Alice Anderson | $1,000.00 |
| bob | `Password!1` | Bob Brown | $1,000.00 |
| carol | `Password!1` | Carol Chen | $1,000.00 |
| dave | `Password!1` | Dave Davies | $1,000.00 |
| eve | `Password!1` | Eve Evans | $1,000.00 |

> The seed password (`Password!1`) bypasses the strong-password policy because the seeder writes directly via `createUser(...)` — only `/signup` enforces the policy. Real users created through the signup flow must use a strong password.

Re-seeding is safe — the seeder skips creating an existing username and re-runs `deposit("seed-bonus-<username>", ...)`; the deterministic request ID makes the bonus deposit idempotent.

## End-to-end demo (curl, against deployed ALB)

```bash
ALB=http://financeapp-dr-dev-primary-alb-125393765.us-east-1.elb.amazonaws.com

# 1) Self-sign-up a new user with a strong password
curl -c c.txt -X POST $ALB/signup \
  --data-urlencode "username=demo$(date +%s)" \
  --data-urlencode "displayName=Demo User" \
  --data-urlencode 'password=S3cure-Banking!Pass' \
  --data-urlencode 'confirmPassword=S3cure-Banking!Pass'

JWT=$(awk '$6=="jwt" {print $7}' c.txt)
ACC=$(curl -s -H "Authorization: Bearer $JWT" $ALB/api/me | jq -r .accountId)

# 2) Verify $1,000 welcome bonus
curl -s -H "Authorization: Bearer $JWT" $ALB/api/accounts/$ACC | jq

# 3) Transfer $250 to alice (atomic, cross-region replicated)
curl -s -H "Authorization: Bearer $JWT" -H "X-Request-Id: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"toUsername":"alice","amount":"250.00","memo":"rent split"}' \
  $ALB/api/transfers | jq

# 4) See the audit log
curl -s -H "Authorization: Bearer $JWT" $ALB/api/audit/me | jq

# 5) Failed transfer (insufficient funds) returns 422 and leaves balance untouched
curl -i -H "Authorization: Bearer $JWT" -H "X-Request-Id: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"toUsername":"alice","amount":"9999.99"}' \
  $ALB/api/transfers
```

The JWT signing secret is the same in both regions, so a cookie/token issued by `us-east-1` is valid against the `us-west-2` ALB and vice versa — true active-active.

## Storage modes

- `APP_STORAGE_TYPE=memory` (default, local) — `InMemoryLedgerStore` with synchronous balance bookkeeping
- `APP_STORAGE_TYPE=dynamodb` (AWS runtime) — `DynamoDbLedgerStore` against `TRANSACTIONS_TABLE`

## Environment variables

| Var | Default | Purpose |
|---|---|---|
| `APP_STORAGE_TYPE` | `memory` | `memory` or `dynamodb` |
| `TRANSACTIONS_TABLE` | `transactions-global` | DDB Global Table name |
| `AWS_REGION` | `local` | Tag-stamped on every txn and audit event |
| `APP_JWT_SECRET` | (dev only fallback) | HS256 signing key, **must be ≥ 32 bytes**; passed via Terraform `-var jwt_secret=...` |
| `APP_SEED_ENABLED` | `true` | Runs `UserSeeder` on startup |
| `APP_COOKIE_SECURE` | `false` | Set `true` once ALB has HTTPS |
| `OPENAI_API_KEY` | (empty) | Legacy alias for `ASSISTANT_API_KEY` when using OpenAI |
| `ASSISTANT_PROVIDER` | `ollama` | `ollama` or `openai` |
| `ASSISTANT_MODEL` | `llama3.1` | Assistant model |
| `ASSISTANT_BASE_URL` | `http://localhost:11434/v1` | LLM API base URL |
| `APP_ASSISTANT_ENABLED` | `true` | Toggle assistant endpoints |

## Local dev

Run from the **`app/` directory** (the Spring Boot project lives there, not the repo root):

```bash
cd app
mvn spring-boot:run -Dspring-boot.run.arguments=--app.seed.enabled=true
```

Or from the repo root: `./run-local.sh`

Then open:
- [http://localhost:8080/](http://localhost:8080/) — public landing page
- [http://localhost:8080/login](http://localhost:8080/login) — sign in as `alice` / `Password!1`
- [http://localhost:8080/signup](http://localhost:8080/signup) — create your own account (strong-password gated)

The default in-memory store still credits the $1,000 welcome bonus, provisions demo cards, and records audit events; useful for poking the UI without DDB.

## Deploy to dev (CI/CD)

Merging to `main` runs GitHub Actions (`.github/workflows/ci.yml`):

1. `mvn verify` + Terraform validate
2. Docker build → push `financeapp-dr-dev-repo:latest` and `:$GITHUB_SHA`
3. `aws ecs update-service --force-new-deployment` on primary (`us-east-1`) and secondary (`us-west-2`)

Infrastructure changes (VPC, DDB, ALB) remain **manual** via `terraform apply` in `infra/terraform/environments/dev`. The ECS task definition must already reference `…/financeapp-dr-dev-repo:latest`.
