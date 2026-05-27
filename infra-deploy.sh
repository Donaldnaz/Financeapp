#!/usr/bin/env bash
# Terraform lifecycle wrapper for infra/terraform/environments/{dev,prod}.
# App releases stay separate: ./deploy-dev.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/load-env.sh"
load_repo_env "$ROOT"
export_terraform_oauth_vars "$ROOT"

usage() {
  cat <<'EOF'
Usage:
  ./infra-deploy.sh plan   <dev|prod> [terraform -var flags...]
  ./infra-deploy.sh apply  <dev|prod> [terraform -var flags...]
  ./infra-deploy.sh output <dev|prod>
  ./infra-deploy.sh destroy <dev|prod>   # requires CONFIRM=destroy

Apply options:
  - Uses infra/terraform/environments/<env>/tfplan when present (from plan).
  - Or set AUTO_APPROVE=1 to apply directly without a saved plan.

Secrets (optional; override terraform.tfvars via .env or export):
  TF_VAR_jwt_secret, TF_VAR_secret_value, TF_VAR_google_client_id, TF_VAR_google_client_secret
  GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET in .env are mapped automatically for dev/prod apply.
  AUTO_APPROVE
  Copy .env.example to .env and edit once for repeatable deploys.

Examples:
  ./infra-deploy.sh plan dev
  ./infra-deploy.sh apply dev
  ./infra-deploy.sh apply dev -var='container_image=920375856513.dkr.ecr.us-east-1.amazonaws.com/financeapp-dr-dev-repo:latest'
  CONFIRM=destroy ./infra-deploy.sh destroy dev
EOF
  exit 1
}

CMD="${1:-}"
ENV="${2:-}"
if [[ -z "$CMD" || -z "$ENV" ]]; then
  usage
fi
shift 2 || true
EXTRA_ARGS=("$@")

case "$ENV" in
  dev|prod) ;;
  *) echo "Unknown environment: $ENV (use dev or prod)" >&2; exit 1 ;;
esac

TF_DIR="$ROOT/infra/terraform/environments/$ENV"
PLAN_FILE="$TF_DIR/tfplan"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

preflight() {
  require_cmd terraform
  require_cmd aws
  aws sts get-caller-identity --output text >/dev/null
  if [[ ! -d "$TF_DIR" ]]; then
    echo "Terraform environment directory not found: $TF_DIR" >&2
    exit 1
  fi
}

tf_init() {
  terraform -chdir="$TF_DIR" init -input=false
}

empty_dev_ecr_if_destroy() {
  if [[ "$ENV" != "dev" ]]; then
    return 0
  fi
  local region="${AWS_REGION:-us-east-1}"
  local repo="${ECR_REPO:-financeapp-dr-dev-repo}"
  if ! aws ecr describe-repositories --repository-names "$repo" --region "$region" >/dev/null 2>&1; then
    return 0
  fi
  echo "=== Emptying ECR repository $repo (allows destroy) ==="
  local image_ids
  image_ids="$(aws ecr list-images --repository-name "$repo" --region "$region" --query 'imageIds' --output json)"
  if [[ "$image_ids" != "[]" && "$image_ids" != "null" ]]; then
    aws ecr batch-delete-image --repository-name "$repo" --region "$region" --image-ids "$image_ids"
  fi
}

preflight
tf_init

case "$CMD" in
  plan)
    if ((${#EXTRA_ARGS[@]})); then
      terraform -chdir="$TF_DIR" plan -input=false -out=tfplan "${EXTRA_ARGS[@]}"
    else
      terraform -chdir="$TF_DIR" plan -input=false -out=tfplan
    fi
    echo "Plan saved to $PLAN_FILE"
    ;;
  apply)
    if [[ -f "$PLAN_FILE" ]]; then
      terraform -chdir="$TF_DIR" apply -input=false tfplan
      rm -f "$PLAN_FILE"
    elif [[ "${AUTO_APPROVE:-}" == "1" ]]; then
      if ((${#EXTRA_ARGS[@]})); then
        terraform -chdir="$TF_DIR" apply -input=false -auto-approve "${EXTRA_ARGS[@]}"
      else
        terraform -chdir="$TF_DIR" apply -input=false -auto-approve
      fi
    else
      echo "No saved plan at $PLAN_FILE. Run './infra-deploy.sh plan $ENV' first, or set AUTO_APPROVE=1." >&2
      exit 1
    fi
    ;;
  output)
    terraform -chdir="$TF_DIR" output -json
    ;;
  destroy)
    if [[ "${CONFIRM:-}" != "destroy" ]]; then
      echo "Refusing destroy. Re-run with CONFIRM=destroy" >&2
      exit 1
    fi
    empty_dev_ecr_if_destroy
    if ((${#EXTRA_ARGS[@]})); then
      terraform -chdir="$TF_DIR" destroy -input=false -auto-approve "${EXTRA_ARGS[@]}"
    else
      terraform -chdir="$TF_DIR" destroy -input=false -auto-approve
    fi
    rm -f "$PLAN_FILE"
    ;;
  *)
    usage
    ;;
esac
