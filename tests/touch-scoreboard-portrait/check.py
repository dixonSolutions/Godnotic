#!/usr/bin/env python3
"""Portrait scoreboard: low-value columns dropped and the header on two lines; landscape untouched."""
import re, sys
src = open(sys.argv[1] + "/engine/data/xonotic-data.pk3dir/qcsrc/client/hud/panel/scoreboard.qc").read()
assert "return vid_conwidth < vid_conheight;" in src, "narrow means portrait"
drops = re.search(r"bool Scoreboard_NarrowDrops\(string field\)\s*\{(.*?)\n\}", src, re.S).group(1)
names = set(re.findall(r'case "(\w+)":', drops))
for need in ("pl", "suicides", "dmgtaken", "dmg"):
    assert need in names, "portrait must drop " + need
for keep in ("name", "ping", "kills", "deaths", "score"):
    assert keep not in names, "portrait must keep " + keep
# the drop is applied only when narrow, and a turn of the device re-reads the columns
assert "if (Scoreboard_NarrowLayout() && Scoreboard_NarrowDrops(str))\n\t\t\tcontinue;" in src
assert "Scoreboard_NarrowLayout() != scoreboard_narrow_seen" in src
# header: the map line drops below the limits line only when narrow
assert re.search(r"if \(Scoreboard_NarrowLayout\(\)\)\s*pos\.y \+= sb_gameinfo_detail_fontsize\.y;\s*if \(campaign\)", src)
print("ok   portrait scoreboard drops", sorted(names), "and wraps the header")
