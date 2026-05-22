output "vpc_id" {
  value       = aws_vpc.this.id
  description = "VPC ID."
}

output "public_subnet_ids" {
  value       = [for subnet in aws_subnet.public : subnet.id]
  description = "Public subnet IDs."
}

output "private_subnet_ids" {
  value       = [for subnet in aws_subnet.private : subnet.id]
  description = "Private subnet IDs."
}
