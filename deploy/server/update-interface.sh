#!/usr/bin/env bash
# Apply the one-hour interface fix without Node.js compilation or a new release.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if [ "$#" -ne 0 ]; then echo 'This updater takes no arguments.' >&2; exit 1; fi
if [ ! -f .env ]; then echo 'Existing installation .env is required.' >&2; exit 1; fi
COMPOSE=(docker compose -p "${POLLYDROP_COMPOSE_PROJECT:-pollydrop-public}" -f compose.yaml -f deploy/server/compose.server.yaml)
STAGING="$(mktemp -d)"
trap 'rm -rf -- "$STAGING"' EXIT
python3 - "$STAGING" <<'PY'
import hashlib, json, sys, tarfile
from pathlib import Path, PurePosixPath
patch = Path('deploy/server/patches')
manifest = json.loads((patch/'interface-one-hour.json').read_text())
archive = patch/'interface-one-hour.tar.gz'
if hashlib.sha256(archive.read_bytes()).hexdigest() != manifest['archiveSha256']:
    raise SystemExit('Interface package checksum mismatch.')
root = Path(sys.argv[1])/'dist'
root.mkdir()
with tarfile.open(archive) as bundle:
    for member in bundle:
        path = PurePosixPath(member.name)
        if path.is_absolute() or '..' in path.parts or not member.isfile():
            raise SystemExit('Unexpected interface archive entry.')
        target = root/member.name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(bundle.extractfile(member).read())
js = (root/manifest['scriptPath'].lstrip('/')).read_bytes()
if hashlib.sha256(js).hexdigest() != manifest['scriptSha256'] or b'useState(3600)' not in js:
    raise SystemExit('Interface package does not match the one-hour fix.')
print('Interface package verified: default TTL 3600 seconds.')
PY
mkdir -p .runtime/server/frontend
cp deploy/server/runtime/frontend/Dockerfile .runtime/server/frontend/Dockerfile
cp deploy/server/nginx.frontend.conf .runtime/server/frontend/nginx.conf
chmod -R a+rX "$STAGING/dist"
rm -rf -- .runtime/server/frontend/dist
mv "$STAGING/dist" .runtime/server/frontend/dist
"${COMPOSE[@]}" config --quiet
# Only Nginx with already-built static files: no backend/database build or restart.
"${COMPOSE[@]}" build frontend
"${COMPOSE[@]}" up -d --no-deps --no-build --force-recreate frontend
python3 deploy/server/verify-interface.py --manifest deploy/server/patches/interface-one-hour.json --url "${POLLYDROP_FRONTEND_URL:-http://127.0.0.1:8088}"
echo 'One-hour interface is now served. Database, backend and transfer files were preserved.'
