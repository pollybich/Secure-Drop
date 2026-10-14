#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export npm_config_cache="$ROOT/.tools/npm-cache"
./scripts/init-env.sh
mkdir -p .tools
if ! command -v mvn >/dev/null 2>&1 && [ ! -x .tools/apache-maven-3.9.11/bin/mvn ]; then
  python3 - <<'PY'
from pathlib import Path
import urllib.request, hashlib, tarfile
root=Path('.tools'); version='3.9.11'
url=f'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/{version}/apache-maven-{version}-bin.tar.gz'
data=urllib.request.urlopen(url,timeout=60).read()
checksum=urllib.request.urlopen(url+'.sha512',timeout=30).read().decode().split()[0]
if hashlib.sha512(data).hexdigest()!=checksum: raise SystemExit('Maven checksum verification failed')
archive=root/'maven.tar.gz'; archive.write_bytes(data)
with tarfile.open(archive) as t: t.extractall(root,filter='data')
archive.unlink()
PY
fi
# A runtime-only Java installation cannot compile the backend.
if [ ! -x "${JAVA_HOME:-/usr/lib/jvm/default-java}/bin/javac" ] && ! command -v javac >/dev/null 2>&1 && [ ! -x .tools/jdk21/bin/javac ]; then
  JDK_CONTAINER="$(docker create maven:3.9.11-eclipse-temurin-21)"
  trap 'docker rm "$JDK_CONTAINER" >/dev/null' EXIT
  docker cp "$JDK_CONTAINER:/opt/java/openjdk" .tools/jdk21
  docker rm "$JDK_CONTAINER" >/dev/null
  trap - EXIT
  # Keep the platform's existing trusted CA roots; TLS verification stays enabled.
  if [ -f /etc/ssl/certs/java/cacerts ]; then cp /etc/ssl/certs/java/cacerts .tools/jdk21/lib/security/cacerts; fi
fi
python3 scripts/prepare-geoip.py
npm --prefix frontend ci
docker compose -p pollydrop-dev -f compose.yaml -f compose.dev.yaml up -d db
