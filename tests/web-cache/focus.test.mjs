// DOM stub: model the focus-changing default of pointer/mouse presses while
// running the actual shell listeners. Run with node tests/web-cache/focus.test.mjs.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

class Element extends EventTarget {
  constructor(kind, parent = null) { super(); this.kind = kind; this.parent = parent; }
  closest(selector) { return selector === 'button' ? (this.kind === 'button' ? this : this.parent?.closest(selector)) : null; }
  matches() { return this.kind === 'input'; }
}
const document = new EventTarget();
const kbd = new Element('input');
const canvas = new Element('canvas');
const button = new Element('button');
const icon = new Element('icon', button);
document.activeElement = kbd;
const source = readFileSync(new URL('../../web/shell.js', import.meta.url), 'utf8');
const start = source.indexOf('  // Cancel the focus-changing default');
const end = source.indexOf('  // ------------------------------------------------------------ audio', start);
assert.ok(start >= 0 && end > start, 'shell focus listeners found');
vm.runInNewContext(source.slice(start, end), { document, canvas, kbd });

let clicks = 0;
button.addEventListener('click', () => clicks++);
function press(target, type) {
  const e = new Event(type, { cancelable: true });
  Object.defineProperty(e, 'target', { value: target });
  document.dispatchEvent(e);
  if (target === canvas) canvas.dispatchEvent(e);
  if (!e.defaultPrevented) document.activeElement = target; // browser focus default
  return e;
}
const down = press(icon, 'pointerdown');
assert.equal(down.defaultPrevented, true);
assert.equal(document.activeElement, kbd, 'button press keeps text focus');
button.dispatchEvent(new Event('click'));
assert.equal(clicks, 1, 'button action runs once');

let engineDown = 0;
canvas.addEventListener('mousedown', () => engineDown++);
const pointer = new Event('pointerdown', { cancelable: true });
Object.defineProperty(pointer, 'target', { value: canvas });
document.dispatchEvent(pointer);
assert.equal(pointer.defaultPrevented, false, 'canvas pointer events remain available');
const mouse = press(canvas, 'mousedown');
assert.equal(mouse.defaultPrevented, true);
assert.equal(engineDown, 1, 'canvas mouse event reaches engine');
assert.equal(document.activeElement, kbd, 'canvas press keeps keyboard focus');
console.log('focus and control dispatch: ok');
