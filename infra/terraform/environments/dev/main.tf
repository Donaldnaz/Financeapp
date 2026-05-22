provider "aws" {
  region = var.primary_region
}

provider "aws" {
  alias  = "secondary"
  region = var.secondary_region
}

data "aws_availability_zones" "primary" {
  state = "available"
}

data "aws_availability_zones" "secondary" {
  provider = aws.secondary
  state    = "available"
}

locals {
  prefix = "${var.project_name}-${var.environment}"
  tags = {
    Project     = var.project_name
    Environment = var.environment
    ManagedBy   = "terraform"
    Portfolio   = "true"
  }
}

resource "aws_ecr_repository" "app" {
  name                 = "${local.prefix}-repo"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = local.tags
}

resource "aws_ecr_replication_configuration" "primary_to_secondary" {
  replication_configuration {
    rule {
      destination {
        region      = var.secondary_region
        registry_id = data.aws_caller_identity.current.account_id
      }

      repository_filter {
        filter      = "financeapp-dr-"
        filter_type = "PREFIX_MATCH"
      }
    }
  }
}

data "aws_caller_identity" "current" {}

module "network_primary" {
  source = "../../modules/network"

  name                 = "${local.prefix}-primary"
  vpc_cidr             = "10.10.0.0/16"
  public_subnet_cidrs  = ["10.10.0.0/24", "10.10.1.0/24"]
  private_subnet_cidrs = ["10.10.10.0/24", "10.10.11.0/24"]
  availability_zones   = slice(data.aws_availability_zones.primary.names, 0, 2)
  tags                 = local.tags
}

module "network_secondary" {
  source    = "../../modules/network"
  providers = { aws = aws.secondary }

  name                 = "${local.prefix}-secondary"
  vpc_cidr             = "10.20.0.0/16"
  public_subnet_cidrs  = ["10.20.0.0/24", "10.20.1.0/24"]
  private_subnet_cidrs = ["10.20.10.0/24", "10.20.11.0/24"]
  availability_zones   = slice(data.aws_availability_zones.secondary.names, 0, 2)
  tags                 = local.tags
}

module "security_primary" {
  source = "../../modules/security"

  name   = "${local.prefix}-primary"
  vpc_id = module.network_primary.vpc_id
  tags   = local.tags
}

module "security_secondary" {
  source    = "../../modules/security"
  providers = { aws = aws.secondary }

  name   = "${local.prefix}-secondary"
  vpc_id = module.network_secondary.vpc_id
  tags   = local.tags
}

module "data_global" {
  source = "../../modules/data_global"

  table_name      = "${local.prefix}-transactions"
  replica_regions = [var.secondary_region]
  tags            = local.tags
}

module "secrets_replication" {
  source = "../../modules/secrets_replication"

  secret_name    = "${local.prefix}/app/config"
  secret_value   = var.secret_value
  replica_region = var.secondary_region
  tags           = local.tags
}

module "compute_primary" {
  source = "../../modules/compute_service"

  name               = "${local.prefix}-primary"
  region             = var.primary_region
  vpc_id             = module.network_primary.vpc_id
  public_subnet_ids  = module.network_primary.public_subnet_ids
  private_subnet_ids = module.network_primary.private_subnet_ids
  alb_sg_id          = module.security_primary.alb_sg_id
  ecs_sg_id          = module.security_primary.ecs_sg_id
  container_image    = var.container_image
  desired_count      = 1
  execution_role_policy_arns = [
    "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
  ]
  task_role_policy_arns = [
    "arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess",
    "arn:aws:iam::aws:policy/SecretsManagerReadWrite"
  ]
  environment = {
    APP_STORAGE_TYPE   = "dynamodb"
    TRANSACTIONS_TABLE = module.data_global.table_name
    AWS_REGION         = var.primary_region
    APP_JWT_SECRET     = var.jwt_secret
    APP_SEED_ENABLED   = "true"
    APP_COOKIE_SECURE  = "false"
  }
  tags = local.tags
}

module "compute_secondary" {
  source    = "../../modules/compute_service"
  providers = { aws = aws.secondary }

  name               = "${local.prefix}-secondary"
  region             = var.secondary_region
  vpc_id             = module.network_secondary.vpc_id
  public_subnet_ids  = module.network_secondary.public_subnet_ids
  private_subnet_ids = module.network_secondary.private_subnet_ids
  alb_sg_id          = module.security_secondary.alb_sg_id
  ecs_sg_id          = module.security_secondary.ecs_sg_id
  container_image    = var.container_image
  desired_count      = 1
  execution_role_policy_arns = [
    "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
  ]
  task_role_policy_arns = [
    "arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess",
    "arn:aws:iam::aws:policy/SecretsManagerReadWrite"
  ]
  environment = {
    APP_STORAGE_TYPE   = "dynamodb"
    TRANSACTIONS_TABLE = module.data_global.table_name
    AWS_REGION         = var.secondary_region
    APP_JWT_SECRET     = var.jwt_secret
    APP_SEED_ENABLED   = "true"
    APP_COOKIE_SECURE  = "false"
  }
  tags = local.tags
}

module "traffic_management" {
  source = "../../modules/traffic_management"

  name                  = local.prefix
  hosted_zone_id        = var.hosted_zone_id
  record_name           = var.domain_name
  routing_policy        = "latency"
  region_a_alb_dns_name = module.compute_primary.alb_dns_name
  region_a_alb_zone_id  = module.compute_primary.alb_zone_id
  region_a_aws_region   = var.primary_region
  region_b_alb_dns_name = module.compute_secondary.alb_dns_name
  region_b_alb_zone_id  = module.compute_secondary.alb_zone_id
  region_b_aws_region   = var.secondary_region
  tags                  = local.tags
}

module "observability_primary" {
  source = "../../modules/observability"

  name                    = "${local.prefix}-primary"
  alb_arn_suffix          = module.compute_primary.alb_arn_suffix
  target_group_arn_suffix = module.compute_primary.target_group_arn_suffix
  cluster_name            = module.compute_primary.cluster_name
  service_name            = module.compute_primary.service_name
  tags                    = local.tags
}

module "observability_secondary" {
  source    = "../../modules/observability"
  providers = { aws = aws.secondary }

  name                    = "${local.prefix}-secondary"
  alb_arn_suffix          = module.compute_secondary.alb_arn_suffix
  target_group_arn_suffix = module.compute_secondary.target_group_arn_suffix
  cluster_name            = module.compute_secondary.cluster_name
  service_name            = module.compute_secondary.service_name
  tags                    = local.tags
}
