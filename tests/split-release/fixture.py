#!/usr/bin/env python3
"""Generate small, deliberately different ASTC and DXT release inputs."""
import json
import pathlib
import subprocess
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parents[2]
out = pathlib.Path(sys.argv[1])
for profile in ('astc', 'dxt'):
    phone = out / ('phone-' + profile)
    phone.mkdir(parents=True)
    records = []
    for role, entries in {
        'data': {'models/a.md3': profile * 100, 'gfx/menu.jpg': 'menu'},
        'maps': {'maps/stormkeep.bsp': profile * 200, 'maps/stormkeep.mapinfo': 'dm',
                 'maps/other.bsp': 'other', 'maps/other.mapinfo': 'dm'},
        'music': {'sound/cdtracks/a.ogg': 'music'},
    }.items():
        name = f'xonotic-20261107-{role}-phone.pk3'
        with zipfile.ZipFile(phone / name, 'w') as z:
            for path, data in entries.items():
                z.writestr(path, data)
        records.append({'file': name, 'install_as': name.replace('-phone', ''), 'profile': profile})
    (phone / 'phone-manifest.json').write_text(json.dumps({'packs': records}))
    usage = out / ('usage-' + profile + '.tsv')
    usage.write_text('xonotic-20261107-data.pk3\tmodels/a.md3\tstormkeep,other\n')
    subprocess.run([sys.executable, str(root / 'scripts/build-split-release.py'),
                    '--profile', profile, '--source-date', '20261107', '--revision', '1',
                    '--usage', str(usage), '--output', str(out / profile), str(phone)], check=True)
