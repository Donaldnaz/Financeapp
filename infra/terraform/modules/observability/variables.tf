variable "name" {
  description = "Name prefix for alarms."
  type        = string
}

variable "alb_arn_suffix" {
  description = "ALB ARN suffix."
  type        = string
}

variable "target_group_arn_suffix" {
  description = "Target group ARN suffix."
  type        = string
}

variable "cluster_name" {
  description = "ECS cluster name."
  type        = string
}

variable "service_name" {
  description = "ECS service name."
  type        = string
}

variable "alb_5xx_threshold" {
  description = "Threshold for ALB 5XX."
  type        = number
  default     = 5
}

variable "ecs_cpu_threshold" {
  description = "Threshold for ECS CPU utilization."
  type        = number
  default     = 80
}

variable "tags" {
  description = "Common tags."
  type        = map(string)
  default     = {}
}
