# Production-Ready Java DR App (AWS + Terraform, Active-Active)

Portfolio project demonstrating a production-minded **full-stack banking app** — server-rendered Thymeleaf UI plus JSON API — deployed across two AWS regions with active-active traffic routing, globally replicated data, and an **iTrust Assistant** powered by **Llama** (via local **Ollama**). Application releases ship through **GitHub Actions** to **ECR** and **ECS** in both regions.

## Repository Layout

- `app/` - Spring Boot app (Thymeleaf UI + `/api/*` REST API + Llama assistant via Ollama)
- `infra/terraform/` - Modular Terraform for AWS infrastructure
- `architecture/` - AWS architecture diagram (draw.io, PNG, SVG) with VPC/subnet layout
- `docs/` - Runbook, DR test evidence, and design notes ([`docs/PORTFOLIO.md`](docs/PORTFOLIO.md))
- `.github/workflows/` - **GitHub Actions CI/CD** ([`ci.yml`](.github/workflows/ci.yml): test → build → **ECR push** → **ECS rollout** in `us-east-1` + `us-west-2`); manual infra apply ([`infra-apply.yml`](.github/workflows/infra-apply.yml))

## High-Level Capabilities

- Active-active multi-region app on ECS Fargate
- Route53 latency/health-based routing
- DynamoDB Global Tables for cross-region data replication
- Secrets Manager regional replication pattern
- **Banking assistant:** Llama (default `llama3.2:3b`) through **Ollama** — local, no OpenAI dependency
- **Software release:** GitHub Actions → Docker build → ECR → ECS force-new-deployment (both regions)
- Observability, alarms, and incident runbook

## Cost, stack, and DR (dev)

| Question | Answer |
|----------|--------|
| **Estimated daily cost (dev, low traffic)** | ~$4–8/day (~$120–240/month); can reach ~$8–15/day with heavier NAT/data transfer |
| **Top cost drivers** | NAT Gateway (×2) → ALB (×2) → ECS Fargate → NAT/data egress |
| **Language / framework** | Java 21, Spring Boot 3.3 (Thymeleaf UI + REST API) |
| **AI assistant** | **Ollama** + **Llama** (`ASSISTANT_PROVIDER=ollama`, default `llama3.2:3b`) — not OpenAI |
| **Software release** | **GitHub Actions** → ECR (`financeapp-dr-dev-repo`) → ECS Fargate (primary + secondary) |
| **Infrastructure** | Terraform (VPC, ALB, ECS, DynamoDB Global Table, Route53) — separate from app CI/CD |
| **RTO (target / observed)** | ≤ 5 min target · ~60 s observed ([DR test](docs/DR-TEST-RESULTS.md)) |
| **RPO (target / observed)** | ≤ 60 s target · < 30 s observed ([DR test](docs/DR-TEST-RESULTS.md)) |

Dev runs **active-active** in `us-east-1` and `us-west-2`: Route53 latency routing, DynamoDB Global Table replication, and ECS in private subnets (egress via NAT). For account-specific spend, use AWS Cost Explorer with tags `Project=financeapp-dr`, `Environment=dev`.

## Architecture

Active-active deployment in `us-east-1` (primary) and `us-west-2` (secondary). One Docker image per region serves the banking UI and API; DynamoDB Global Table keeps data in sync.

![Dev AWS architecture — VPC, subnets, and services](architecture/financeapp-dr-dev-aws2.png)

Editable source: [`architecture/financeapp-dr-dev-aws.drawio`](architecture/financeapp-dr-dev-aws.drawio) · PNG: [`financeapp-dr-dev-aws.png`](architecture/financeapp-dr-dev-aws.png) · SVG: [`architecture/financeapp-dr-dev-aws.svg`](architecture/financeapp-dr-dev-aws.svg)

<details>
<summary>Simplified diagram (Mermaid)</summary>

```mermaid
flowchart TB
  client["Browser / API client"] --> route53["Route53 latency routing<br/>api-dev.ikenna-financeapp-dr.com"]

  subgraph global [Global services]
    ecr["ECR financeapp-dr-dev-repo<br/>replicated us-east-1 → us-west-2"]
    ddb["DynamoDB Global Table<br/>financeapp-dr-dev-transactions"]
    secrets["Secrets Manager<br/>financeapp-dr-dev/app/config"]
    ci["GitHub Actions CI<br/>test · build · push · ECS rollout"]
  end

  subgraph east [us-east-1 primary]
    albE["ALB"] --> ecsE["ECS Fargate<br/>Spring Boot UI + API :8080"]
  end

  subgraph west [us-west-2 secondary]
    albW["ALB"] --> ecsW["ECS Fargate<br/>Spring Boot UI + API :8080"]
  end

  route53 --> albE
  route53 --> albW
  ecsE --> ddb
  ecsW --> ddb
  ecsE --> secrets
  ecsW --> secrets
  ci --> ecr
  ecr --> ecsE
  ecr --> ecsW
  ci --> ecsE
  ci --> ecsW
```

</details>

### Request path

1. DNS resolves via Route53 to the lowest-latency healthy regional ALB (`/health` checks).
2. ALB forwards to an ECS Fargate task running Spring Boot (JWT cookie auth; CSRF on browser forms).
3. Reads and writes hit the DynamoDB Global Table; changes replicate to the other region within seconds.
4. **Software release:** push to `main` — GitHub Actions runs tests, builds the Docker image, pushes to ECR, and force-deploys both ECS services (see [CI/CD](#deploy-to-aws-dev)).

### Component map (dev)

| Layer | Resource |
|-------|----------|
| DNS | `api-dev.ikenna-financeapp-dr.com` |
| Compute | ECS `financeapp-dr-dev-primary-service` / `financeapp-dr-dev-secondary-service` |
| Data | DynamoDB `financeapp-dr-dev-transactions` |
| Images | ECR `financeapp-dr-dev-repo` |
| Secrets | `financeapp-dr-dev/app/config` |
| State | S3 `financeapp-tf-state-920375856513` + DynamoDB lock `financeapp-tf-locks` |

### Live endpoints (dev)

- **App:** [http://api-dev.ikenna-financeapp-dr.com/](http://api-dev.ikenna-financeapp-dr.com/) (or primary ALB: `http://financeapp-dr-dev-primary-alb-681197869.us-east-1.elb.amazonaws.com/` if DNS is still propagating)
- **Health:** `/health`
- **Demo login:** `alice` / `Password!1`

### Further reading

- [`architecture/`](architecture/) — AWS diagram (VPC, subnets, AWS icons)
- [`docs/PORTFOLIO.md`](docs/PORTFOLIO.md) — banking model, auth design, data schema
- [`docs/DR-TEST-RESULTS.md`](docs/DR-TEST-RESULTS.md) — disaster recovery test evidence
- [`docs/RUNBOOK.md`](docs/RUNBOOK.md) — operations and incident response

## Quick Start

1. **Configure environment (once):**
   ```bash
   cp .env.example .env   # if present
   # edit .env — Ollama/Llama assistant, AWS deploy keys, optional TF_VAR_* secrets
   ```
   Spring Boot loads `ASSISTANT_*` and `APP_*` from `.env`. Deploy keys are for shell scripts only.

2. **Start Ollama + Llama (assistant):**
   ```bash
   brew install ollama
   ollama serve
   ollama pull llama3.2:3b
   ```
   Defaults: `ASSISTANT_PROVIDER=ollama`, `ASSISTANT_MODEL=llama3.2:3b`, `ASSISTANT_BASE_URL=http://localhost:11434/v1`. See [`app/README.md`](app/README.md) for assistant env vars.

3. **Run locally (UI + API):**
   ```bash
   ./run-local.sh
   ```
   Open [http://localhost:8080/](http://localhost:8080/) — sign in with `alice` / `Password!1`

4. Build and test API:
   - `cd app && mvn -B clean verify`
5. Validate Terraform:
   - `cd infra/terraform/environments/dev`
   - `terraform init`
   - `terraform validate`

## Deploy to AWS (dev)

Two layers — keep them separate:

| Layer | When | Command |
|---|---|---|
| **Infrastructure** (VPC, ECS, DDB, Route53) | Module or env changes | `./infra-deploy.sh plan dev` then `./infra-deploy.sh apply dev` |
| **Application release** (Docker → ECR → ECS) | Every software release | **Push to `main`** (GitHub Actions) or manual `./deploy-dev.sh` |

**First-time / infra change:**

```bash
cp .env.example .env   # if not done yet
./infra-deploy.sh plan dev
./infra-deploy.sh apply dev
git push origin main   # triggers CI/CD: build → ECR → ECS rollout
```

All deploy scripts (`run-local.sh`, `infra-deploy.sh`, `deploy-dev.sh`) source root `.env` via [`scripts/load-env.sh`](scripts/load-env.sh).

**Routine software release (preferred — GitHub CI/CD):**

```bash
git push origin main
```

GitHub Actions [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs `mvn verify`, builds the Docker image, pushes to `financeapp-dr-dev-repo` in ECR, and force-deploys ECS in **us-east-1** and **us-west-2**.

**Manual release fallback:**

```bash
./deploy-dev.sh
```

**Teardown:**

```bash
CONFIRM=destroy ./infra-deploy.sh destroy dev
```

**CI/CD (software release):** push or merge to `main` — [`.github/workflows/ci.yml`](.github/workflows/ci.yml) is the primary path from code to running containers:

| Job | Trigger | Steps |
|---|---|---|
| `quality` | PR, push to `main`, manual | `mvn verify` (Java tests) |
| `deploy-dev` | Push to `main` after `quality` passes | Docker build → **push ECR** (`:$GITHUB_SHA` + `:latest`) → **ECS force-new-deployment** (primary + secondary) |

Re-deploy without a code change: **Actions → FinanceApp DR CI/CD → Run workflow**.

**CI/CD (infra):** manual [**Apply Infrastructure (Terraform)**](.github/workflows/infra-apply.yml) workflow — type `apply` to confirm. Terraform is **not** run on every app push; validate locally with `terraform validate` before applying.

Copy [`.env.example`](.env.example) to `.env` for local scripts, and [`infra/terraform/environments/dev/terraform.tfvars.example`](infra/terraform/environments/dev/terraform.tfvars.example) to `terraform.tfvars` for Terraform defaults. Optional `TF_VAR_*` entries in `.env` override tfvars secrets.

**GitHub repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Used by |
|---|---|
| `AWS_ACCESS_KEY_ID` | `ci.yml` deploy, `infra-apply.yml` |
| `AWS_SECRET_ACCESS_KEY` | `ci.yml` deploy, `infra-apply.yml` |
| `TF_VAR_JWT_SECRET_DEV` | `infra-apply.yml` (optional) |
| `TF_VAR_SECRET_VALUE_DEV` | `infra-apply.yml` (optional) |

CI deploy uses a dedicated IAM user (`github-actions-financeapp-dr`) with least-privilege ECR push and ECS update permissions — see [`infra/iam/github-actions-financeapp-dr-dev-policy.json`](infra/iam/github-actions-financeapp-dr-dev-policy.json).
