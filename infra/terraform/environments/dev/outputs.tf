output "api_record_fqdn" {
  value       = module.traffic_management.record_fqdn
  description = "Public API DNS record."
}

output "transactions_table_name" {
  value       = module.data_global.table_name
  description = "DynamoDB global table name."
}

output "ecr_repository_url" {
  value       = aws_ecr_repository.app.repository_url
  description = "ECR repository URL."
}

output "ecr_repository_name" {
  value       = aws_ecr_repository.app.name
  description = "ECR repository name."
}

output "primary_cluster_name" {
  value       = module.compute_primary.cluster_name
  description = "Primary ECS cluster name."
}

output "primary_service_name" {
  value       = module.compute_primary.service_name
  description = "Primary ECS service name."
}

output "secondary_cluster_name" {
  value       = module.compute_secondary.cluster_name
  description = "Secondary ECS cluster name."
}

output "secondary_service_name" {
  value       = module.compute_secondary.service_name
  description = "Secondary ECS service name."
}

output "primary_alb_dns" {
  value       = module.compute_primary.alb_dns_name
  description = "Primary ALB DNS name."
}

output "secondary_alb_dns" {
  value       = module.compute_secondary.alb_dns_name
  description = "Secondary ALB DNS name."
}
