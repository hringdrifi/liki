export type FlickDirection = 'center' | 'left' | 'up' | 'right' | 'down';
export type KanaVariantAction = 'cycle' | 'voiced' | 'semiVoiced' | 'small';

export const imeOnKey = { code: 0x90, modifiers: 0 }; // Keyboard LANG1
export const imeOffKey = { code: 0x91, modifiers: 0 }; // Keyboard LANG2

export const directions: FlickDirection[] = ['center', 'left', 'up', 'right', 'down'];
export const kanaActionKeys: Record<string, number> = { 取消: 0x29, 削除: 0x2a, 左: 0x50, 右: 0x4f, 変換: 0x2c, 確定: 0x28 };
export const kanaPunctuationKey = '、。？！・';
export const kanaRows = [
  ['取消', 'あいうえお', 'かきくけこ', 'さしすせそ', '削除'],
  ['左', 'たちつてと', 'なにぬねの', 'はひふへほ', '右'],
  ['記号', 'まみむめも', 'や ゆ よ', 'らりるれろ', '変換'],
  ['英数', '゛゜小', 'わをんー ', kanaPunctuationKey, '確定'],
];

const kanaSymbols = [
  ['!', 0x1e, 2, 0x1e, 2], ['?', 0x38, 2, 0x38, 2],
  ['(', 0x25, 2, 0x26, 2], [')', 0x26, 2, 0x27, 2],
  ['[', 0x30, 0, 0x2f, 0], [']', 0x32, 0, 0x30, 0],
  ['{', 0x30, 2, 0x2f, 2], ['}', 0x32, 2, 0x30, 2],
  ['<', 0x36, 2, 0x36, 2], ['>', 0x37, 2, 0x37, 2],
  ['@', 0x2f, 0, 0x1f, 2], ['#', 0x20, 2, 0x20, 2],
  ['&', 0x23, 2, 0x24, 2], ['*', 0x34, 2, 0x25, 2],
  [':', 0x34, 0, 0x33, 2], [';', 0x33, 0, 0x33, 0],
  ['+', 0x33, 2, 0x2e, 2], ['=', 0x2d, 2, 0x2e, 0],
  ['-', 0x2d, 0, 0x2d, 0], ['_', 0x87, 2, 0x2d, 2],
  ['/', 0x38, 0, 0x38, 0], ['%', 0x22, 2, 0x22, 2],
  ['"', 0x1f, 2, 0x34, 2], ["'", 0x24, 2, 0x34, 0],
] as const;

export function kanaSymbolKeys(layout: 'jis' | 'us') {
  return kanaSymbols.map(([label, jisCode, jisModifiers, usCode, usModifiers]) => ({
    label, code: layout === 'jis' ? jisCode : usCode, modifiers: layout === 'jis' ? jisModifiers : usModifiers,
  }));
}

const variants: Record<Exclude<KanaVariantAction, 'cycle'>, [string, string]> = {
  small: ['あいうえおつやゆよわ', 'ぁぃぅぇぉっゃゅょゎ'],
  voiced: ['うかきくけこさしすせそたちつてとはひふへほ', 'ゔがぎぐげござじずぜぞだぢづでどばびぶべぼ'],
  semiVoiced: ['はひふへほ', 'ぱぴぷぺぽ'],
};

const romaji = new Map<string, string>(Object.entries({
  や: 'ya', ゆ: 'yu', よ: 'yo', わ: 'wa', を: 'wo', ん: 'nn', ゔ: 'vu', っ: 'xtu',
  ゃ: 'xya', ゅ: 'xyu', ょ: 'xyo', ゎ: 'xwa',
}));
for (const [kana, consonant] of [
  ['あいうえお', ''], ['かきくけこ', 'k'], ['さしすせそ', 's'], ['たちつてと', 't'], ['なにぬねの', 'n'],
  ['はひふへほ', 'h'], ['まみむめも', 'm'], ['らりるれろ', 'r'], ['がぎぐげご', 'g'], ['ざじずぜぞ', 'z'],
  ['だぢづでど', 'd'], ['ばびぶべぼ', 'b'], ['ぱぴぷぺぽ', 'p'], ['ぁぃぅぇぉ', 'x'],
]) {
  Array.from(kana).forEach((character, i) => romaji.set(character, consonant + 'aiueo'[i]));
}

const punctuation: Record<string, { code: number; modifiers: number }> = {
  'ー': { code: 0x2d, modifiers: 0 }, '、': { code: 0x36, modifiers: 0 }, '。': { code: 0x37, modifiers: 0 },
  '？': { code: 0x38, modifiers: 2 }, '！': { code: 0x1e, modifiers: 2 }, '・': { code: 0x38, modifiers: 0 },
};

export function flickDirection(dx: number, dy: number): FlickDirection {
  if (Math.hypot(dx, dy) < 12) return 'center';
  if (Math.abs(dx) >= Math.abs(dy)) return dx < 0 ? 'left' : 'right';
  return dy < 0 ? 'up' : 'down';
}

export function variantKana(character: string, action: KanaVariantAction) {
  let base = character;
  for (const [plain, changed] of Object.values(variants)) {
    const index = changed.indexOf(character);
    if (index >= 0) { base = plain[index]; break; }
  }
  if (action === 'cycle') {
    const available = [base, ...Object.values(variants).map(([plain, changed]) => changed[plain.indexOf(base)]).filter(Boolean)];
    return available[(available.indexOf(character) + 1) % available.length];
  }
  const [plain, changed] = variants[action];
  const next = changed[plain.indexOf(base)];
  return next === character ? base : next ?? character;
}

export function kanaTaps(character: string) {
  const spelling = romaji.get(character);
  if (spelling) return Array.from(spelling, letter => ({ code: letter.charCodeAt(0) - 93, modifiers: 0 }));
  return Object.hasOwn(punctuation, character) ? [punctuation[character]] : [];
}
