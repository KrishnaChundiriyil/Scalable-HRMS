#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -e .env ]]; then echo '.env already exists; not overwriting'; exit 0; fi
secret=$(python3 - <<'PY'
import secrets,base64
print(base64.b64encode(secrets.token_bytes(48)).decode())
PY
)
printf 'HRMS_JWT_SECRET_BASE64=%s\nSPRING_PROFILES_ACTIVE=dev\n' "$secret" > .env
chmod 600 .env
echo 'Created private .env; do not commit it or share the secret.'
