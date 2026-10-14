#!/usr/bin/env python3
"""Verify the served JS bytes against a built image or a checked package."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser()
expected = parser.add_mutually_exclusive_group(required=True)
expected.add_argument('--image')
expected.add_argument('--manifest')
parser.add_argument('--source')
parser.add_argument('--url', default='http://127.0.0.1:8088')
args = parser.parse_args()
pattern = r'<script[^>]*src="(/assets/index-[A-Za-z0-9_-]+\.js)"'
if args.manifest:
    manifest = json.loads(Path(args.manifest).read_text())
    path, digest = manifest['scriptPath'], manifest['scriptSha256']
else:
    def image_file(path):
        return subprocess.check_output(['docker', 'run', '--rm', '--entrypoint', 'cat', args.image, '/usr/share/nginx/html' + path])
    path = re.search(pattern, image_file('/index.html').decode()).group(1)
    built_js = image_file(path)
    digest = hashlib.sha256(built_js).hexdigest()
    if args.source:
        ttl = re.search(r'const\s+\[ttl,\s*setTtl\]\s*=\s*useState\((\d+)\)', Path(args.source).read_text()).group(1)
        if not re.search(rb'useState\(' + ttl.encode() + rb'\)', built_js):
            sys.exit('Built frontend does not contain the current source default. Update failed.')
if not re.fullmatch(r'/assets/index-[A-Za-z0-9_-]+\.js', path):
    sys.exit('Unexpected frontend script path.')
last = 'not reachable'
for _ in range(20):
    try:
        def fetch(path):
            request = Request(args.url.rstrip('/') + path + '?verify=' + str(time.time_ns()), headers={'Cache-Control': 'no-cache'})
            with urlopen(request, timeout=3) as response:
                return response.read()
        current = re.search(pattern, fetch('/').decode())
        if not current or current.group(1) != path:
            last = 'served HTML still points to a different build'
        elif hashlib.sha256(fetch(path)).hexdigest() != digest:
            last = 'served JavaScript differs from the built frontend'
        else:
            print('Served frontend verified: ' + path)
            sys.exit(0)
    except Exception as error:
        last = type(error).__name__
    time.sleep(.5)
sys.exit('Frontend verification failed: ' + last)
