#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if [ "$(id -u)" -ne 0 ]; then echo 'Run this script as root on the server.' >&2; exit 1; fi
if ! command -v docker >/dev/null 2>&1; then echo 'Docker Engine must be installed first.' >&2; exit 1; fi
apt-get update
apt-get install -y ca-certificates curl python3
systemctl enable --now docker
docker info >/dev/null
COMPOSE=(docker compose -p pollydrop-public -f compose.yaml -f deploy/server/compose.server.yaml)
if ! docker compose version >/dev/null 2>&1; then
  VERSION='v5.6.0'
  case "$(uname -m)" in x86_64) ARCH='x86_64';; aarch64|arm64) ARCH='aarch64';; *) echo 'Unsupported CPU architecture.' >&2; exit 1;; esac
  ASSET="docker-compose-linux-$ARCH"
  STAGING="$(mktemp -d)"
  trap 'rm -rf -- "$STAGING"' EXIT
  curl --fail --silent --show-error --location --retry 3 "https://github.com/docker/compose/releases/download/$VERSION/$ASSET" --output "$STAGING/$ASSET"
  curl --fail --silent --show-error --location --retry 3 "https://github.com/docker/compose/releases/download/$VERSION/$ASSET.sha256" --output "$STAGING/checksum"
  EXPECTED="$(awk '{print $1}' "$STAGING/checksum")"
  ACTUAL="$(sha256sum "$STAGING/$ASSET" | awk '{print $1}')"
  if [ "$EXPECTED" != "$ACTUAL" ]; then echo 'Docker Compose checksum verification failed.' >&2; exit 1; fi
  install -d -m 0755 /usr/local/lib/docker/cli-plugins
  install -m 0755 "$STAGING/$ASSET" /usr/local/lib/docker/cli-plugins/docker-compose
  rm -rf -- "$STAGING"
  trap - EXIT
fi
docker compose version
# Stop before starting if another service owns a required port.
if [ -z "$(docker ps -q --filter label=com.docker.compose.project=pollydrop-public)" ]; then
  BUSY="$(ss -H -ltn '( sport = :80 or sport = :443 or sport = :8088 )')"
  if [ -n "$BUSY" ]; then echo 'Ports 80, 443 or 8088 are now occupied. Existing services were preserved.' >&2; exit 1; fi
fi
./deploy/server/prepare-release.sh
./scripts/init-env.sh
"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" up -d --build
for attempt in $(seq 1 90); do
  if curl --fail --silent http://127.0.0.1:8088/api/health >/dev/null; then
    echo 'pollyDrop backend is ready. HTTPS certificate issuance starts automatically.'
    echo 'Open https://drop.pollybich.ru'
    exit 0
  fi
  sleep 1
done
echo 'Backend did not become ready. Inspect: docker compose -p pollydrop-public -f compose.yaml -f deploy/server/compose.server.yaml logs --tail=50 backend caddy' >&2
exit 1
