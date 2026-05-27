#!/usr/bin/env bash
# Manual dev app deploy (ECR build/push + ECS rollout). Infra: ./infra-deploy.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/load-env.sh"
load_repo_env "$ROOT"

export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_SECONDARY_REGION="${AWS_SECONDARY_REGION:-us-west-2}"
AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-920375856513}"
IMAGE_TAG="${IMAGE_TAG:-$(date +%Y%m%d-%H%M%S)}"
ECR_REPLICATION_WAIT_SECONDS="${ECR_REPLICATION_WAIT_SECONDS:-30}"
TF_DIR="$ROOT/infra/terraform/environments/dev"

tf_output() {
  local name="$1"
  local fallback="$2"
  local value=""
  if command -v terraform >/dev/null 2>&1; then
    value="$(terraform -chdir="$TF_DIR" output -raw "$name" 2>/dev/null || true)"
  fi
  if [[ -n "$value" ]]; then
    echo "$value"
  else
    echo "$fallback"
  fi
}

ECR_REPO="${ECR_REPO:-$(tf_output ecr_repository_name financeapp-dr-dev-repo)}"
ECR_REGISTRY="${ECR_REGISTRY:-${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com}"
ECS_PRIMARY_CLUSTER="${ECS_PRIMARY_CLUSTER:-$(tf_output primary_cluster_name financeapp-dr-dev-primary-cluster)}"
ECS_PRIMARY_SERVICE="${ECS_PRIMARY_SERVICE:-$(tf_output primary_service_name financeapp-dr-dev-primary-service)}"
ECS_SECONDARY_CLUSTER="${ECS_SECONDARY_CLUSTER:-$(tf_output secondary_cluster_name financeapp-dr-dev-secondary-cluster)}"
ECS_SECONDARY_SERVICE="${ECS_SECONDARY_SERVICE:-$(tf_output secondary_service_name financeapp-dr-dev-secondary-service)}"

echo "=== ECR login ==="
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY"

echo "=== Build image ==="
docker build -t "$ECR_REGISTRY/$ECR_REPO:$IMAGE_TAG" -t "$ECR_REGISTRY/$ECR_REPO:latest" "$ROOT/app"

echo "=== Push image ==="
docker push "$ECR_REGISTRY/$ECR_REPO:$IMAGE_TAG"
docker push "$ECR_REGISTRY/$ECR_REPO:latest"

echo "=== Roll out primary ECS ($AWS_REGION) ==="
aws ecs update-service \
  --cluster "$ECS_PRIMARY_CLUSTER" \
  --service "$ECS_PRIMARY_SERVICE" \
  --force-new-deployment \
  --region "$AWS_REGION" \
  --query 'service.{status:status,desired:desiredCount,running:runningCount,deployment:deployments[0].status}' \
  --output json

echo "=== Wait for ECR replication ==="
sleep "$ECR_REPLICATION_WAIT_SECONDS"

echo "=== Roll out secondary ECS ($AWS_SECONDARY_REGION) ==="
aws ecs update-service \
  --cluster "$ECS_SECONDARY_CLUSTER" \
  --service "$ECS_SECONDARY_SERVICE" \
  --force-new-deployment \
  --region "$AWS_SECONDARY_REGION" \
  --query 'service.{status:status,desired:desiredCount,running:runningCount,deployment:deployments[0].status}' \
  --output json

echo "=== Wait for services to stabilize ==="
aws ecs wait services-stable \
  --cluster "$ECS_PRIMARY_CLUSTER" \
  --services "$ECS_PRIMARY_SERVICE" \
  --region "$AWS_REGION"
aws ecs wait services-stable \
  --cluster "$ECS_SECONDARY_CLUSTER" \
  --services "$ECS_SECONDARY_SERVICE" \
  --region "$AWS_SECONDARY_REGION"

echo "=== Deploy complete: $ECR_REGISTRY/$ECR_REPO:$IMAGE_TAG ==="
