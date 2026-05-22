# Terraform Infrastructure

This directory contains modular Terraform code for deploying an active-active Java API across two AWS regions.

## Structure

- `bootstrap/` one-time stack that codifies the state backend (S3 versioning); uses **local state** so it can manage the remote backend itself
- `modules/` reusable modules
- `environments/dev` development composition
- `environments/prod` production composition (currently destroyed; reapply with the env's `terraform.tfvars` to recreate)

## Modules

- `network`
- `security`
- `iam` — ECS task execution role and task role with managed policy attachments
- `load_balancer` — Application Load Balancer, target group, and HTTP listener
- `compute_service` — ECS Fargate cluster, task definition, and service (composes `iam` + `load_balancer`)
- `data_global` — single-table DynamoDB Global Table with `pk`+`sk`, GSI1, TTL, streams, PITR, multi-region replicas
- `observability`
- `traffic_management` — Route53 records (latency or weighted) + health checks
- `secrets_replication`

## Bootstrap (one-time)

```bash
cd bootstrap
terraform init
terraform import aws_s3_bucket_versioning.tf_state financeapp-tf-state-920375856513
terraform apply   # no-op once imported
```

This stack ensures `Versioning=Enabled` on the S3 bucket that backs every other Terraform state file. Local state is gitignored.

## Notes

- All environments use the shared S3 backend (`financeapp-tf-state-920375856513`) with DynamoDB locking (`financeapp-tf-locks`).
- Providers are split into primary (`us-east-1`) and secondary (`us-west-2`) aliases via `provider "aws" { alias = "secondary" }`.
- ECR replication from primary to secondary is configured once in `environments/dev` (account-scoped, prefix-filtered on `financeapp-dr-`).
