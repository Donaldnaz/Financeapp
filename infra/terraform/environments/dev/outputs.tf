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
