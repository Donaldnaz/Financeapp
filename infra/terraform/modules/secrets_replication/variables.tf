variable "secret_name" {
  description = "Secrets Manager secret name."
  type        = string
}

variable "secret_value" {
  description = "Initial secret value."
  type        = string
  sensitive   = true
}

variable "replica_region" {
  description = "Region to replicate secret to."
  type        = string
}

variable "tags" {
  description = "Common tags."
  type        = map(string)
  default     = {}
}
