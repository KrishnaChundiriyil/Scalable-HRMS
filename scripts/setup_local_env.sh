#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import secrets, base64, os
from pathlib import Path
path = Path('.env')
existing = path.read_text() if path.exists() else ''
present = {line.split('=', 1)[0].strip() for line in existing.splitlines() if '=' in line}
defaults = {
    'HRMS_JWT_SECRET_BASE64': base64.b64encode(secrets.token_bytes(48)).decode(),
    'EMPLOYEE_SERVICE_INTERNAL_TOKEN': secrets.token_urlsafe(40),
    'SPRING_PROFILES_ACTIVE': 'dev',
}
missing = {key: value for key, value in defaults.items() if key not in present}
if missing:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, 'a') as output:
        if existing and not existing.endswith('\n'):
            output.write('\n')
        for key, value in missing.items():
            output.write(f'{key}={value}\n')
path.chmod(0o600)
print('Private .env ready; existing values preserved. Do not commit or share it.')
PY
