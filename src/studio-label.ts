// Short keycap legends; full binding names remain available in the editor.
const shortNames: Record<string, string> = {
  Backspace: 'Bksp', Delete: 'Del', Insert: 'Ins',
  'Page Up': 'PgUp', 'Page Down': 'PgDn', 'Caps Lock': 'Caps',
  'Print Screen': 'PrtSc', 'Scroll Lock': 'ScrLk', 'Num Lock': 'Num',
  'Volume Up': 'Vol +', 'Volume Down': 'Vol -',
  'Next Track': 'Next', 'Previous Track': 'Prev',
  Transparent: '透過', 'Key Press': 'キー入力',
};
const glyphWidth = (value: string) => Array.from(value).reduce((width, c) =>
  width + (c.codePointAt(0)! >= 0x2e80 ? 1 : .6), 0);

export function studioKeyLabel(value: string, width: number, height: number) {
  const layerTap = /^Layer (\d+) \/ (.+)$/.exec(value);
  const badge = layerTap ? `L${layerTap[1]}` : undefined;
  const lines = (layerTap ? layerTap[2] : value).split(' / ').flatMap(part => {
    const compact = part.split(/\+(?=\S)/).map(name => shortNames[name.trim()] ?? name.trim().replace(/^Numpad /, 'Num ')).join('+');
    if (glyphWidth(compact) * .18 <= width) return [compact];
    // Wrap at word/modifier boundaries without dropping custom behavior names or parameters.
    const words = compact.replace(/_/g, ' ').split(/(?<=\+)|\s+/).filter(Boolean);
    const wrapped: string[] = [];
    for (const word of words) {
      const previous = wrapped[wrapped.length - 1];
      const joined = previous ? `${previous}${previous.endsWith('+') ? '' : ' '}${word}` : word;
      if (previous && glyphWidth(joined) * .22 <= width) wrapped[wrapped.length - 1] = joined;
      else wrapped.push(word);
    }
    return wrapped.length ? wrapped : [''];
  });
  return { lines, size: Math.min(.22, width / Math.max(2, ...lines.map(glyphWidth)),
    (badge ? height * .55 : height) / (lines.length * 1.2)), ...(badge ? { badge } : {}) };
}
