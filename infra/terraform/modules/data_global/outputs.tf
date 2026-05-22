output "table_name" {
  value       = aws_dynamodb_table.transactions.name
  description = "DynamoDB global table name."
}

output "table_arn" {
  value       = aws_dynamodb_table.transactions.arn
  description = "DynamoDB global table ARN."
}
