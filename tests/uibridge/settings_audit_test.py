#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Settings audit: no dead entries, no cvar bound twice, and a search index that
covers every section and sub-tab.

Static: reads the QuakeC settings builders (and the native fe JSON), so it needs
no engine (scripts/test-uibridge.sh runs it with the schema checks). "No dead
entries" means every action a settings row emits reaches a handler, every section
has a builder, and every row has a real cvar/vm bind or an action.
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SETTINGS = os.path.join(ROOT, "engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_settings.qc")
SCREENS = os.path.join(ROOT, "engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_screens.qc")
HOST = os.path.join(ROOT, "android/app/src/main/kotlin/io/github/dixonsolutions/godnotic/fe/FrontendHost.kt")
FE = os.path.join(ROOT, "android/app/src/main/assets/fe")

fails = 0


def check(cond, what):
    global fails
    print(("ok   " if cond else "FAIL ") + what)
    if not cond:
        fails += 1


def read(path):
    with open(path) as f:
        return f.read()


settings = read(SETTINGS)
host = read(HOST)

# 1. one row per setting: a cvar or vm key is bound once, so no screen edits it twice
binds = re.findall(r'"cvar:([A-Za-z0-9_]+)"', settings)
dup = sorted({b for b in binds if binds.count(b) > 1})
check(not dup, "cvar binds are unique" + (f": {dup}" if dup else ""))
binds = re.findall(r'(?:uib_s\("bind", |UIB_(?:Switch|Choice|Slider|Stepper|Field)\("[^"]*",\s*"[^"]*",\s*)"vm:([A-Za-z0-9_.]+)"', settings)
dup = sorted({b for b in binds if binds.count(b) > 1})
check(not dup, "vm binds are unique" + (f": {dup}" if dup else ""))

check("_touch_asset_fetch_background" not in settings,
      "the transient asset-fetch switch is not a settings row")
check(settings.count('"vm:touch.preset"') == 1,
      "touch_preset is one choice, bound to vm:touch.preset")
check("case \"vm:touch.preset\":" in settings, "and the preset has a handler that applies a profile")

# 2. every section in the tab list has a content builder
sec_key = settings[settings.index("string UIB_SecKey"):settings.index("string UIB_SecLabel")]
keys = re.findall(r'case \d+: return "([a-z]+)";', sec_key)
check(len(keys) == 13, f"thirteen settings sections ({len(keys)})")
accessibility = settings[settings.index("void UIB_SecAccessibility()"):settings.rindex("void UIB_SecContent(string k)\n{")]
check('UIB_Section("desktop", _("Desktop"), true)' in accessibility and
      'UIB_Switch("assist", _("Enable auto shoot and aim assist"), "cvar:cl_accessibility_desktop"' in accessibility,
      "Accessibility exposes the desktop opt-in through the shared settings tree")
content_start = settings.rindex("void UIB_SecContent(string k)\n{")
content = settings[content_start:]
for k in keys:
    check(f'case "{k}": UIB_Sec' in content, f"section {k} has a builder")

# 3. every action a settings row emits is handled
fn_start = settings.index("bool UIB_SettingsAction(string node, string a, string payload)\n{")
fn = settings[fn_start:]
nxt = re.search(r"\n(bool|void|string|float|int|vector) UIB_", fn[10:])
if nxt:
    fn = fn[:10 + nxt.start()]
prefixes = re.findall(r'substring\(a, 0, \d+\) == "([^"]+)"', fn)
exact = set(re.findall(r'case "([^"]+)"', fn)) | set(re.findall(r'if \(a == "([^"]+)"\)', fn))
unhandled = sorted(a for a in set(re.findall(r'UIB_A(?:S)?\("([a-z0-9._]+)"', settings))
                   if a not in exact and not any(a.startswith(p) for p in prefixes))
check(not unhandled, f"every settings action has a handler" + (f": {unhandled}" if unhandled else ""))

# 4. search: the field is first, the index walks the real builders, and a result jumps to its row
root = settings[settings.index("void UIB_BuildSettingsRoot()"):settings.index("string UIB_SecTabs")]
check(root.index('UIB_Open("search", "field")') < root.index('UIB_Open("split", "split")'),
      "the search field is above the sections")
check('UIB_SearchResults(q)' in root and "UIB_SearchIndex()" in settings,
      "a query builds results from the index")
check("UIB_SecContent(sec)" in settings[settings.index("void UIB_SearchBuild"):settings.index("void UIB_SearchIndex()\n{")],
      "the index is built from the same builders the screens use")
check('sec == "updates" || sec == "data"' in settings,
      "the index leaves out Updates (a network check) and Data")
check('substring(a, 0, 16) == "settings.result."' in fn,
      "a search result has an action")
check('settings.reveal' in settings and 'settings.reveal.at' in settings,
      "a result reveals its row")
check('uib_sr_oldtab' in settings, "building the index puts the open sub-tab back")

# 5. the native fe screens only name actions the app handles
def actions(node):
    p = node.get("props", {})
    out = []
    for key in ("action", "primary", "rowPrimary", "submit"):
        a = p.get(key)
        if isinstance(a, dict) and a.get("id"):
            out.append(a["id"])
    for a in p.get("actions") or []:
        if isinstance(a, dict) and a.get("id"):
            out.append(a["id"])
    return out

for name in ("home", "pause"):
    with open(os.path.join(FE, name + ".json")) as f:
        snap = json.load(f)["snapshot"]
    for node in snap["nodes"]:
        for a in actions(node):
            if a in ("team", "spectate"):
                continue  # hidden in Home/Pause; the engine opens them
            check(f'"{a}"' in host, f"fe/{name}.json action {a} has a handler")

print(("settings audit: %d failure(s)" % fails) if fails else "settings audit: all passed")
sys.exit(1 if fails else 0)
