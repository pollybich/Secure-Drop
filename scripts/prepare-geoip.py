#!/usr/bin/env python3
import hashlib, json, os, tempfile
from pathlib import Path
from urllib.request import urlopen
from urllib.parse import urlsplit
import subprocess, re
root = Path(__file__).resolve().parent.parent
manifest = json.loads((root/'config/geoip-source.json').read_text())
target = root/'backend/src/main/resources/geoip-country.mmdb'
if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == manifest['sha256']:
    print('Country database already verified.'); raise SystemExit(0)
urls = [manifest['url']]
try:
    remote = subprocess.check_output(['git','remote','get-url','origin'],cwd=root,text=True).strip()
    repo = urlsplit(remote).path.strip('/').removesuffix('.git') if '://' in remote else remote.split(':',1)[1].removesuffix('.git')
    if re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',repo):
        urls.append('https://github.com/'+repo+'/releases/download/v0.1.0-beta.3/pollydrop-country.mmdb')
except Exception: pass
data = None
for url in urls:
    try:
        with urlopen(url, timeout=60) as response: candidate = response.read(16 * 1024 * 1024 + 1)
        if len(candidate) <= 16 * 1024 * 1024 and hashlib.sha256(candidate).hexdigest() == manifest['sha256']:
            data = candidate; break
    except Exception: pass
if data is None:
    raise SystemExit('Could not retrieve the pinned country database. TLS and SHA-256 verification remain enabled.')
with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
    temporary = Path(output.name); output.write(data)
os.chmod(temporary, 0o644); temporary.replace(target)
print('Country database downloaded and SHA-256 verified.')
