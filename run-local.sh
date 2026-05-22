#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/app"
exec mvn spring-boot:run -Dspring-boot.run.arguments=--app.seed.enabled=true "$@"
