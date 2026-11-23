/** Converts text typed on the phone's own keyboard into HID key taps for the connected PC. */
export type TextLayout = 'us' | 'jis';
export type KeyTap = { modifiers: number; code: number };
export const TEXT_KEYS = { enter: 0x28, escape: 0x29, backspace: 0x2a, tab: 0x2b, space: 0x2c, right: 0x4f, left: 0x50, down: 0x51, up: 0x52, zenkaku: 0x35 } as const;

const SHIFT = 2;
// HID positions and Shift pairs match KeyboardLayoutConverter in the native modules.
const symbols: Record<TextLayout, [code: number, plain: string, shifted: string][]> = {
  us: [[0x1e, '1', '!'], [0x1f, '2', '@'], [0x20, '3', '#'], [0x21, '4', '$'], [0x22, '5', '%'], [0x23, '6', '^'], [0x24, '7', '&'],
    [0x25, '8', '*'], [0x26, '9', '('], [0x27, '0', ')'], [0x2d, '-', '_'], [0x2e, '=', '+'], [0x2f, '[', '{'], [0x30, ']', '}'],
    [0x31, '\\', '|'], [0x33, ';', ':'], [0x34, '\'', '"'], [0x35, '`', '~'], [0x36, ',', '<'], [0x37, '.', '>'], [0x38, '/', '?']],
  jis: [[0x1e, '1', '!'], [0x1f, '2', '"'], [0x20, '3', '#'], [0x21, '4', '$'], [0x22, '5', '%'], [0x23, '6', '&'], [0x24, '7', '\''],
    [0x25, '8', '('], [0x26, '9', ')'], [0x27, '0', ''], [0x2d, '-', '='], [0x2e, '^', '~'], [0x2f, '@', '`'], [0x30, '[', '{'],
    [0x32, ']', '}'], [0x33, ';', '+'], [0x34, ':', '*'], [0x87, '\\', '_'], [0x89, '', '|'], [0x36, ',', '<'], [0x37, '.', '>'], [0x38, '/', '?']],
};
const tables = Object.fromEntries((['us', 'jis'] as const).map(layout => {
  const table = new Map<string, KeyTap>([[' ', { modifiers: 0, code: 0x2c }], ['\t', { modifiers: 0, code: TEXT_KEYS.tab }], ['\n', { modifiers: 0, code: TEXT_KEYS.enter }]]);
  for (let i = 0; i < 26; i++) {
    table.set(String.fromCharCode(97 + i), { modifiers: 0, code: 4 + i });
    table.set(String.fromCharCode(65 + i), { modifiers: SHIFT, code: 4 + i });
  }
  for (const [code, plain, shifted] of symbols[layout]) {
    if (plain) table.set(plain, { modifiers: 0, code });
    if (shifted) table.set(shifted, { modifiers: SHIFT, code });
  }
  return [layout, table];
})) as Record<TextLayout, Map<string, KeyTap>>;

// One romaji spelling per kana, independent of its neighbours, so that any edit can be
// replayed as "Backspace per removed character, then the new characters".
const kana = new Map<string, string>();
const rows: [string, string][] = [['あいうえお', ''], ['かきくけこ', 'k'], ['さしすせそ', 's'], ['たちつてと', 't'], ['なにぬねの', 'n'],
  ['はひふへほ', 'h'], ['まみむめも', 'm'], ['らりるれろ', 'r'], ['がぎぐげご', 'g'], ['ざじずぜぞ', 'z'], ['だぢづでど', 'd'],
  ['ばびぶべぼ', 'b'], ['ぱぴぷぺぽ', 'p'], ['ぁぃぅぇぉ', 'x']];
for (const [characters, consonant] of rows) Array.from(characters).forEach((c, i) => kana.set(c, consonant + 'aiueo'[i]));
for (const [c, romaji] of Object.entries({ や: 'ya', ゆ: 'yu', よ: 'yo', わ: 'wa', を: 'wo', ん: 'nn', っ: 'xtu', ゃ: 'xya', ゅ: 'xyu', ょ: 'xyo',
  ゎ: 'xwa', ゔ: 'vu', ー: '-', '、': ',', '。': '.', '「': '[', '」': ']', '・': '/', '〜': '~', '　': ' ' })) kana.set(c, romaji);

/** Key taps for one character, or undefined when it cannot be typed through a HID keyboard. */
export function keysForCharacter(character: string, layout: TextLayout): KeyTap[] | undefined {
  let c = character;
  const point = c.codePointAt(0) ?? 0;
  if (point >= 0xff01 && point <= 0xff5e) c = String.fromCharCode(point - 0xfee0); // full-width ASCII
  else if (point >= 0x30a1 && point <= 0x30f6) c = String.fromCharCode(point - 0x60); // katakana
  const taps = Array.from(kana.get(c) ?? c).map(ascii => tables[layout].get(ascii));
  return taps.length && taps.every(tap => tap) ? taps as KeyTap[] : undefined;
}

/**
 * Key taps that turn `previous` into `next` on the PC, assuming its caret is at the end.
 * Characters that were never sent are not erased either; they are reported in `skipped`.
 * When text that was sent is replaced by text that cannot be sent, the phone's keyboard has
 * converted it (kana to kanji); `converted` is set and the PC gets Space to convert there instead.
 */
export function textEdit(previous: string, next: string, layout: TextLayout): { keys: KeyTap[]; skipped: string[]; converted: boolean } {
  const before = Array.from(previous), after = Array.from(next), keys: KeyTap[] = [], skipped: string[] = [];
  let same = 0;
  while (same < before.length && same < after.length && before[same] === after[same]) same++;
  for (const c of before.slice(same)) if (keysForCharacter(c, layout)) keys.push({ modifiers: 0, code: TEXT_KEYS.backspace });
  const erased = keys.length;
  for (const c of after.slice(same)) {
    const taps = keysForCharacter(c, layout);
    if (taps) keys.push(...taps); else skipped.push(c);
  }
  if (erased && skipped.length) return { keys: [{ modifiers: 0, code: TEXT_KEYS.space }], skipped: [], converted: true };
  return { keys, skipped, converted: false };
}
