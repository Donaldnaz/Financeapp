variable "project_name" {
  type        = string
  description = "Project name."
  default     = "financeapp-dr"
}

variable "environment" {
  type        = string
  description = "Environment name."
  default     = "dev"
}

variable "primary_region" {
  type        = string
  description = "Primary AWS region."
  default     = "us-east-1"
}

variable "secondary_region" {
  type        = string
  description = "Secondary AWS region."
  default     = "us-west-2"
}

variable "domain_name" {
  type        = string
  description = "Public DNS record for API."
}

variable "hosted_zone_id" {
  type        = string
  description = "Route53 hosted zone ID."
}

variable "container_image" {
  type        = string
  description = "ECR image URI for app."
}

variable "secret_value" {
  type        = string
  description = "Initial app secret value."
  sensitive   = true
}

variable "jwt_secret" {
  type        = string
  description = "HS256 signing key for app JWTs (min 32 chars)."
  sensitive   = true
}

variable "google_client_id" {
  type        = string
  description = "Google OAuth client ID. When set, enables Continue with Google on login/signup."
  default     = ""
}

variable "google_client_secret" {
  type        = string
  description = "Google OAuth client secret."
  sensitive   = true
  default     = ""
}

variable "google_redirect_uri" {
  type        = string
  description = "OAuth redirect URI registered in Google Cloud Console. Defaults to http://<domain_name>/login/oauth2/code/google."
  default     = ""
}
