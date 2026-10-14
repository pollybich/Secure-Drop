#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if [ ! -f .env ]; then echo 'Existing installation .env is required.' >&2; exit 1; fi
COMPOSE=(docker compose -p pollydrop-public -f compose.yaml -f deploy/server/compose.server.yaml)
if [ -z "$("${COMPOSE[@]}" ps -q backend)" ]; then echo 'Use update-debian.sh to update or migrate the complete installation first.' >&2; exit 1; fi
./deploy/server/prepare-release.sh
"${COMPOSE[@]}" build frontend
"${COMPOSE[@]}" up -d --no-deps --no-build --force-recreate frontend
python3 deploy/server/verify-interface.py --image pollydrop-public-frontend
echo 'pollyDrop interface is now served. Backend, database and transfer files were preserved.'
