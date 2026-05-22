# iTrust — dev environment
#
# Usage (from infra/terraform/environments/dev):
#   terraform init
#   terraform plan -input=false
#   terraform apply -input=false
#
# Override any value at apply time, e.g.:
#   terraform apply -var="container_image=920375856513.dkr.ecr.us-east-1.amazonaws.com/financeapp-dr-dev-repo:latest"
#
# Sensitive values below are committed for portfolio/local dev convenience.
# For CI, prefer GitHub secrets (TF_VAR_JWT_SECRET_DEV, TF_VAR_SECRET_VALUE_DEV).

# --- Core identity (have defaults in variables.tf; set explicitly for clarity) ---

project_name = "financeapp-dr"
environment  = "dev"

# --- Multi-region active-active topology ---

primary_region   = "us-east-1"
secondary_region = "us-west-2"

# --- DNS (Route53 latency routing → both regional ALBs) ---

domain_name    = "api-dev.ikenna-financeapp-dr.com"
hosted_zone_id = "Z02272842N6T33RBSSBSE"

# --- Application image (ECR; replicated us-east-1 → us-west-2) ---

container_image = "920375856513.dkr.ecr.us-east-1.amazonaws.com/financeapp-dr-dev-repo:latest"

# --- Secrets (sensitive) ---
# secret_value → AWS Secrets Manager (financeapp-dr-dev/app/config) with us-west-2 replica
# jwt_secret   → APP_JWT_SECRET on both ECS task definitions (must be ≥ 32 chars for HS256)

secret_value = "{\"apiKey\":\"financeapp-dev-bootstrap\"}"
jwt_secret   = "financeapp-dev-jwt-signing-key-32chars-min"
