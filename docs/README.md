# Documentation

- [`docs/architecture/`](architecture/) — AWS architecture diagram (draw.io, PNG, SVG) with VPC and subnet layout
- `RUNBOOK.md` - operational procedures, incident response, failover and failback (includes CI/CD and deploy steps).
- `DR-TEST-RESULTS.md` - recorded disaster recovery simulation outcomes.
- `PORTFOLIO.md` - design rationale and simplifications for interview review.

**CI/CD workflows** (repo root `.github/workflows/`):

- `ci.yml` — on push to `main`: Java tests, Docker build, ECR push, ECS rollout (both regions).
- `infra-apply.yml` — manual Terraform apply for dev or prod (not part of routine app releases).
