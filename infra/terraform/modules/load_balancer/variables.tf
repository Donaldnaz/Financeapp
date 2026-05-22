variable "name" {
  description = "Name prefix for load balancer resources."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID for the target group."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnet IDs for the ALB."
  type        = list(string)
}

variable "alb_sg_id" {
  description = "Security group ID attached to the ALB."
  type        = string
}

variable "container_port" {
  description = "Target group port (must match the container port)."
  type        = number
  default     = 8080
}

variable "listener_port" {
  description = "ALB listener port."
  type        = number
  default     = 80
}

variable "health_check_path" {
  description = "Target group health check path."
  type        = string
  default     = "/health"
}

variable "internal" {
  description = "Whether the ALB is internal."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Common tags."
  type        = map(string)
  default     = {}
}
