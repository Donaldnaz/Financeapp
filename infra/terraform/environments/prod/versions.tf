terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.50"
    }
  }

  backend "s3" {
    bucket         = "financeapp-tf-state-920375856513"
    key            = "financeapp/prod/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "financeapp-tf-locks"
    encrypt        = true
  }
}
