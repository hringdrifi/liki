import React, { useEffect, useLayoutEffect, useMemo, useReducer } from 'react';
import { View } from 'react-native';
import type { GestureResponderEvent } from 'react-native';
import Svg, { G, Rect, Text as SvgText } from 'react-native-svg';
import { LikiModel } from './model';
import type { KeyShape } from './model';
import { KeyboardGesture } from './gestures';
import type { Touch } from './gestures';

export const colors = { background: '#10151c', surface: '#1f2834', text: '#ebf2f7', muted: '#a3b3c0', accent: '#80cbc4' };
export const hex = (color: number) => `#${(color & 0xffffff).toString(16).padStart(6, '0')}`;
const glyphWidth = (value: string) => Array.from(value).reduce((width, c) => width + (c.codePointAt(0)! >= 0x2e80 ? 1 : .55), 0);

function bindingLabel(value: string, width: number) {
  let lines = [value];
  if (/[\u2e80-\uffff]/.test(value) && glyphWidth(value) * .14 > width) {
    const characters = Array.from(value), half = glyphWidth(value) / 2;
    let split = 0, used = 0;
    while (split < characters.length - 1 && used < half) used += glyphWidth(characters[split++]);
    lines = [characters.slice(0, split).join('').trim(), characters.slice(split).join('').trim()];
  }
  return { lines, size: Math.min(.28, width / Math.max(2, ...lines.map(glyphWidth))) };
}

export function Keyboard({ model, onEdit }: { model: LikiModel; onEdit(key: KeyShape): void }) {
  const [, redraw] = useReducer(n => n + 1, 0), geometry = model.geometry!;
  const gesture = useMemo(() => new KeyboardGesture(model, () => {}, geometry), [model, geometry]);
  useLayoutEffect(() => gesture.setEditor(onEdit), [gesture, onEdit]);
  useEffect(() => () => gesture.cancel(false), [gesture]);
  const touch = (kind: Touch['kind'], event: GestureResponderEvent) => {
    const { changedTouches, touches } = event.nativeEvent;
    if (kind === 'move' || kind === 'up') for (const p of touches) gesture.points.set(p.identifier, { x: p.locationX, y: p.locationY });
    for (const p of changedTouches) gesture.touch({ kind, id: p.identifier, position: { x: p.locationX, y: p.locationY }, time: p.timestamp });
    redraw();
  };
  return <View style={{ flex: 1, overflow: 'hidden' }} accessibilityLabel="KLEキーボード。通常はタップで入力、設定モードではタップで割り当て"
    onLayout={e => { model.setViewport(geometry, e.nativeEvent.layout.width, e.nativeEvent.layout.height); redraw(); }}
    onStartShouldSetResponder={() => true} onMoveShouldSetResponder={() => true} onResponderTerminationRequest={() => false}
    onTouchStart={e => touch('down', e)} onTouchMove={e => touch('move', e)} onTouchEnd={e => touch('up', e)} onTouchCancel={e => touch('cancel', e)}>
    <Svg width="100%" height="100%" pointerEvents="none">
      <G transform={`translate(${geometry.origin.x},${geometry.origin.y}) scale(${geometry.scale})`}>
        {model.keys.map(key => <DrawKey key={key.index} shape={key} model={model} />)}
      </G>
    </Svg>
  </View>;
}

function DrawKey({ shape: k, model: m }: { shape: KeyShape; model: LikiModel }) {
  // Measure glyphs at their displayed size, as the original Android canvas does.
  const textScale = m.geometry!.scale;
  const b = m.bindingFor(k), overridden = m.overridden(k);
  const active = !k.ghost && !m.editing && (m.layerKey === k.index || b && !b.code && b.modifier && (m.modifiers & b.modifier));
  const c = k.color, dark = `rgb(${20 + ((c >> 16) & 255) / 4},${25 + ((c >> 8) & 255) / 4},${32 + (c & 255) / 4})`;
  const fill = active ? '#24736d' : m.displayedLayer && overridden && !k.ghost ? '#26485b' : dark;
  const showBinding = !k.ghost && (!m.flag('kle_json_key_labels') || k.labels.every(s => !s));
  const label = b ? bindingLabel(b.name, k.w - .14) : undefined;
  const primary = [k.x, k.y, k.w, k.h], secondary = [k.x + k.x2, k.y + k.y2, k.w2, k.h2];
  const rects = [primary, ...(primary.some((v, i) => v !== secondary[i]) ? [secondary] : [])];
  return <G transform={`rotate(${k.rotation},${k.rx},${k.ry})`}>
    {!k.decal && rects.map(([x, y, w, h], i) => <Rect key={i} x={x + .025} y={y + .025} width={w - .05} height={h - .05} rx={.1} ry={.1}
      fill={fill} stroke={hex(m.settings.border_colors[m.displayedLayer])} strokeWidth={.018} />)}
    <G transform={`scale(${1 / textScale})`}>
    {!showBinding && k.labels.slice(0, 9).map((label, i) => {
      if (!label) return null; const col = i % 3, row = Math.floor(i / 3);
      return <SvgText key={i} x={(col === 0 ? k.x + .11 : col === 2 ? k.x + k.w - .11 : k.x + k.w / 2) * textScale}
        y={(row === 0 ? k.y + .3 : row === 1 ? k.y + k.h / 2 + .08 : k.y + k.h - .1) * textScale}
        fill={colors.text} fontWeight="bold" fontSize={Math.min(.25, (k.w - .18) / Math.max(2, glyphWidth(label))) * textScale} textAnchor={col === 0 ? 'start' : col === 2 ? 'end' : 'middle'}>{label}</SvgText>;
    })}
    {showBinding && label && <>
      {label.lines.map((line, i) => <SvgText key={i} x={(k.x + k.w / 2) * textScale}
        y={(k.y + k.h / 2 + label.size * (.3 + (i - (label.lines.length - 1) / 2) * 1.25)) * textScale} fill={colors.text} textAnchor="middle" fontWeight="bold"
        fontSize={label.size * textScale}>{line}</SvgText>)}
      {!!m.displayedLayer && !overridden && <SvgText x={(k.x + k.w / 2) * textScale} y={(k.y + k.h - .1) * textScale} fill={colors.muted} textAnchor="middle" fontSize={.13 * textScale}>継承</SvgText>}
    </>}
    </G>
  </G>;
}
