output "alb_sg_id" {
  value       = aws_security_group.alb.id
  description = "ALB security group ID."
}

output "ecs_sg_id" {
  value       = aws_security_group.ecs.id
  description = "ECS security group ID."
}
