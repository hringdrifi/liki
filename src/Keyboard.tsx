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
  const b = m.bindingFor(k), overridden = m.overridden(k);
  const active = !k.ghost && !m.editing && (m.layerKey === k.index || b && !b.code && b.modifier && (m.modifiers & b.modifier));
  const c = k.color, dark = `rgb(${20 + ((c >> 16) & 255) / 4},${25 + ((c >> 8) & 255) / 4},${32 + (c & 255) / 4})`;
  const fill = active ? '#24736d' : m.displayedLayer && overridden && !k.ghost ? '#26485b' : dark;
  const showBinding = !k.ghost && (!m.flag('kle_json_key_labels') || k.labels.every(s => !s));
  const primary = [k.x, k.y, k.w, k.h], secondary = [k.x + k.x2, k.y + k.y2, k.w2, k.h2];
  const rects = [primary, ...(primary.some((v, i) => v !== secondary[i]) ? [secondary] : [])];
  return <G transform={`rotate(${k.rotation},${k.rx},${k.ry})`}>
    {!k.decal && rects.map(([x, y, w, h], i) => <Rect key={i} x={x + .025} y={y + .025} width={w - .05} height={h - .05} rx={.1} ry={.1}
      fill={fill} stroke={hex(m.settings.border_colors[m.displayedLayer])} strokeWidth={.018} />)}
    {!showBinding && k.labels.slice(0, 9).map((label, i) => {
      if (!label) return null; const col = i % 3, row = Math.floor(i / 3);
      return <SvgText key={i} x={col === 0 ? k.x + .11 : col === 2 ? k.x + k.w - .11 : k.x + k.w / 2}
        y={row === 0 ? k.y + .3 : row === 1 ? k.y + k.h / 2 + .08 : k.y + k.h - .1}
        fill={colors.text} fontWeight="bold" fontSize={Math.min(.25, Math.max(.14, (k.w - .18) / Math.max(2, label.length * .54)))} textAnchor={col === 0 ? 'start' : col === 2 ? 'end' : 'middle'}>{label}</SvgText>;
    })}
    {showBinding && b && <>
      <SvgText x={k.x + k.w / 2} y={k.y + k.h / 2 + .08} fill={colors.text} textAnchor="middle" fontWeight="bold"
        fontSize={Math.min(.28, Math.max(.12, (k.w - .14) / Math.max(2, b.name.length * .55)))}>{b.name}</SvgText>
      {!!m.displayedLayer && !overridden && <SvgText x={k.x + k.w / 2} y={k.y + k.h - .1} fill={colors.muted} textAnchor="middle" fontSize={.13}>継承</SvgText>}
    </>}
  </G>;
}
