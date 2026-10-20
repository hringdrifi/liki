import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { iosBinding, iosBindings, parseIosLayout } from '../src/ios-layout.ts';
import { createIosBackend } from '../src/ios-backend.ts';
import { LikiModel } from '../src/model.ts';

const assets = Object.fromEntries(['default-kle.json', 'ghosted-trackpad.json'].map(name =>
  [name, readFileSync(new URL(`../modules/liki-hid/android/src/main/assets/${name}`, import.meta.url), 'utf8')]));

test('iOS default assets retain Android key counts and explicit shortcut assignments', () => {
  const keyboard = parseIosLayout(assets['default-kle.json'], 'default-kle.json');
  assert.equal(keyboard.keys.length, 87); assert.equal(Object.keys(JSON.parse(keyboard.inferred)).length, 87);
  assert.equal(JSON.parse(keyboard.inferred)['45'], 'F');
  const trackpad = parseIosLayout(assets['ghosted-trackpad.json'], 'ghosted-trackpad.json');
  assert.equal(trackpad.keys.length, 13); assert.equal(trackpad.keys[0].ghost, true);
  assert.deepEqual(Object.values(JSON.parse(trackpad.inferred)).slice(0, 5), ['Ctrl+Z', 'Ctrl+X', 'Ctrl+C', 'Ctrl+V', 'Ctrl+Shift+Z']);
  for (const name of Object.values(JSON.parse(keyboard.inferred)) as string[]) assert.ok(iosBinding(name), name);
  assert.equal(new Set(iosBindings.map(b => b.name)).size, iosBindings.length);
});
test('iOS KLE import preserves clusters, secondary shape, labels, ghosting and inferred QMK modifiers', () => {
  const parsed = parseIosLayout(JSON.stringify([{ name: 'custom' }, [{ r: 15, rx: 2, ry: 3, w: 1.5, x2: -.25, w2: 1.75, a: 7, c: '#123456' },
    'LSFT(LCTL(KC_A))', { g: true }, 'pad', { g: false }, 'B'], ['C']]));
  assert.equal(parsed.name, 'custom');
  assert.deepEqual([parsed.keys[0].x, parsed.keys[0].y, parsed.keys[0].rotation, parsed.keys[0].w2], [2, 3, 15, 1.75]);
  assert.equal(parsed.keys[0].labels[4], 'LSFT(LCTL(KC_A))');
  assert.equal(parsed.keys[0].color, 0xff123456 | 0);
  assert.equal(parsed.keys[1].ghost, true); assert.equal(parsed.keys[2].ghost, false);
  assert.equal(parsed.keys[3].x, 2); assert.equal(parsed.keys[3].y, 4);
  assert.equal(iosBinding(JSON.parse(parsed.inferred)['0'])?.modifier, 3);
  assert.equal(iosBinding('RSFT(RCTL(KC_A))')?.modifier, 48);
  assert.equal(iosBinding('LSFT(Volume Up)'), undefined);
  assert.equal(iosBinding('LSFT(KC_A))'), undefined);
});
test('iOS KLE rejects invalid size, excessive keys and non-finite geometry', () => {
  assert.throws(() => parseIosLayout('[[{"w":0},"A"]]'));
  assert.throws(() => parseIosLayout(JSON.stringify([Array(301).fill('A')])));
  assert.throws(() => parseIosLayout('[[{"x":"bad"},"A"]]'));
  assert.throws(() => parseIosLayout('[[]]'));
  assert.throws(() => parseIosLayout('[["A",{"r":20},"B"]]'));
  assert.throws(() => parseIosLayout(JSON.stringify([['日'.repeat(400000)]])));
});
test('iOS adapter initializes Liki, preserves explicit empty bindings, and passes HID and preference deletions through', async () => {
  const preferences: Record<string, string> = {}, calls: { method: string; args: any }[] = [];
  const native = {
    async invoke(method: string, args: any) {
      calls.push({ method, args });
      if (method === 'asset') return assets[args.name];
      if (method === 'initialize') return { preferences: { ...preferences }, settings: { border_colors: [0, 0, 0, 0] },
        density: 3, xdpi: 480, privacy: '', status: { connected: false, devices: [] } };
      if (method === 'status') return { connected: false, devices: [] };
    },
    async preferences(values: Record<string, string | null>) {
      Object.entries(values).forEach(([key, value]) => { if (value === null) delete preferences[key]; else preferences[key] = value; });
    },
    async pickFile() { return null; },
    addListener() { return { remove() {} }; },
  };
  const backend = createIosBackend(native), model = new LikiModel(backend);
  await model.initialize(); assert.equal(model.error, undefined); assert.equal(model.initialized, true);
  assert.equal(model.geometry?.keys.length, 87); assert.equal(Object.keys(model.overrides).length, 87);
  preferences.overrides = '{}'; assert.equal((await backend.invoke('initialize')).layout.overrides, '{}');
  await backend.invoke('preferences', { values: { overrides: null } }); assert.equal(preferences.overrides, undefined);
  assert.equal(Object.keys(JSON.parse((await backend.invoke('initialize')).layout.overrides)).length, 87);
  await backend.invoke('key', { modifiers: 2, code: 4 });
  assert.ok(calls.some(c => c.method === 'key' && c.args.modifiers === 2 && c.args.code === 4));
  model.dispose();
});
