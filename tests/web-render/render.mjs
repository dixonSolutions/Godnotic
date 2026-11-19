// Web rendering checks in a real headless Chromium (CDP): our UI must be the
// one on screen, laid out inside the window, with no two buttons on top of
// each other, and no console errors. Run through tests/web-render/run.sh.
//
//   node tests/web-render/render.mjs --port P --url URL --out DIR
//        --width 1920 --height 1200 [--touch] [--label tablet]
//
// Prints one "ok"/"FAIL" line per check and a JSON summary; exit 1 on any
// failure. Screenshots go to DIR/<label>-*.png.
import fs from 'node:fs';

const args = process.argv.slice(2);
const opt = (n, d) => { const i = args.indexOf('--' + n); return i < 0 ? d : args[i + 1]; };
const port = Number(opt('port'));
const url = opt('url');
const out = opt('out', '.');
const W = Number(opt('width', '1920'));
const H = Number(opt('height', '1200'));
const touch = args.includes('--touch');
const label = opt('label', `${W}x${H}`);
fs.mkdirSync(out, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

console.error(`connecting to Chromium on ${port}`);
let ws;
for (let i = 0; i < 150 && !ws; i++) {
  try {
    const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    if (page) ws = new WebSocket(page.webSocketDebuggerUrl);
  } catch (e) { /* not up yet */ }
  await sleep(100);
}
if (!ws) { console.log('FAIL no Chromium on port ' + port); process.exit(1); }
if (ws.readyState !== 1) {
  await new Promise((r, j) => {
    const t = setTimeout(() => j(new Error('devtools socket did not open')), 20000);
    ws.onopen = () => { clearTimeout(t); r(); };
    ws.onerror = (e) => { clearTimeout(t); j(new Error('devtools socket error')); };
  }).catch((e) => { console.log('FAIL ' + e.message); process.exit(1); });
}
console.error('devtools connected');
let id = 0;
const pending = new Map();
const consoleErrors = [];
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); return; }
  if (m.method === 'Runtime.exceptionThrown') {
    const d = m.params.exceptionDetails || {};
    consoleErrors.push(((d.exception && (d.exception.description || d.exception.value)) || d.text || 'exception').slice(0, 300));
  } else if (m.method === 'Runtime.consoleAPICalled' && (m.params.type === 'error' || m.params.type === 'assert')) {
    consoleErrors.push(m.params.args.map((a) => a.value === undefined ? (a.description || '') : String(a.value)).join(' ').slice(0, 300));
  }
};
const send = (method, params = {}, ms = 60000) => new Promise((resolve) => {
  const i = ++id;
  const t = setTimeout(() => { pending.delete(i); resolve({ timeout: true }); }, ms);
  pending.set(i, (m) => { clearTimeout(t); if (m.error) console.error(`cdp ${method}: ${JSON.stringify(m.error)}`); resolve(m); });
  ws.send(JSON.stringify({ id: i, method, params }));
});
const evaluate = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true, userGesture: true });
  if (r.result && r.result.exceptionDetails) return 'FAIL ' + (r.result.exceptionDetails.text || 'exception');
  return r.result && r.result.result ? r.result.result.value : undefined;
};
const logLines = async () => (await evaluate('window.xt && xt.log ? xt.log() : []')) || [];
const command = (line) => evaluate(`xt.command(${JSON.stringify(line)})`);
let markN = 0;
const run = async (line) => { const m = `XTMARK${++markN}`; await command(`echo ${m}`); await command(line); return m; };
const after = (lines, m) => { for (let i = lines.length - 1; i >= 0; i--) if (lines[i].includes(m)) return lines.slice(i + 1); return []; };
const ask = async (line, match, tries = 30) => {
  const m = await run(line);
  for (let i = 0; i < tries; i++) {
    await sleep(150);
    const l = after(await logLines(), m).find(match);
    if (l) return l;
  }
  return '';
};
const shot = async (name) => {
  const r = await send('Page.captureScreenshot', { format: 'png' });
  if (r.result && r.result.data) fs.writeFileSync(`${out}/${label}-${name}.png`, Buffer.from(r.result.data, 'base64'));
};
const snapshot = async () => {
  for (let k = 0; k < 3; k++) {
    const l = await ask('uib_dump', (x) => x.includes('"t":"snapshot"'), 40);
    try { const j = JSON.parse(l.slice(l.indexOf('{'))); return Object.fromEntries(j.nodes.map((n) => [n.id, n])); } catch (e) { /* cut line */ }
  }
  return {};
};
const stackOf = (nodes) => (nodes.app && nodes.app.props && nodes.app.props.stack) || [];
const click = async (x, y) => {
  if (touch) {
    const p = { x, y, id: 1, radiusX: 8, radiusY: 8, force: 1 };
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [p] });
    await sleep(60);
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  } else {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
    await sleep(60);
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
  }
};
const key = async (k, code, vk) => {
  await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: k, code, windowsVirtualKeyCode: vk });
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk });
};
const rectOf = async (node) => {
  // Ask for the rect and wait for a value that arrived after the ask: the
  // renderer answers a frame later, and a stale answer from the previous
  // node made every control look like it sat on the last one's box.
  await command('set _uib_r_found ""');
  await command(`set _uib_r_find "${node}"`);
  let r = null;
  for (let i = 0; i < 25 && !r; i++) {
    await sleep(120);
    const m = /FOUND ([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+)/.exec(await ask('echo FOUND $_uib_r_found', (x) => x.includes('FOUND '), 3));
    if (m) r = m.slice(1).map(Number);
  }
  await command('set _uib_r_find ""');
  return r && { x: r[0] * W, y: r[1] * H, w: r[2] * W, h: r[3] * H };
};
let fail = 0;
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fail = 1; return ok; };

// ---- overlap: two children of the same node must not sit on each other
// (cards and their own chips nest, so only siblings are compared), and every
// drawn item must fit in the window.
const BUTTONISH = /^(button|tile|card|chip|link|tab)$/;
// What is worth checking on a screen: the cards/tiles, the buttons and the
// tabs. One console round trip per rect, so a screen that grows more
// controls still costs a bounded time (MAX_RECTS).
const MAX_RECTS = 10;
const pick = (nodes) => Object.keys(nodes).filter((k) => k !== 'app' && nodes[k] && BUTTONISH.test(nodes[k].type)).slice(0, MAX_RECTS);
const layout = async (nodes, wanted) => {
  const ids = (wanted && wanted.length ? wanted : pick(nodes)).filter((k) => k !== 'app');
  const groups = new Map();
  for (const k of ids) {
    const p = k.includes('/') ? k.slice(0, k.lastIndexOf('/')) : '';
    if (!groups.has(p)) groups.set(p, []);
    groups.get(p).push(k);
  }
  const rects = {};
  let fetched = 0;
  for (const [parent, kids] of groups) {
    if (kids.length < 2 || fetched > MAX_RECTS) continue;
    for (const k of kids) {
      if (fetched > MAX_RECTS) break;
      const r = await rectOf(k);
      fetched++;
      if (r && r.w > 0 && r.h > 0) rects[k] = r;
    }
  }
  const overlaps = [];
  const outOfWindow = [];
  for (const k of Object.keys(rects)) {
    const r = rects[k];
    if (r.x < -1 || r.y < -1 || r.x + r.w > W + 1 || r.y + r.h > H + 1) outOfWindow.push(`${k} at ${Math.round(r.x)},${Math.round(r.y)} ${Math.round(r.w)}x${Math.round(r.h)}`);
  }
  for (const [parent, kids] of groups) {
    const here = kids.filter((k) => rects[k]);
    for (let i = 0; i < here.length; i++) {
      for (let j = i + 1; j < here.length; j++) {
        const a = rects[here[i]], b = rects[here[j]];
        const ix = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x));
        const iy = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
        const inter = ix * iy;
        const smaller = Math.min(a.w * a.h, b.w * b.h);
        if (smaller > 0 && inter / smaller > 0.02) overlaps.push(`${here[i]} over ${here[j]} (${Math.round(100 * inter / smaller)}%)`);
      }
    }
  }
  return { rects, overlaps, outOfWindow, fetched };
};

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setFocusEmulationEnabled', { enabled: true });
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: touch ? 2 : 1, mobile: !!touch });
if (touch) await send('Emulation.setTouchEmulationEnabled', { enabled: true });
const nav = await send('Page.navigate', { url });
console.error('navigate: ' + JSON.stringify((nav && nav.result) || nav));
const t0 = Date.now();
let menu = false, clicked = false, pageError = '';
while (!menu && Date.now() - t0 < 600000) {
  await sleep(300);
  const s = await evaluate(`(() => {
    const vis = (i) => { const e = document.getElementById(i); return !!e && !e.hidden; };
    const log = window.xt && xt.log ? xt.log() : [];
    return { download: vis('xt-download'), failed: vis('xt-error'), err: (document.getElementById('xt-error-text') || {}).textContent || '',
             menu: log.some((l) => /menu/i.test(l) && /loaded|program/i.test(l)) };
  })()`);
  if (!s || s === 'FAIL') continue;
  if (s.failed) { pageError = s.err; break; }
  if (s.download && !clicked) { clicked = true; await evaluate(`document.getElementById('xt-dl-go').click()`); }
  menu = s.menu;
}
check(menu && !pageError, `the game starts (${Math.round((Date.now() - t0) / 1000)} s after navigation)${pageError ? ': ' + pageError : ''}`);
if (!menu) { console.log(JSON.stringify({ label, W, H, touch, fail: 1, errors: consoleErrors })); process.exit(1); }
const touchDetected = await evaluate('window.xt && xt.cvar ? xt.cvar("vid_touchscreen_detected") : "?"');
const touchMode = await evaluate('window.xt && xt.cvar ? xt.cvar("vid_touchscreen") : "?"');
check(String(touchDetected) === (touch ? '1' : '0'), `the web build reports its pointer capabilities to the engine (vid_touchscreen_detected=${touchDetected}, vid_touchscreen=${touchMode})`);
await sleep(6000);
await shot('01-home');

// The welcome is a first-start thing; answer it the way a player does.
let nodes = await snapshot();
if (nodes['welcome/form/name']) {
  // A first start asks for a name. Type it into the description's own field,
  // through the renderer's scripted input: browser key events are not what
  // the page's text field receives on every build, and a welcome left up
  // swallows every tap that follows.
  const f = await rectOf('welcome/form/name');
  if (f) { await click(f.x + f.w / 2, f.y + f.h / 2); await sleep(500); }
  // The renderer's own tap focuses the field whatever the browser delivered.
  await command('uib_input tap welcome/form/name');
  await sleep(600);
  await command('uib_input text Webby');
  await sleep(600);
  await command('uib_input key ENTER');
  await sleep(2000);
  nodes = await snapshot();
  if (nodes['welcome/form/name']) {
    await command('uib_event {"ev":"value","node":"welcome/form/name","value":"Webby"}');
    await sleep(500);
    await command('uib_input tap welcome/primary');
    await sleep(2000);
    nodes = await snapshot();
  }
}
await shot('02-home-ready');
check(JSON.stringify(stackOf(nodes)) === '["home"]' && !!nodes['home/tiles/sp'] && !!nodes['home/tiles/mp'],
  `the menu that comes up is our Home (stack ${JSON.stringify(stackOf(nodes))})`);
check(!!nodes['home'] && !!nodes['home/tiles'], 'the UIB renderer draws it (a home screen with its tiles)');
let lay = await layout(nodes, ['home/tiles/sp', 'home/tiles/campaign', 'home/tiles/mp', 'home/nav/settings', 'home/nav/credits', 'home/nav/quit']);
check(lay.overlaps.length === 0, `Home: no two controls overlap${lay.overlaps.length ? ': ' + lay.overlaps.join('; ') : ''}`);
check(lay.outOfWindow.length === 0, `Home: every control is inside ${W}x${H}${lay.outOfWindow.length ? ': ' + lay.outOfWindow.join('; ') : ''}`);

// Every main screen, opened by a real tap on its card.
const screens = [];
const visit = async (name, open, afterOpen) => {
  await open();
  await sleep(1800);
  const n = await snapshot();
  await shot(name);
  const st = JSON.stringify(stackOf(n));
  const ok = check(!!afterOpen(n, st), `${name}: opened (stack ${st})`);
  const l = await layout(n);
  check(l.overlaps.length === 0, `${name}: no two controls overlap${l.overlaps.length ? ': ' + l.overlaps.join('; ') : ''}`);
  check(l.outOfWindow.length === 0, `${name}: every control is inside the window${l.outOfWindow.length ? ': ' + l.outOfWindow.join('; ') : ''}`);
  screens.push({ name, stack: st, ok, overlaps: l.overlaps, outOfWindow: l.outOfWindow });
  await command('uib_input key ESCAPE');
  await sleep(1200);
};
// A tap on the center of a control must reach the engine through the browser.
// Do not fall back to scripted input: that would hide broken pointer handling.
const tapNode = async (nid, expect, verify) => {
  const r = await rectOf(nid);
  if (!r) return null;
  const methods = [
    ['touch', async () => click(r.x + r.w / 2, r.y + r.h / 2)],
    ['mouse', async () => {
      await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: r.x + r.w / 2, y: r.y + r.h / 2 });
      await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: r.x + r.w / 2, y: r.y + r.h / 2, button: 'left', clickCount: 1 });
      await sleep(60);
      await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: r.x + r.w / 2, y: r.y + r.h / 2, button: 'left', clickCount: 1 });
    }],
  ];
  for (const [name, go] of methods) {
    if ((name === 'touch') !== touch) continue;
    await go();
    await sleep(1400);
    const n = await snapshot();
    const st = JSON.stringify(stackOf(n));
    if (verify ? await verify() : (!expect || st === expect)) return { method: name, stack: st };
  }
  return { method: null, stack: JSON.stringify(stackOf(await snapshot())) };
};
const tapSp = await tapNode('home/tiles/sp', '["home","sp"]');
check(!!tapSp && !!tapSp.method, 'Home: the Singleplayer card has an on-screen rect');
let n2 = await snapshot();
await shot('03-singleplayer');
check(JSON.stringify(stackOf(n2)) === '["home","sp"]', `a tap on Singleplayer opens it (stack ${JSON.stringify(stackOf(n2))}, via ${tapSp && tapSp.method})`);
let l2 = await layout(n2);
check(l2.overlaps.length === 0, `Singleplayer: no two controls overlap${l2.overlaps.length ? ': ' + l2.overlaps.join('; ') : ''}`);
screens.push({ name: 'singleplayer', stack: JSON.stringify(stackOf(n2)), overlaps: l2.overlaps, outOfWindow: l2.outOfWindow });
await command('uib_input key ESCAPE');
await sleep(1200);
const tapMp = await tapNode('home/tiles/mp', '["home","mp"]');
check(!!tapMp && !!tapMp.method, 'Home: the Multiplayer card has an on-screen rect');
n2 = await snapshot();
await shot('04-multiplayer');
check(JSON.stringify(stackOf(n2)) === '["home","mp"]', `a tap on Multiplayer opens it (stack ${JSON.stringify(stackOf(n2))})`);
l2 = await layout(n2);
check(l2.overlaps.length === 0, `Multiplayer: no two controls overlap${l2.overlaps.length ? ': ' + l2.overlaps.join('; ') : ''}`);
screens.push({ name: 'multiplayer', stack: JSON.stringify(stackOf(n2)), overlaps: l2.overlaps, outOfWindow: l2.outOfWindow });
await command('uib_input key ESCAPE');
await sleep(1200);
const tapSettings = await tapNode('home/nav/settings', '["home","settings"]');
if (tapSettings && tapSettings.method) {
  n2 = await snapshot();
  await shot('05-settings');
  check(JSON.stringify(stackOf(n2)) === '["home","settings"]', `Settings opens (stack ${JSON.stringify(stackOf(n2))})`);
  l2 = await layout(n2);
  check(l2.overlaps.length === 0, `Settings: no two controls overlap${l2.overlaps.length ? ': ' + l2.overlaps.join('; ') : ''}`);
  screens.push({ name: 'settings', stack: JSON.stringify(stackOf(n2)), overlaps: l2.overlaps, outOfWindow: l2.outOfWindow });
  await command('uib_input key ESCAPE');
  await sleep(1200);
} else {
  check(false, 'Settings: the tab has an on-screen rect');
}

// In a match: our pause screen, never the old Xonotic game menu.
await command('bot_number 2; map stormkeep');
let loaded = false;
for (let i = 0; i < 1200 && !loaded; i++) {
  await sleep(250);
  loaded = (await logLines()).slice(-40).some((l) => /client: program loaded|CSQC.*loaded/i.test(l));
}
check(loaded, 'a local match loads on stormkeep');
await sleep(5000);
await shot('06-ingame');
if (touch) {
  const hud = await ask('echo HUDSTATE $_touch_hud_mode $_touch_hud_controls', (x) => x.includes('HUDSTATE'));
  check(/HUDSTATE (playing|dead|spectating|following|out)\b/.test(hud), `the touch HUD is running in a web match (${hud.trim()})`);
  const hudRects = await ask('touch_hud_state rects', (x) => x.includes('TOUCHRECTS mode='));
  check(/TOUCHRECTS mode=(playing|dead|spectating|following|out)/.test(hudRects), `the touch HUD lays out its controls (${hudRects.trim().slice(0, 120)})`);
}
await command('uib_input key ESCAPE');
await sleep(2500);
let nm = await snapshot();
let resumeRect = await rectOf('pause/actions/resume');
if (!resumeRect) {
  // A browser may take Escape for its own fullscreen/pointer-lock exit, or
  // the menu was open already: open it the way the MENU pill does and check
  // that our pause screen is actually drawn, not only described.
  await command('togglemenu 1');
  await sleep(2000);
  nm = await snapshot();
  resumeRect = await rectOf('pause/actions/resume');
}
await shot('07-inmatch-menu');
const gm = await ask('echo GMENU $_menu_gamemenu_dialog_available', (x) => x.includes('GMENU'));
const paused = await ask('echo PAUSED $_touch_menu_paused', (x) => x.includes('PAUSED'));
const mstack = JSON.stringify(stackOf(nm));
const classic = await ask('echo CLASSIC $_uib_classic_up', (x) => x.includes('CLASSIC'));
check(/CLASSIC 0/.test(classic), `the stock Xonotic menu is not drawing (${classic.trim()}; ${gm.trim()})`);
check(mstack === '["pause"]', `the in-match menu is our pause screen (stack ${mstack}, ${paused.trim()})`);
const layoutInfo = await evaluate('window.xt && xt.cvar ? [xt.cvar("_uib_region_h"), xt.cvar("_uib_screen_h")].join("x") : "?"');
check(!!resumeRect, `our pause screen is drawn, Resume at ${resumeRect && Math.round(resumeRect.x)},${resumeRect && Math.round(resumeRect.y)} (layout ${layoutInfo})`);
const lm = await layout(nm, Object.keys(nm).filter((k) => k.startsWith('pause/actions/')));
check(lm.overlaps.length === 0, `in-match menu: no two controls overlap${lm.overlaps.length ? ': ' + lm.overlaps.join('; ') : ''}`);
check(lm.outOfWindow.length === 0, `in-match menu: every control is inside the window${lm.outOfWindow.length ? ': ' + lm.outOfWindow.join('; ') : ''}`);
screens.push({ name: 'in-match', stack: mstack, gmenu: gm.trim(), overlaps: lm.overlaps, outOfWindow: lm.outOfWindow });

// Back to the game and out again: the pause screen closes.
await tapNode('pause/actions/resume', null, async () => !(await rectOf('pause/actions/resume')));
await sleep(1200);
await shot('08-back-ingame');
const afterResume = await evaluate('window.xt && xt.cvar ? xt.cvar("_touch_menu_paused") : "?"');
check(String(afterResume) === '0', `Resume goes back to the game (_touch_menu_paused=${afterResume})`);
await command('disconnect');

check(consoleErrors.length === 0, `no console errors${consoleErrors.length ? ': ' + consoleErrors.slice(0, 5).join(' | ') : ''}`);
console.log(JSON.stringify({ label, W, H, touch, fail: fail ? 1 : 0, screens, errors: consoleErrors }));
try { ws.close(); } catch (e) { /* gone */ }
process.exit(fail ? 1 : 0);
