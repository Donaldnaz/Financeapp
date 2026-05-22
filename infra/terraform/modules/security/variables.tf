variable "name" {
  description = "Name prefix for security groups."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID."
  type        = string
}

variable "app_port" {
  description = "Application port."
  type        = number
  default     = 8080
}

variable "alb_ingress_cidrs" {
  description = "CIDRs allowed to hit ALB."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "tags" {
  description = "Common tags."
  type        = map(string)
  default     = {}
}
