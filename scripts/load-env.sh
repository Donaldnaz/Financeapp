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
