#!/usr/bin/env python3
"""Guard the bridge description and the Android join button measurements."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
qc = (root / 'engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_join.qc').read_text()
kt = (root / 'android/app/src/main/kotlin/io/github/dixonsolutions/godnotic/fe/ViewRenderer.kt').read_text()
build = qc.split('void UIB_BuildJoin()', 1)[1].split('bool UIB_JoinUp()', 1)[0]
button = kt.split('private fun button(', 1)[1].split('private fun dialogFrame', 1)[0]
grid = kt.split('class FlowGrid(', 1)[1]
assert 'uib_j("primary"' not in build
assert build.index('UIB_Open("teams"') < build.index('UIB_Button("spectate"') < build.index('UIB_Button("auto"')
assert 'uib_f("maxColumns", 2)' in build
assert 'if (id == "join/teams") 1 else' in kt
assert 'g.maxColumns = props.num("maxColumns", 0.0).toInt()' in kt
assert 'id.startsWith("join/")' in button
h = int(re.search(r'joinActionHeight = .*?dp\((\d+)f\)', button).group(1))
assert 'MeasureSpec.makeMeasureSpec(joinActionHeight, MeasureSpec.EXACTLY)' in button
assert 'FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)' in button
assert 'width - col * (cw + gap)' in grid
assert 'c.layout(left, y, left + c.measuredWidth, y + rowH)' in grid
# Mirror FlowGrid's two columns, including an odd width and RTL. The other
# two body buttons fill the same width and use the same exact measured height.
for width in (319, 320, 601, 760):
    gap = 16
    cw = (width - gap) // 2
    for rtl in (False, True):
        widths = (cw, width - (cw + gap))
        teams = []
        for col, cell in enumerate(widths):
            x = col * (cw + gap)
            left = width - x - cell if rtl else x
            teams.append((left, 0, left + cell, h))
        full = (0, h + gap, width, 2*h + gap)
        auto = (0, 2*(h + gap), width, 3*h + 2*gap)
        assert min(t[0] for t in teams) == full[0] == auto[0] == 0
        assert max(t[2] for t in teams) == full[2] == auto[2] == width
        assert all(r[3] - r[1] == h for r in (*teams, full, auto))
        # MATCH_PARENT drawable bounds equal each button's measured bounds.
        assert all(r[2] > r[0] for r in (*teams, full, auto))
print('join layout contract OK')
