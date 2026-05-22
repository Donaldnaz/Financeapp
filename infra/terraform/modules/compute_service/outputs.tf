output "alb_dns_name" {
  value       = module.load_balancer.alb_dns_name
  description = "ALB DNS name."
}

output "alb_zone_id" {
  value       = module.load_balancer.alb_zone_id
  description = "ALB hosted zone ID."
}

output "alb_arn_suffix" {
  value       = module.load_balancer.alb_arn_suffix
  description = "ALB ARN suffix for CloudWatch metrics."
}

output "target_group_arn_suffix" {
  value       = module.load_balancer.target_group_arn_suffix
  description = "Target group ARN suffix for CloudWatch metrics."
}

output "execution_role_arn" {
  value       = module.iam.execution_role_arn
  description = "ECS task execution role ARN."
}

output "task_role_arn" {
  value       = module.iam.task_role_arn
  description = "ECS task role ARN."
}

output "cluster_name" {
  value       = aws_ecs_cluster.this.name
  description = "ECS cluster name."
}

output "service_name" {
  value       = aws_ecs_service.this.name
  description = "ECS service name."
}
