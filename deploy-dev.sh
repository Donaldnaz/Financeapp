#!/usr/bin/env bash
# Manual dev deploy fallback (same steps as .github/workflows/ci.yml deploy-dev job).
set -euo pipefail

export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_SECONDARY_REGION="${AWS_SECONDARY_REGION:-us-west-2}"
ECR_REGISTRY="${ECR_REGISTRY:-920375856513.dkr.ecr.us-east-1.amazonaws.com}"
ECR_REPO="${ECR_REPO:-financeapp-dr-dev-repo}"
IMAGE_TAG="${IMAGE_TAG:-$(date +%Y%m%d-%H%M%S)}"
ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "=== ECR login ==="
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY"

echo "=== Build image ==="
docker build -t "$ECR_REGISTRY/$ECR_REPO:$IMAGE_TAG" -t "$ECR_REGISTRY/$ECR_REPO:latest" "$ROOT/app"

echo "=== Push image ==="
docker push "$ECR_REGISTRY/$ECR_REPO:$IMAGE_TAG"
docker push "$ECR_REGISTRY/$ECR_REPO:latest"

echo "=== Roll out primary ECS ($AWS_REGION) ==="
aws ecs update-service \
  --cluster financeapp-dr-dev-primary-cluster \
  --service financeapp-dr-dev-primary-service \
  --force-new-deployment \
  --region "$AWS_REGION" \
  --query 'service.{status:status,desired:desiredCount,running:runningCount,deployment:deployments[0].status}' \
  --output json

echo "=== Wait for ECR replication ==="
sleep 30

echo "=== Roll out secondary ECS ($AWS_SECONDARY_REGION) ==="
aws ecs update-service \
  --cluster financeapp-dr-dev-secondary-cluster \
  --service financeapp-dr-dev-secondary-service \
  --force-new-deployment \
  --region "$AWS_SECONDARY_REGION" \
  --query 'service.{status:status,desired:desiredCount,running:runningCount,deployment:deployments[0].status}' \
  --output json

echo "=== Wait for services to stabilize ==="
aws ecs wait services-stable \
  --cluster financeapp-dr-dev-primary-cluster \
  --services financeapp-dr-dev-primary-service \
  --region "$AWS_REGION"
aws ecs wait services-stable \
  --cluster financeapp-dr-dev-secondary-cluster \
  --services financeapp-dr-dev-secondary-service \
  --region "$AWS_SECONDARY_REGION"

echo "=== Deploy complete: $ECR_REGISTRY/$ECR_REPO:$IMAGE_TAG ==="
