#!/usr/bin/env bash
# Live walkthrough: needs existing Employee Service identities and its shared dev key.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/guided_demo.py
