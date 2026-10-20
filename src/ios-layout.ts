// iOS counterpart of the existing Android KLE and binding boundary.
import type { Binding, KeyShape } from './model.ts';

const mods: Record<string, number> = { LCTL: 1, LSFT: 2, LALT: 4, LGUI: 8, RCTL: 16, RSFT: 32, RALT: 64, RGUI: 128 };
export const iosBindings: Binding[] = [];
const add = (name: string, code = 0, modifier = 0, layer = 0, action = 'NONE', consumer = 0) =>
  iosBindings.push({ name, code, modifier, layer, action, consumer });
['Ctrl', 'Shift', 'Alt', 'Win/Cmd'].forEach((name, i) => add(name, 0, 1 << i));
add('レイヤー0へ戻る', 0, 0, 0, 'BASE');
for (let i = 1; i <= 3; i++) add(`レイヤー${i}切り替え`, 0, 0, i, 'TOGGLE');
add('次のレイヤー', 0, 0, 0, 'CYCLE');
for (let i = 1; i <= 3; i++) add(`次の1キーだけレイヤー${i}`, 0, 0, i, 'ONE_SHOT');
for (let i = 1; i <= 3; i++) add(`押している間レイヤー${i}`, 0, 0, i, 'MOMENTARY');
for (const [name, code, mod] of [['Ctrl+Z', 29, 1], ['Ctrl+Shift+Z', 29, 3], ['Ctrl+Y', 28, 1], ['Ctrl+X', 27, 1],
  ['Ctrl+C', 6, 1], ['Ctrl+V', 25, 1], ['Ctrl+S', 22, 1], ['Ctrl+A', 4, 1]] as const) add(name, code, mod);
for (const [name, usage] of [['Play/Pause', 0xcd], ['Next Track', 0xb5], ['Previous Track', 0xb6], ['Stop', 0xb7],
  ['Volume Up', 0xe9], ['Volume Down', 0xea], ['Mute', 0xe2]] as const) add(name, 0, 0, 0, 'NONE', usage);
Array.from('ABCDEFGHIJKLMNOPQRSTUVWXYZ').forEach((c, i) => add(c, i + 4));
Array.from('1234567890').forEach((c, i) => add(c, i + 30));
for (const [name, code] of [['Enter', 40], ['Esc', 41], ['Backspace', 42], ['Tab', 43], ['Space', 44], ['-', 45], ['=', 46],
  ['[', 47], [']', 48], ['\\', 49], [';', 51], ["'", 52], ['`', 53], [',', 54], ['.', 55], ['/', 56], ['Caps Lock', 57]] as const) add(name, code);
for (let i = 1; i <= 12; i++) add(`F${i}`, 57 + i);
['Print Screen', 'Scroll Lock', 'Pause', 'Insert', 'Home', 'Page Up', 'Delete', 'End', 'Page Down', 'Right', 'Left', 'Down', 'Up',
  'Num Lock', 'Numpad /', 'Numpad *', 'Numpad -', 'Numpad +', 'Numpad Enter'].forEach((name, i) => add(name, 70 + i));
for (let i = 1; i <= 9; i++) add(`Numpad ${i}`, 88 + i);
add('Numpad 0', 98); add('Numpad .', 99); add('Menu', 101);
const aliases: Record<string, string> = {
  ENT: 'Enter', ESC: 'Esc', BSPC: 'Backspace', SPC: 'Space', CAPS: 'Caps Lock', DEL: 'Delete', INS: 'Insert', PGUP: 'Page Up',
  PGDN: 'Page Down', RGHT: 'Right', LEFT: 'Left', DOWN: 'Down', UP: 'Up', MINS: '-', EQL: '=', LBRC: '[', RBRC: ']',
  BSLS: '\\', SCLN: ';', QUOT: "'", GRV: '`', COMM: ',', DOT: '.', SLSH: '/',
};
export function iosBinding(name: string): Binding | undefined {
  const exact = iosBindings.find(b => b.name === name); if (exact) return exact;
  let expression = name.trim(), modifier = 0;
  for (let i = 0; i < 8; i++) {
    const match = /^([^()]+)\((.*)\)$/.exec(expression); if (!match) break;
    const mod = mods[match[1].trim().toUpperCase()]; if (!mod) return;
    modifier |= mod; expression = match[2].trim();
  }
  if (!modifier || /[()]/.test(expression)) return;
  const token = expression.toUpperCase().replace(/^KC_/, ''), baseName = aliases[token] ?? token;
  const base = iosBindings.find(b => b.name.toUpperCase() === baseName.toUpperCase() && b.code && !b.modifier && !b.consumer);
  return base ? { ...base, name: name.trim(), modifier } : undefined;
}
const labels: Record<string, string> = {
  escape: 'Esc', esc: 'Esc', backspace: 'Backspace', bksp: 'Backspace', '⌫': 'Backspace', tab: 'Tab', enter: 'Enter',
  return: 'Enter', '↵': 'Enter', space: 'Space', spacebar: 'Space', 'caps lock': 'Caps Lock', caps: 'Caps Lock',
  shift: 'Shift', '⇧': 'Shift', ctrl: 'Ctrl', control: 'Ctrl', alt: 'Alt', option: 'Alt', win: 'Win/Cmd', cmd: 'Win/Cmd',
  command: 'Win/Cmd', meta: 'Win/Cmd', super: 'Win/Cmd', '←': 'Left', left: 'Left', '→': 'Right', right: 'Right',
  '↑': 'Up', up: 'Up', '↓': 'Down', down: 'Down', del: 'Delete', delete: 'Delete', ins: 'Insert', insert: 'Insert',
  pgup: 'Page Up', 'page up': 'Page Up', pgdn: 'Page Down', 'page down': 'Page Down', home: 'Home', end: 'End',
};
function labelBinding(label: string) {
  const name = label.trim(), canonical = labels[name.toLowerCase()];
  const binding = canonical ? iosBinding(canonical) : iosBinding(name) ?? iosBindings.find(b => b.name.toLowerCase() === name.toLowerCase());
  if (binding) return binding.name;
  const plain = '~!@#$%^&*()_+{}|:"<>?'.indexOf(name);
  return name.length === 1 && plain >= 0 ? '`1234567890-=[]\\;\',./'[plain] : undefined;
}
const labelMaps = [
  [0, 6, 2, 8, 9, 11, 3, 5, 1, 4, 7, 10], [1, 7, -1, -1, 9, 11, 4, -1, -1, -1, -1, 10],
  [3, -1, 5, -1, 9, 11, -1, -1, 4, -1, -1, 10], [4, -1, -1, -1, 9, 11, -1, -1, -1, -1, -1, 10],
  [0, 6, 2, 8, 10, -1, 3, 5, 1, 4, 7, -1], [1, 7, -1, -1, 10, -1, 4, -1, -1, -1, -1, -1],
  [3, -1, 5, -1, 10, -1, -1, -1, 4, -1, -1, -1], [4, -1, -1, -1, 10, -1, -1, -1, -1, -1, -1, -1],
];
export const iosBundledBindings: Record<string, string[]> = {
  'ghosted-trackpad.json': ['', 'Ctrl+Z', 'Ctrl+X', 'Ctrl+C', 'Ctrl+V', 'Ctrl+Shift+Z', 'Ctrl+Y', 'Ctrl+A', 'Ctrl+S', 'Mute', 'Volume Down', 'Volume Up', 'Enter'],
  'default-kle.json': ['Esc', ...Array.from({ length: 12 }, (_, i) => `F${i + 1}`), '`', ...Array.from('1234567890'), '-', '=', 'Backspace',
    'Tab', ...Array.from('QWERTYUIOP'), '[', ']', '\\', 'Caps Lock', ...Array.from('ASDFGHJKL'), ';', "'", 'Enter',
    'Shift', ...Array.from('ZXCVBNM'), ',', '.', '/', 'Shift', 'Ctrl', 'Win/Cmd', 'Alt', 'Space', 'Alt', 'Ctrl', 'Left', 'Down', 'Up', 'Right',
    'Print Screen', 'Scroll Lock', 'Pause', 'Insert', 'Home', 'Page Up', 'Delete', 'End', 'Page Down', 'Win/Cmd', 'Menu'],
};
export function parseIosLayout(json: string, bundled?: string) {
  if (encodeURIComponent(json).replace(/%[\da-f]{2}/gi, 'x').length > 1024 * 1024) throw Error('ファイルは1MB以下にしてください');
  const rows: unknown = JSON.parse(json);
  if (!Array.isArray(rows) || !rows.length) throw Error('KLEの行がありません');
  let name = 'KLEキーボード', x = 0, y = 0, rx = 0, ry = 0, rotation = 0, color = 0xffd9e0e9 | 0, align = 4, ghost = false;
  const keys: KeyShape[] = [], inferred: Record<string, string> = {};
  for (const [rowIndex, row] of rows.entries()) {
    if (rowIndex === 0 && row && !Array.isArray(row) && typeof row === 'object') { name = String(row.name ?? name); continue; }
    if (!Array.isArray(row)) throw Error('KLEの行は配列である必要があります');
    let w = 1, h = 1, x2 = 0, y2 = 0, w2 = 0, h2 = 0, decal = false; x = rx;
    for (const [index, item] of row.entries()) {
      if (item && typeof item === 'object' && !Array.isArray(item)) {
        if (!index) { rotation = item.r ?? rotation; rx = item.rx ?? rx; ry = item.ry ?? ry; x = rx; if ('rx' in item || 'ry' in item) y = ry; }
        else if ('r' in item || 'rx' in item || 'ry' in item) throw Error('回転属性は行の先頭に指定してください');
        x += item.x ?? 0; y += item.y ?? 0; w = item.w ?? w; h = item.h ?? h;
        x2 = item.x2 ?? x2; y2 = item.y2 ?? y2; w2 = item.w2 ?? w2; h2 = item.h2 ?? h2;
        align = item.a ?? align; decal = item.d ?? decal; ghost = item.g ?? ghost;
        if (typeof item.c === 'string' && /^#(?:[\da-f]{3}|[\da-f]{6}|[\da-f]{8})$/i.test(item.c)) {
          let hex = item.c.slice(1); if (hex.length === 3) hex = [...hex].map(c => c + c).join('');
          color = (parseInt(hex, 16) | (hex.length === 8 ? 0 : 0xff000000)) | 0;
        }
      } else if (typeof item === 'string') {
        if (![x, y, rx, ry, rotation, w, h, x2, y2, w2, h2, align].every(Number.isFinite) || w <= 0 || h <= 0 || w > 30 || h > 30) throw Error('キーの大きさが不正です');
        const keyLabels = Array<string>(12).fill(''), mapping = labelMaps[Math.max(0, Math.min(7, Math.trunc(align)))];
        item.split('\n').slice(0, 12).forEach((raw, i) => { if (mapping[i] >= 0) keyLabels[mapping[i]] = raw.replace(/<[^>]*>/g, '').trim(); });
        const key: KeyShape = { index: keys.length, x, y, w, h, x2, y2, w2: w2 || w, h2: h2 || h, rx, ry, rotation, color, labels: keyLabels, ghost, decal };
        keys.push(key); if (keys.length > 300) throw Error('キー数が多すぎます（最大300）');
        for (const at of [4, 0, 6, 2, 3, 5, 7, 8, 1, 9, 10, 11]) {
          const binding = labelBinding(keyLabels[at]); if (binding) { inferred[key.index] = binding; break; }
        }
        x += w; w = h = 1; x2 = y2 = w2 = h2 = 0; decal = false;
      } else throw Error('行に文字列以外のキーがあります');
    }
    y++;
  }
  if (!keys.length) throw Error('キーがありません');
  const bindings = bundled ? iosBundledBindings[bundled] : undefined;
  if (bindings?.length === keys.length) { Object.keys(inferred).forEach(k => delete inferred[k]); bindings.forEach((b, i) => { if (b) inferred[i] = b; }); }
  return { name, keys, inferred: JSON.stringify(inferred) };
}
