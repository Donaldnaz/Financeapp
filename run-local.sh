#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/load-env.sh"
load_repo_env "$ROOT"

PROVIDER="${ASSISTANT_PROVIDER:-ollama}"
MODEL="${ASSISTANT_MODEL:-llama3.2:3b}"
if [[ "$PROVIDER" == "ollama" ]]; then
  if ! curl -sf --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
    echo "Warning: Ollama does not appear to be running on localhost:11434."
    echo "The app will start anyway; assistant chat will fail until Ollama is up."
    echo "Install/start it with: brew install ollama && ollama serve && ollama pull ${MODEL}"
  else
    echo "Warming Ollama model ${MODEL} in background (won't block startup)…"
    curl -sf --max-time 5 http://localhost:11434/api/generate \
      -d "{\"model\":\"${MODEL}\",\"prompt\":\"hi\",\"stream\":false}" >/dev/null 2>&1 &
  fi
fi

cd "$ROOT/app"
exec mvn spring-boot:run -Dspring-boot.run.arguments=--app.seed.enabled=true
