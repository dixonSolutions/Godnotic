#!/bin/bash
# Stamp Flatpak metainfo (and optional files) with a CI package version.
set -euo pipefail

ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
VERSION="${1:?usage: set-package-version.sh <version>}"
DATE="${PACKAGE_DATE:-$(date -u +%Y-%m-%d)}"
METAINFO="$ROOT/flatpak/io.github.dixonSolutions.Godnotic.metainfo.xml"

test -f "$METAINFO"

python3 - "$METAINFO" "$VERSION" "$DATE" <<'PY'
import re
import sys
from pathlib import Path

path, version, date = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
text = path.read_text(encoding="utf-8")
if f'<release version="{version}"' in text:
    print(f"metainfo already has release {version}")
    raise SystemExit(0)
# Keep "Automated CI build <version>.": scripts/stage-flatpak.sh, packaging/start.sh
# and the loader (flatpak_update.rs, version_from_metainfo) read the version from it.
entry = f'''    <release version="{version}" date="{date}" type="stable">
      <description>
        <p>Automated CI build {version}.</p>
      </description>
      <url type="details">https://github.com/dixonSolutions/Godnotic/releases/tag/v{version}</url>
    </release>
'''
if re.search(r"<releases>\s*</releases>", text):
    text = re.sub(
        r"<releases>\s*</releases>",
        f"<releases>\n{entry}  </releases>",
        text,
        count=1,
    )
elif "<releases>\n" in text:
    text = text.replace("<releases>\n", f"<releases>\n{entry}", 1)
elif "<releases>" in text:
    text = text.replace("<releases>", f"<releases>\n{entry}", 1)
else:
    raise SystemExit("metainfo.xml missing <releases> block")
path.write_text(text, encoding="utf-8")
print(f"Stamped metainfo version {version} ({date})")
PY
