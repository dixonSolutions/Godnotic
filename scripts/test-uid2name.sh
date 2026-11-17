#!/bin/bash
set -euo pipefail
ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
python3 "$ROOT/tests/uid2name/ask_test.py"
