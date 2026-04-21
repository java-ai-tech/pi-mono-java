#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if command -v python3.10 >/dev/null 2>&1; then
  PYTHON_BIN="python3.10"
elif command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="python3"
else
  echo "[error] python3.10 or python3 is required" >&2
  exit 127
fi

RAW_INPUT="${*:-}"
exec "$PYTHON_BIN" "$SCRIPT_DIR/scripts/planning_query_router.py" "$RAW_INPUT"
