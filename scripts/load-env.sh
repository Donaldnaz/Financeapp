#!/usr/bin/env bash
# Source repo-root .env into the current shell (set -a export mode).
load_repo_env() {
  local root="$1"
  if [[ -f "$root/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$root/.env"
    set +a
  fi
}

# Map GOOGLE_* from .env to Terraform TF_VAR_* for ECS OAuth env injection.
export_terraform_oauth_vars() {
  local root="$1"
  if [[ -n "${GOOGLE_CLIENT_ID:-}" ]]; then
    export TF_VAR_google_client_id="$GOOGLE_CLIENT_ID"
  fi
  if [[ -n "${GOOGLE_CLIENT_SECRET:-}" ]]; then
    export TF_VAR_google_client_secret="$GOOGLE_CLIENT_SECRET"
  fi
  if [[ -n "${GOOGLE_REDIRECT_URI:-}" ]]; then
    export TF_VAR_google_redirect_uri="$GOOGLE_REDIRECT_URI"
    return 0
  fi
  if [[ -z "${TF_VAR_google_redirect_uri:-}" ]] && command -v terraform >/dev/null 2>&1; then
    local alb=""
    alb="$(terraform -chdir="$root/infra/terraform/environments/dev" output -raw primary_alb_dns 2>/dev/null || true)"
    if [[ -n "$alb" ]]; then
      export TF_VAR_google_redirect_uri="http://${alb}/login/oauth2/code/google"
    fi
  fi
}
