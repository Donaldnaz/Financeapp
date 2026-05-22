variable "table_name" {
  description = "Global table name."
  type        = string
}

variable "replica_regions" {
  description = "Replica regions for global table."
  type        = list(string)
}

variable "tags" {
  description = "Common tags."
  type        = map(string)
  default     = {}
}
