# Production-Ready Java DR App (AWS + Terraform, Active-Active)

Portfolio project demonstrating a simple but production-minded Spring Boot API deployed across two AWS regions with active-active traffic routing and globally replicated data.

## Repository Layout

- `app/` - Spring Boot REST API
- `infra/terraform/` - Modular Terraform for AWS infrastructure
- `docs/` - Runbook, DR test evidence, and architecture notes
- `.github/workflows/` - CI checks for Java and Terraform

## High-Level Capabilities

- Active-active multi-region API on ECS Fargate
- Route53 latency/health-based routing
- DynamoDB Global Tables for cross-region data replication
- Secrets Manager regional replication pattern
- Observability, alarms, and incident runbook

## Quick Start

1. **Run locally (UI + API):**
   ```bash
   ./run-local.sh
   # or: cd app && mvn spring-boot:run -Dspring-boot.run.arguments=--app.seed.enabled=true
   ```
   Open [http://localhost:8080/](http://localhost:8080/) — sign in with `alice` / `Password!1`

2. Build and test API:
   - `cd app && mvn -B clean verify`
3. Validate Terraform:
   - `cd infra/terraform/environments/dev`
   - `terraform init`
   - `terraform validate`

## Deploy to AWS (dev)

**CI/CD (recommended):** push or merge to `main` — GitHub Actions runs tests, builds the Docker image, pushes to ECR, and force-rolls both ECS services (`us-east-1` + `us-west-2`). You can also re-deploy from the Actions tab via **Run workflow** (`workflow_dispatch`).

**Manual fallback:** `./deploy-dev.sh` (requires AWS CLI + Docker, same steps as the pipeline).

**Infrastructure changes:** run `terraform apply` in `infra/terraform/environments/dev` only when modules change — not on every app deploy.

Repository secrets required for CI deploy: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`.
