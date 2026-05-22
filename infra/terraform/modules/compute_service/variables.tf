variable "name" {
  description = "Name prefix for compute resources."
  type        = string
}

variable "region" {
  description = "AWS region for logs."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnet IDs for ALB."
  type        = list(string)
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for ECS tasks."
  type        = list(string)
}

variable "alb_sg_id" {
  description = "ALB security group ID."
  type        = string
}

variable "ecs_sg_id" {
  description = "ECS security group ID."
  type        = string
}

variable "container_image" {
  description = "Container image URI."
  type        = string
}

variable "container_port" {
  description = "Container port."
  type        = number
  default     = 8080
}

variable "desired_count" {
  description = "Desired ECS task count."
  type        = number
  default     = 2
}

variable "cpu" {
  description = "Fargate CPU units."
  type        = number
  default     = 512
}

variable "memory" {
  description = "Fargate memory in MiB."
  type        = number
  default     = 1024
}

variable "execution_role_policy_arns" {
  description = "Policy ARNs to attach to execution role."
  type        = list(string)
}

variable "task_role_policy_arns" {
  description = "Policy ARNs to attach to task role."
  type        = list(string)
  default     = []
}

variable "environment" {
  description = "Environment variables for container."
  type        = map(string)
  default     = {}
}

variable "health_check_path" {
  description = "Target group health check path."
  type        = string
  default     = "/health"
}

variable "tags" {
  description = "Common tags."
  type        = map(string)
  default     = {}
}
