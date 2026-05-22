output "alb_arn" {
  description = "Application Load Balancer ARN."
  value       = aws_lb.this.arn
}

output "alb_dns_name" {
  description = "ALB DNS name."
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "ALB hosted zone ID."
  value       = aws_lb.this.zone_id
}

output "alb_arn_suffix" {
  description = "ALB ARN suffix for CloudWatch metrics."
  value       = aws_lb.this.arn_suffix
}

output "target_group_arn" {
  description = "Target group ARN."
  value       = aws_lb_target_group.app.arn
}

output "target_group_arn_suffix" {
  description = "Target group ARN suffix for CloudWatch metrics."
  value       = aws_lb_target_group.app.arn_suffix
}

output "listener_arn" {
  description = "HTTP listener ARN."
  value       = aws_lb_listener.http.arn
}
