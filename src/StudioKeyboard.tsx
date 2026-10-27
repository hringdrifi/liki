import React, { useEffect, useLayoutEffect, useMemo, useReducer } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import type { GestureResponderEvent } from 'react-native';
import Svg, { G, Rect, Text as SvgText } from 'react-native-svg';
import { Geometry } from './model';
import type { LikiModel } from './model';
import { colors, hex } from './Keyboard';
import { studioKeyActive } from './zmk';
import { StudioKeyboardGesture } from './studio-gesture';
import type { Touch } from './gestures';

export function StudioKeyboard({ model, selectedKey, onSelectKey, onSelectLayer }: {
  model: LikiModel; selectedKey?: number; onSelectKey?(index: number): void; onSelectLayer?(): void;
}) {
  const client = model.studio, layout = client.layout;
  const geometry = useMemo(() => layout ? new Geometry(layout.keys) : undefined, [layout]);
  const gesture = useMemo(() => geometry ? new StudioKeyboardGesture(model, geometry) : undefined, [model, geometry]);
  useLayoutEffect(() => gesture?.setSelector(onSelectKey), [gesture, onSelectKey]);
  useEffect(() => () => gesture?.cancel(), [gesture]);
  const [, redraw] = useReducer(n => n + 1, 0);
  useLayoutEffect(() => {
    if (!gesture) return;
    const reset = () => { gesture.reset(); redraw(); };
    return model.registerStudioViewReset(reset);
  }, [model, gesture]);
  const touch = (kind: Touch['kind'], event: GestureResponderEvent) => {
    if (!gesture) return;
    const { changedTouches, touches } = event.nativeEvent;
    if (kind === 'move' || kind === 'up') for (const point of touches) gesture.points.set(point.identifier, { x: point.locationX, y: point.locationY });
    for (const point of changedTouches) gesture.touch({ kind, id: point.identifier, position: { x: point.locationX, y: point.locationY }, time: point.timestamp });
    if (kind === 'cancel') gesture.cancel();
    redraw();
  };
  const displayLayer = onSelectKey ? client.layer : client.displayLayer;
  const layer = layout?.layers[displayLayer];
  if (!layout || !onSelectKey && !model.showZmkLayout) return <View style={{ flex: 1 }}>
    {model.isZmkKeyboard && !!client.message && <Text style={{ color: colors.text, padding: 12 }}>{client.message}</Text>}
  </View>;
  return <View style={{ flex: 1, padding: 12, gap: 8 }}>
    {layout && geometry && layer && <>
      {client.activeLayer !== undefined && !onSelectKey ? <Text style={{ color: colors.text }}>現在のレイヤー: {layer.name}</Text> : <ScrollView horizontal style={{ flexGrow: 0 }} contentContainerStyle={{ gap: 8 }}>
        {layout.layers.map((layer, i) => <Pressable key={i} accessibilityRole="button" accessibilityState={{ selected: client.layer === i }}
          onPress={() => { client.layer = i; redraw(); onSelectLayer?.(); }} style={{ padding: 10, backgroundColor: client.layer === i ? '#24736d' : colors.surface, borderRadius: 6 }}>
          <Text style={{ color: colors.text }}>{layer.name}</Text>
        </Pressable>)}
      </ScrollView>}
      <View style={{ flex: 1, minHeight: 120, overflow: 'hidden' }} accessibilityLabel="ZMKキーボード。ドラッグで移動、ピンチで拡大縮小、編集時はタップでキーを選択"
        onLayout={e => {
          gesture?.setViewport(e.nativeEvent.layout.width, e.nativeEvent.layout.height);
          redraw();
        }}
        onStartShouldSetResponder={() => true} onMoveShouldSetResponder={() => true} onResponderTerminationRequest={() => false}
        onTouchStart={e => touch('down', e)} onTouchMove={e => touch('move', e)} onTouchEnd={e => touch('up', e)} onTouchCancel={e => touch('cancel', e)}
        onResponderTerminate={() => gesture?.cancel()}>
        <Svg width="100%" height="100%" pointerEvents="none">
          <G transform={`translate(${geometry.origin.x},${geometry.origin.y}) scale(${geometry.scale})`}>
            {layout.keys.map(key => {
              const binding = layer.keys[key.index], active = selectedKey === key.index || studioKeyActive(binding, model.relayPressed, model.relayConsumer);
              const lines = binding.label.split(' / '), size = Math.min(.22, (key.w - .12) / Math.max(2, ...lines.map(l => l.length * .6)));
              return <G key={key.index} transform={`rotate(${key.rotation},${key.rx},${key.ry})`}>
                <Rect x={key.x + .025} y={key.y + .025} width={key.w - .05} height={key.h - .05} rx={.08}
                  fill={active ? '#24736d' : colors.surface} stroke={!onSelectKey && (model.shiftActive || client.activeLayer !== undefined) ? hex(model.borderColorForLayer(client.activeLayer)) : active ? colors.accent : '#657789'} strokeWidth={.025} />
                {lines.map((line, i) => <SvgText key={i} x={key.x + key.w / 2} y={key.y + key.h / 2 + size * (.3 + (i - (lines.length - 1) / 2) * 1.2)}
                  fill={colors.text} fontSize={size} textAnchor="middle">{line}</SvgText>)}
              </G>;
            })}
          </G>
        </Svg>
      </View>
    </>}
  </View>;
}
