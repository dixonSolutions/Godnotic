#!/usr/bin/env python3
"""Build one publish-ready split release from a repack-phone-assets output directory."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--profile', choices=['astc', 'dxt'], required=True)
p.add_argument('--source-date', required=True, help='eight-digit date of the input data release')
p.add_argument('--revision', type=int, required=True, help='unused immutable release revision')
p.add_argument('--usage', type=pathlib.Path, required=True)
p.add_argument('--default-map', default='stormkeep')
p.add_argument('--output', type=pathlib.Path, required=True, help='new empty output directory')
p.add_argument('phone', type=pathlib.Path)
a = p.parse_args()
if not re.fullmatch(r'\d{8}', a.source_date) or a.revision < 1:
    p.error('source-date must be YYYYMMDD and revision must be positive')
if a.output.exists() and any(a.output.iterdir()):
    p.error('output must be empty')
phone_manifest = json.loads((a.phone / 'phone-manifest.json').read_text())
if not phone_manifest.get('packs') or any(r.get('profile') != a.profile for r in phone_manifest['packs']):
    sys.exit('phone-manifest.json must declare the requested profile for every pack')
a.output.mkdir(parents=True, exist_ok=True)
tag = f'data-{a.profile}-{a.source_date}-r{a.revision}'
archive = f'Godnotic-data-{a.profile}.zip'
subprocess.run([sys.executable, str(ROOT / 'split-data-packs.py'), '--usage', str(a.usage),
                '--default-map', a.default_map, '--archive', '--archive-name', archive,
                '--meta', 'profile=' + a.profile, '--meta', 'release=' + tag,
                '-o', str(a.output), str(a.phone)], check=True)
subprocess.run(['bash', str(ROOT / 'test-data-split.sh'), str(a.output), str(a.phone)], check=True)
path = a.output / 'godnotic-data.json'
m = json.loads(path.read_text())
if not all(a.source_date in x['install_as'] for x in m['packs']):
    sys.exit('source date does not match the pack names')
m['archive']['url'] = f'https://github.com/dixonSolutions/Godnotic-data/releases/download/{tag}/{archive}'
path.unlink()
manifest_file = f'godnotic-data-{a.profile}.json'
(a.output / manifest_file).write_text(json.dumps(m, indent=1) + '\n')
manifest_sha = hashlib.sha256((a.output / manifest_file).read_bytes()).hexdigest()
(a.output / 'UPLOADS.txt').write_text(
    f"{tag}/{archive} {m['archive']['size']} {m['archive']['sha256']}\n"
    f"{tag}/{manifest_file} {(a.output / manifest_file).stat().st_size} {manifest_sha}\n"
    f"data-slim/{manifest_file} {(a.output / manifest_file).stat().st_size} {manifest_sha}\n")
print(f'Upload {archive} to {tag}, then godnotic-data-{a.profile}.json to {tag} and data-slim')
