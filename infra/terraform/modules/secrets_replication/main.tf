resource "aws_secretsmanager_secret" "app" {
  name        = var.secret_name
  description = "Shared secret for active-active API."

  replica {
    region = var.replica_region
  }

  tags = merge(var.tags, {
    Name = var.secret_name
  })
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id     = aws_secretsmanager_secret.app.id
  secret_string = var.secret_value
}
