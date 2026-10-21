import test from 'node:test';
import assert from 'node:assert/strict';
import { LikiModel, Geometry, rotate } from '../src/model.ts';
import type { Backend, Binding, Data, KeyShape, Point } from '../src/model.ts';
import { KeyboardGesture } from '../src/gestures.ts';

const binding = (name: string, code = 0, modifier = 0, action = 'NONE', layer = 0, consumer = 0): Binding => ({ name, code, modifier, action, layer, consumer });
const shape = (index: number, ghost = false): KeyShape => ({ index, x: index, y: 0, w: 1, h: 1, x2: 0, y2: 0, w2: 1, h2: 1,
  rx: 0, ry: 0, rotation: 0, color: 0xffd9e0e9, labels: Array(12).fill(''), ghost, decal: false });
const options = [binding('A', 4), binding('B', 5), binding('Ctrl', 0, 1), binding('Shift', 0, 2), binding('Mute', 0, 0, 'NONE', 0, 0xe2),
  binding('base', 0, 0, 'BASE'), binding('cycle', 0, 0, 'CYCLE'), ...[1, 2, 3].flatMap(l =>
    [binding(`toggle${l}`, 0, 0, 'TOGGLE', l), binding(`once${l}`, 0, 0, 'ONE_SHOT', l), binding(`hold${l}`, 0, 0, 'MOMENTARY', l)])];
async function fixture() {
  const calls: { method: string; args: Data }[] = [], preferences: Data = {};
  const settings: Data = { sticky_modifiers: true, kle_json_key_labels: true, pinch_zoom: true, drag_move_keyboard: true,
    key_pitch_mm: 0, auto_key_scale_px: 0, pointer_speed: 1, scroll_speed: 1, tap_to_click: true, hold_to_drag: true, tap_drag: true,
    two_finger_scroll: true, trackpad_pinch_zoom: true, two_finger_right_click: true, three_finger_middle_click: true };
  const native: Backend = {
    subscribe: () => ({ remove() {} }),
    async invoke(method, args = {}) {
      calls.push({ method, args });
      if (method === 'parse') {
        if (args.json === 'invalid') throw Error('invalid KLE');
        return { name: 'fixture', keys: [shape(0), shape(1), shape(2, true)], inferred: '{"0":"A","1":"B"}' };
      }
      if (method === 'binding') return options.find(b => b.name === args.name);
      if (method === 'setting') { settings[args.key] = args.value; return { ...settings }; }
      if (method === 'preferences') Object.assign(preferences, args.values);
      return null;
    },
  };
  const m = new LikiModel(native); m.settings = { ...settings }; m.options = options; options.forEach(b => m.bindings.set(b.name, b));
  await m.readLayout('fixture'); m.overrides = { 0: 'A', 1: 'B' }; m.initialized = true; calls.length = 0;
  const sent = (method: string) => calls.filter(c => c.method === method).map(c => c.args);
  return { m, calls, sent, preferences };
}

test('sticky modifiers combine, send with a key and clear', async () => {
  const { m, sent } = await fixture(); m.overrides[0] = 'Ctrl'; await m.tap(m.keys[0]);
  assert.equal(m.modifiers, 1); assert.equal(sent('key').length, 0);
  await m.tap(m.keys[1]); assert.deepEqual(sent('key'), [{ modifiers: 1, code: 5 }]); assert.equal(m.modifiers, 0);
});
test('nonsticky modifiers use standalone reports', async () => {
  const { m, sent } = await fixture(); m.settings.sticky_modifiers = false; m.overrides[0] = 'Shift'; await m.tap(m.keys[0]); await m.tap(m.keys[1]);
  assert.deepEqual(sent('key'), [{ modifiers: 2, code: 0 }, { modifiers: 0, code: 5 }]);
});
test('all three extended layers toggle, inherit, override and return to zero', async () => {
  const { m, sent } = await fixture();
  for (const l of [1, 2, 3]) {
    m.overrides[0] = `toggle${l}`; await m.tap(m.keys[0]); assert.equal(m.layer, l); assert.equal(m.bindingFor(m.keys[1])?.name, 'B');
    m.layers[l] = { 1: 'A' }; await m.tap(m.keys[1]); assert.equal(sent('key').at(-1)?.code, 4);
    await m.tap(m.keys[0]); assert.equal(m.layer, 0);
  }
});
test('cycle covers L0 through L3 and explicit base resets', async () => {
  const { m } = await fixture(); m.overrides[0] = 'cycle';
  for (const l of [1, 2, 3, 0]) { await m.tap(m.keys[0]); assert.equal(m.layer, l); }
  m.setLayer(3); m.overrides[0] = 'base'; await m.tap(m.keys[0]); assert.equal(m.layer, 0);
});
test('one-shot survives modifiers then restores previous layer and its marker', async () => {
  const { m } = await fixture(); m.setLayer(2); m.layerKey = 8; m.overrides = { 0: 'once3', 1: 'Ctrl' };
  await m.tap(m.keys[0]); await m.tap(m.keys[1]); assert.equal(m.layer, 3);
  m.layers[3] = { 1: 'B' }; await m.tap(m.keys[1]); assert.equal(m.layer, 2); assert.equal(m.layerKey, 8); assert.equal(m.oneShotReturn, undefined);
});
test('consumer reports consume one-shot without a keyboard report', async () => {
  const { m, sent } = await fixture(); m.overrides = { 0: 'once1', 1: 'Mute' }; await m.tap(m.keys[0]); await m.tap(m.keys[1]);
  assert.deepEqual(sent('consumer'), [{ usage: 0xe2 }]); assert.equal(sent('key').length, 0); assert.equal(m.layer, 0);
});
test('cancel discards all edit drafts; save writes main-compatible preference keys', async () => {
  const { m, preferences } = await fixture(); m.beginEdit(); m.editLayer = 3; await m.assign(m.keys[1], 'A'); await m.endEdit(false);
  assert.deepEqual(m.layers, {}); assert.deepEqual(preferences, {});
  m.beginEdit(); m.editLayer = 3; await m.assign(m.keys[1], 'A'); await m.endEdit(true);
  assert.deepEqual(JSON.parse(preferences.layer_overrides), { 3: { 1: 'A' } });
});
test('layout roundtrip preserves physical pan, zoom and layer overrides; names ignore case', async () => {
  const { m } = await fixture(); m.density = 2; m.geometry!.pan = { x: 12, y: -3 }; m.geometry!.zoom = 2; m.layers = { 3: { 1: 'A' } };
  await m.saveLayout('Test'); const saved = m.saved[0]; assert.equal(saved.pan_x, 24); assert.equal(saved.pan_y, -6);
  await m.applyLayout('other'); await m.restoreLayout(saved); assert.deepEqual(m.geometry!.pan, { x: 12, y: -3 }); assert.equal(m.geometry!.zoom, 2);
  assert.deepEqual(m.layers, { 3: { 1: 'A' } }); await m.saveLayout('test'); assert.equal(m.saved.length, 1);
  await m.deleteLayout('TEST'); assert.equal(m.saved.length, 0); assert.equal(m.selectedName, undefined);
});
test('invalid import leaves active state untouched', async () => {
  const { m } = await fixture(), geometry = m.geometry;
  await assert.rejects(m.applyLayout('invalid')); assert.equal(m.geometry, geometry); assert.equal(m.json, 'fixture'); assert.deepEqual(m.overrides, { 0: 'A', 1: 'B' });
});
test('rotated secondary key hit and zoom focus match drawn geometry', async () => {
  const key = { ...shape(0), rotation: 90, x2: -1, w2: 2 }, g = new Geometry([key]); g.width = 500; g.height = 300; g.base = 40;
  const world = rotate(key, { x: -.5, y: .5 }), hit = g.hit({ x: g.origin.x + world.x * g.scale, y: g.origin.y + world.y * g.scale }); assert.equal(hit?.index, 0);
  const focus = { x: 123, y: 87 }, before = g.world(focus); g.zoomAt(2, focus, false); assert.ok(Math.hypot(g.world(focus).x - before.x, g.world(focus).y - before.y) < 1e-8);
  g.zoomAt(.01, focus, false); assert.equal(g.zoom, 1);
});
test('viewport fitting preserves physical size on rotation and reset fits the new viewport', async () => {
  const { m } = await fixture(); m.density = 2; m.xdpi = 320;
  m.setViewport(m.geometry!, 360, 640); const base = m.geometry!.base;
  assert.ok(Math.abs(base - 340 / 3) < 1e-8);
  m.setViewport(m.geometry!, 640, 360); assert.equal(m.geometry!.base, base);
  await m.setSetting('key_pitch_mm', 10); assert.ok(Math.abs(m.geometry!.base - 10 * 320 / 25.4 / 2) < 1e-8);
  await m.resetView(); assert.ok(Math.abs(m.geometry!.base - 620 / 3) < 1e-8);
});
const pointer = (g: KeyboardGesture, kind: 'down' | 'move' | 'up' | 'cancel', id: number, p: Point, time: number) => g.touch({ kind, id, position: p, time });

test('relay mode preserves the previous layout and pauses capture for menus and settings', async () => {
  const { m, sent, preferences } = await fixture(); m.status.relaySupported = true; m.showingControls = true;
  const layout = m.json, bindings = { ...m.overrides };
  await m.setRelayMode(true);
  assert.equal(m.relayMode, true); assert.equal(preferences.input_mode, 'relay');
  assert.equal(m.json, layout); assert.deepEqual(m.overrides, bindings); assert.equal(sent('page').at(-1)?.relay, true);
  m.pauseRelay(true); assert.equal(sent('page').at(-1)?.relay, false);
  m.pauseRelay(false); assert.equal(sent('page').at(-1)?.relay, true);
  m.settingsVisible = true; m.syncPage(); assert.equal(sent('page').at(-1)?.relay, false);
  m.settingsVisible = false; await m.setRelayMode(false);
  assert.equal(sent('page').at(-1)?.relay, false); assert.deepEqual(m.overrides, bindings);
});

test('relay mode rejects an old native build and survives a cancelled or invalid layout import', async () => {
  const { m, sent } = await fixture();
  await assert.rejects(m.setRelayMode(true)); assert.equal(m.relayMode, false); assert.equal(sent('preferences').length, 0);
  m.status.relaySupported = true; await m.setRelayMode(true);
  await assert.rejects(m.applyLayout('invalid')); assert.equal(m.relayMode, true);
});

test('choosing an ordinary or saved layout ends relay mode', async () => {
  const { m, sent } = await fixture(); m.status.relaySupported = true; m.showingControls = true;
  await m.saveLayout('Previous'); const previous = m.saved[0];
  await m.setRelayMode(true); await m.applyLayout('fixture');
  assert.equal(m.relayMode, false); assert.equal(sent('page').at(-1)?.relay, false);
  await m.setRelayMode(true); await m.restoreLayout(previous);
  assert.equal(m.relayMode, false); assert.equal(m.selectedName, 'Previous');
});
test('layout replacement and restore remain visible without another viewport event', async () => {
  const { m } = await fixture();
  m.setViewport(m.geometry!, 360, 640);
  await m.saveLayout('Visible');
  const saved = m.saved[0];
  const visible = () => {
    const g = m.geometry!;
    assert.equal(g.width, 360); assert.equal(g.height, 640);
    for (const key of m.keys) {
      const center = { x: g.origin.x + (key.x + key.w / 2) * g.scale, y: g.origin.y + (key.y + key.h / 2) * g.scale };
      assert.ok(center.x > 0 && center.x < 360 && center.y > 0 && center.y < 640);
      assert.equal(g.hit(center)?.index, key.index);
    }
  };
  for (const layout of ['other', 'fixture']) { await m.applyLayout(layout); visible(); }
  await m.restoreLayout(saved); visible();
});
test('momentary chord keeps captured layer if holding pointer lifts first', async () => {
  const { m, sent } = await fixture(); m.geometry!.width = 300; m.geometry!.height = 100; m.geometry!.base = 80;
  m.overrides[0] = 'hold3'; m.layers[3] = { 1: 'A' }; const g = new KeyboardGesture(m, () => {});
  const origin = m.geometry!.origin, p = (x: number) => ({ x: origin.x + x * 80, y: origin.y + 40 });
  pointer(g, 'down', 1, p(.5), 1000); assert.equal(m.layer, 3);
  pointer(g, 'down', 2, p(1.5), 1010); pointer(g, 'up', 1, p(.5), 1100); assert.equal(m.layer, 0);
  pointer(g, 'up', 2, p(1.5), 1150); assert.deepEqual(sent('key'), [{ modifiers: 0, code: 4 }]); g.cancel();
});
test('ghosted area handles one, two and three finger clicks', async () => {
  const { m, sent } = await fixture(); m.geometry!.width = 300; m.geometry!.height = 100; m.geometry!.base = 100;
  const g = new KeyboardGesture(m, () => {});
  for (const count of [1, 2, 3]) {
    for (let id = 1; id <= count; id++) pointer(g, 'down', id, { x: 210 + id * 10, y: 30 }, count * 1000);
    for (let id = count; id >= 1; id--) pointer(g, 'up', id, { x: 210 + id * 10, y: 30 }, count * 1000 + 100);
  }
  assert.deepEqual(sent('click').map(e => e.button), [1, 2, 4]); g.cancel();
});
test('long press releases mouse on cancellation, and double tap drag starts on movement', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { m, sent, calls } = await fixture(); m.geometry!.width = 300; m.geometry!.height = 100; m.geometry!.base = 100;
  const g = new KeyboardGesture(m, () => {});
  pointer(g, 'down', 1, { x: 220, y: 30 }, 1000); t.mock.timers.tick(451); g.cancel();
  assert.equal(sent('mouseDown').length, 1); assert.equal(sent('mouseUp').length, 1); calls.length = 0;
  pointer(g, 'down', 1, { x: 220, y: 30 }, 2000); pointer(g, 'up', 1, { x: 220, y: 30 }, 2100);
  pointer(g, 'down', 1, { x: 220, y: 30 }, 2200); pointer(g, 'move', 1, { x: 240, y: 30 }, 2250); pointer(g, 'up', 1, { x: 240, y: 30 }, 2300);
  assert.equal(sent('mouseDown').length, 1); assert.equal(sent('mouseUp').length, 1); assert.equal(sent('click').length, 1); g.cancel();
});
test('two finger scroll and pinch produce distinct native actions', async () => {
  const { m, sent, calls } = await fixture(); m.geometry!.width = 300; m.geometry!.height = 100; m.geometry!.base = 100;
  const g = new KeyboardGesture(m, () => {});
  pointer(g, 'down', 1, { x: 210, y: 80 }, 1000); pointer(g, 'down', 2, { x: 250, y: 80 }, 1000);
  g.points.set(2, { x: 250, y: 30 }); pointer(g, 'move', 1, { x: 210, y: 30 }, 1100);
  assert.deepEqual(sent('scroll'), [{ amount: 2 }]); assert.equal(sent('zoom').length, 0); g.cancel(); calls.length = 0;
  pointer(g, 'down', 1, { x: 210, y: 50 }, 2000); pointer(g, 'down', 2, { x: 250, y: 50 }, 2000);
  g.points.set(2, { x: 290, y: 50 }); pointer(g, 'move', 1, { x: 170, y: 50 }, 2100);
  assert.deepEqual(sent('zoom'), [{ amount: 3 }]); assert.equal(sent('scroll').length, 0); g.cancel();
});
