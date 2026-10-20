#!/usr/bin/env python3
"""Explicit, verified download. The default prints a plan and makes no network calls."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--download', action='store_true', help='fetch the 614,437,424-byte INT8 model')
parser.add_argument('--destination', type=Path, default=Path('/tmp/lacan-parakeet-probe/parakeet.tflite'))
args = parser.parse_args()
lock = json.loads(Path(__file__).with_name('artifacts.lock.json').read_text())
name = 'parakeet_tdt_0.6b_v3_5s_i8.tflite'
artifact = lock['artifacts'][name]
url = f"https://huggingface.co/{lock['model_repository']}/resolve/{lock['model_revision']}/{name}"
print(json.dumps({'url': url, 'destination': str(args.destination), **artifact}, indent=2))
if not args.download:
    raise SystemExit(0)
args.destination.parent.mkdir(parents=True, exist_ok=True)
if args.destination.exists():
    raise SystemExit('Destination already exists; verify or choose a different destination.')
partial = args.destination.with_suffix('.tflite.partial')
hash_value = hashlib.sha256()
try:
    with urllib.request.urlopen(url, timeout=60) as response, partial.open('wb') as output:
        size = 0
        while chunk := response.read(1024 * 1024):
            size += len(chunk)
            if size > artifact['bytes']:
                raise ValueError('response exceeds pinned byte count')
            hash_value.update(chunk)
            output.write(chunk)
    if size != artifact['bytes'] or hash_value.hexdigest() != artifact['sha256']:
        raise ValueError('size/SHA-256 verification failed')
    partial.replace(args.destination)
except BaseException:
    partial.unlink(missing_ok=True)
    raise
print('Verified model installed locally; no device was contacted.')
