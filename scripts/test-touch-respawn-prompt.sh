#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# "You are dead, tap RESPAWN": the prompt names the button drawn while dead. No engine, no display.
set -euo pipefail
ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
python3 "$ROOT/tests/touch-respawn-prompt/check.py" "$ROOT"
