#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
python3 - <<'PY'
from pathlib import Path
import base64, secrets, os
path=Path('.env')
if not path.exists():
    fd=os.open(path, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as f:
        f.write('POSTGRES_PASSWORD='+secrets.token_hex(24)+'\n')
        f.write('APP_MASTER_KEY='+base64.b64encode(secrets.token_bytes(32)).decode()+'\n')
        f.write('DATABASE_URL=jdbc:postgresql://localhost:5432/pollydrop\n')
        f.write('DATABASE_USER=pollydrop\nAPP_STORAGE_DIR=./data/files\n')
    print('Created local .env with generated secrets; it is excluded from Git.')
else:
    print('Keeping existing .env.')
PY
