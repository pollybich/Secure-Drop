#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [ ! -f .env ]; then echo 'Run ./scripts/bootstrap-dev.sh first.' >&2; exit 1; fi
set -a
source .env
set +a
# Maven can inherit a different JAVA_HOME than the java executable on PATH.
if [ -x "$ROOT/.tools/jdk21/bin/javac" ]; then export JAVA_HOME="$ROOT/.tools/jdk21"; elif [ -z "${JAVA_HOME:-}" ]; then export JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.home =/{print $2; exit}')"; fi
if [ ! -x "$JAVA_HOME/bin/javac" ]; then echo "Java 21 JDK is required (the installed Java runtime has no compiler). Set JAVA_HOME to a full JDK." >&2; exit 1; fi
if command -v mvn >/dev/null 2>&1; then MAVEN="$(command -v mvn)"; else MAVEN="$ROOT/.tools/apache-maven-3.9.11/bin/mvn"; fi
MAVEN_ARGS=(-Dmaven.repo.local="$ROOT/.tools/m2" -f backend/pom.xml)
if [ -n "${HTTPS_PROXY:-}" ]; then
  python3 - <<'PY'
import os, urllib.parse, xml.etree.ElementTree as E
from pathlib import Path
p=urllib.parse.urlsplit(os.environ['HTTPS_PROXY'])
if p.username or p.password: raise SystemExit('Authenticated proxies need explicit Maven configuration; credentials are not copied automatically.')
root=E.Element('settings'); proxies=E.SubElement(root,'proxies');proxy=E.SubElement(proxies,'proxy')
for name,value in [('id','environment'),('active','true'),('protocol','http'),('host',p.hostname),('port',str(p.port or 80)),('nonProxyHosts','localhost|127.*|[::1]')]:E.SubElement(proxy,name).text=value
Path('.tools/maven-settings.xml').write_text(E.tostring(root,encoding='unicode'))
PY
  MAVEN_ARGS+=(-s "$ROOT/.tools/maven-settings.xml")
fi
if [ "$#" -gt 0 ]; then exec "$MAVEN" "${MAVEN_ARGS[@]}" "$@"; fi
exec "$MAVEN" "${MAVEN_ARGS[@]}" spring-boot:run
