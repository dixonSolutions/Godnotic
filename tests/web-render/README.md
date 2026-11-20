# Web rendering suite

Checks that the web build shows **our** UI — the QuakeC-drawn UIB — at tablet
and phone sizes, that its controls do not overlap, and that the page logs no
console errors. One command, the real engine in headless Chromium:

```bash
# once: the page and its data (docs/WEB.md)
scripts/build-web.sh
scripts/stage-web-data.sh --zip Godnotic-data-<date>.zip --astc Godnotic-data.zip

# every size
env -u WAYLAND_DISPLAY -u DISPLAY -u XAUTHORITY tests/web-render/run.sh
# another build, a port, a browser
env -u WAYLAND_DISPLAY -u DISPLAY -u XAUTHORITY tests/web-render/run.sh build/web
PORT=8099 CHROME=/path/to/chrome tests/web-render/run.sh
```

Sizes: 1920x1200 and 1280x800 (landscape tablets, touch) and 844x390 (a phone
in landscape, touch). Each size starts in a fresh browser profile, downloads
the data from a local `web/serve.py`, answers the welcome, then:

- **Home** must be our Home (the UIB snapshot's stack `["home"]` with its
  cards), not a classic Xonotic menu;
- **every main screen** (Singleplayer, Multiplayer, Settings) is opened with a
  real tap on its card or tab, and each must be our screen, have no two
  sibling controls overlapping, and keep every control inside the window;
- **a local match** (`bot_number 2; map stormkeep`) is started and its menu
  opened: the UIB stack must be `["pause"]`, `_uib_classic_up`
  must be 0 (the old Xonotic "Game menu" is not drawing), the pause screen must be
  laid out cleanly, and Resume must close it;
- **real touch input** must open the controls; the suite does not substitute scripted taps for these checks.
- **no console errors** may be logged by the page.

Screenshots and `run.log` go to `build/web-render/`; each run also prints a
JSON summary (`screens`, `overlaps`, `errors`). The checks compare the rects
the UIB renderer reports for each node (`_uib_r_find` / `_uib_r_found`), so
they see where things are drawn, not where the description says they are.

`render.mjs` drives Chromium over CDP and can be pointed at a browser someone
else started: `node tests/web-render/render.mjs --port N --url U --out DIR
--width W --height H [--touch] [--label NAME]`.

Needs: `build/web` with data staged, node 22, python3, Chromium (Playwright's,
or `CHROME=`). It refuses to run with a desktop session in the environment.
