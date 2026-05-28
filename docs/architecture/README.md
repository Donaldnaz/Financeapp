# FinanceApp DR — Dev AWS Architecture

Standard AWS architecture diagram for the **dev** active-active stack (`us-east-1` + `us-west-2`), using official **AWS Architecture Icons** in draw.io.

## Files

| File | Description |
|------|-------------|
| [`financeapp-dr-dev-aws.drawio`](financeapp-dr-dev-aws.drawio) | Editable source (open in [diagrams.net](https://app.diagrams.net) or draw.io desktop) |
| [`financeapp-dr-dev-aws.png`](financeapp-dr-dev-aws.png) | PNG export for README / portfolio |
| [`financeapp-dr-dev-aws.svg`](financeapp-dr-dev-aws.svg) | Scalable export |

## Network layout (dev)

| Region | VPC | Public subnets (AZ-a / AZ-b) | Private subnets (AZ-a / AZ-b) |
|--------|-----|------------------------------|-------------------------------|
| **us-east-1** | `10.10.0.0/16` | `10.10.0.0/24`, `10.10.1.0/24` | `10.10.10.0/24`, `10.10.11.0/24` |
| **us-west-2** | `10.20.0.0/16` | `10.20.0.0/24`, `10.20.1.0/24` | `10.20.10.0/24`, `10.20.11.0/24` |

Each VPC has one Internet Gateway, one NAT Gateway (public AZ-a), an internet-facing ALB in public subnets, and ECS Fargate tasks in private subnets (egress via NAT).

## Global services

| Service | Resource |
|---------|----------|
| DNS | Route53 latency routing → `api-dev.ikenna-financeapp-dr.com` |
| Data | DynamoDB Global Table `financeapp-dr-dev-transactions` |
| Secrets | Secrets Manager `financeapp-dr-dev/app/config` (replica in us-west-2) |
| Images | ECR `financeapp-dr-dev-repo` (replicated us-east-1 → us-west-2) |
| CI/CD | GitHub Actions `ci.yml` → ECR push → ECS force-new-deployment |

## Re-export after editing

1. Open `financeapp-dr-dev-aws.drawio` in draw.io.
2. Enable **AWS** shape library if icons appear broken: **More shapes → AWS19 / AWS 2021**.
3. Export:
   - **GUI:** File → Export as → PNG / SVG
   - **CLI** (draw.io desktop):

```bash
drawio -x -f png -b 10 -s 1.5 -o financeapp-dr-dev-aws.png financeapp-dr-dev-aws.drawio
drawio -x -f svg -b 10 -o financeapp-dr-dev-aws.svg financeapp-dr-dev-aws.drawio
```

Source of truth for resource names and CIDRs: [`infra/terraform/environments/dev/main.tf`](../../infra/terraform/environments/dev/main.tf).
