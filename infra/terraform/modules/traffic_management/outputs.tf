output "record_fqdn" {
  value       = aws_route53_record.region_a.fqdn
  description = "Active-active API FQDN."
}
