variable "name" {
  description = "Name prefix for IAM roles."
  type        = string
}

variable "execution_role_policy_arns" {
  description = "Policy ARNs to attach to the ECS task execution role."
  type        = list(string)
}

variable "task_role_policy_arns" {
  description = "Policy ARNs to attach to the ECS task role."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Common tags."
  type        = map(string)
  default     = {}
}
