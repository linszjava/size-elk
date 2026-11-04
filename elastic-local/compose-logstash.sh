#!/usr/bin/env bash
set -euo pipefail
CONFIG_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec docker compose -f "$CONFIG_DIR/compose.yml" -f "$CONFIG_DIR/compose.logstash.yml" "$@"
