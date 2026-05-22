output "execution_role_arn" {
  description = "ECS task execution role ARN."
  value       = aws_iam_role.execution.arn
}

output "execution_role_name" {
  description = "ECS task execution role name."
  value       = aws_iam_role.execution.name
}

output "task_role_arn" {
  description = "ECS task role ARN."
  value       = aws_iam_role.task.arn
}

output "task_role_name" {
  description = "ECS task role name."
  value       = aws_iam_role.task.name
}
