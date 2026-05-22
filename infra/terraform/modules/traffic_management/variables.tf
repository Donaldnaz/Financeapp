variable "name" {
  description = "Name prefix for Route53 resources."
  type        = string
}

variable "hosted_zone_id" {
  description = "Public hosted zone ID."
  type        = string
}

variable "record_name" {
  description = "DNS name for active-active API."
  type        = string
}

variable "region_a_alb_dns_name" {
  description = "Region A ALB DNS name."
  type        = string
}

variable "region_a_alb_zone_id" {
  description = "Region A ALB zone ID."
  type        = string
}

variable "region_b_alb_dns_name" {
  description = "Region B ALB DNS name."
  type        = string
}

variable "region_b_alb_zone_id" {
  description = "Region B ALB zone ID."
  type        = string
}

variable "routing_policy" {
  description = "Route53 routing policy: 'latency' (recommended for active-active) or 'weighted' (canary/blue-green)."
  type        = string
  default     = "latency"

  validation {
    condition     = contains(["latency", "weighted"], var.routing_policy)
    error_message = "routing_policy must be one of: latency, weighted."
  }
}

variable "region_a_aws_region" {
  description = "AWS region for region A (required when routing_policy = 'latency')."
  type        = string
  default     = null
}

variable "region_b_aws_region" {
  description = "AWS region for region B (required when routing_policy = 'latency')."
  type        = string
  default     = null
}

variable "region_a_weight" {
  description = "Weighted routing weight for region A (used when routing_policy = 'weighted')."
  type        = number
  default     = 100
}

variable "region_b_weight" {
  description = "Weighted routing weight for region B (used when routing_policy = 'weighted')."
  type        = number
  default     = 100
}

variable "health_check_path" {
  description = "Health check path."
  type        = string
  default     = "/health"
}

variable "tags" {
  description = "Common tags."
  type        = map(string)
  default     = {}
}
