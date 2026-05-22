variable "name" {
  description = "Name prefix for network resources."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR block."
  type        = string
}

variable "public_subnet_cidrs" {
  description = "Public subnet CIDRs."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "Private subnet CIDRs."
  type        = list(string)
}

variable "availability_zones" {
  description = "Availability zones aligned with subnet CIDRs."
  type        = list(string)
}

variable "tags" {
  description = "Common tags."
  type        = map(string)
  default     = {}
}
