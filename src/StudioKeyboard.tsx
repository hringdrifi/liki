import React, { useMemo, useReducer, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import Svg, { G, Rect, Text as SvgText } from 'react-native-svg';
import { Geometry } from './model';
import type { LikiModel } from './model';
import { colors } from './Keyboard';
import { studioKeyActive } from './zmk';

export function StudioKeyboard({ model }: { model: LikiModel }) {
  const client = model.studio, layout = client.layout;
  const [viewport, setViewport] = useState({ width: 0, height: 0 });
  const geometry = useMemo(() => {
    if (!layout) return undefined;
    const g = new Geometry(layout.keys);
    g.width = viewport.width; g.height = viewport.height;
    g.base = Math.max(.01, Math.min((g.width - 12) / Math.max(1, g.right - g.left), (g.height - 12) / Math.max(1, g.bottom - g.top)));
    return g;
  }, [layout, viewport]);
  const [, redraw] = useReducer(n => n + 1, 0);
  const layer = layout?.layers[client.layer];
  return <View style={{ flex: 1, padding: 12, gap: 8 }}>
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
      <Text style={{ color: colors.text, flex: 1, fontWeight: 'bold' }}>ZMK Studio{layout ? ` · ${layout.name}` : ''}</Text>
      <Pressable accessibilityRole="button" onPress={() => model.send('studioRetry')} style={{ padding: 10, backgroundColor: colors.surface, borderRadius: 8 }}>
        <Text style={{ color: colors.accent }}>再取得</Text>
      </Pressable>
    </View>
    {!!client.message && <Text style={{ color: colors.muted }}>{client.message}</Text>}
    {layout && geometry && layer && <>
      <ScrollView horizontal style={{ flexGrow: 0 }} contentContainerStyle={{ gap: 8 }}>
        {layout.layers.map((layer, i) => <Pressable key={i} accessibilityRole="button" accessibilityState={{ selected: client.layer === i }}
          onPress={() => { client.layer = i; redraw(); }} style={{ padding: 10, backgroundColor: client.layer === i ? '#24736d' : colors.surface, borderRadius: 6 }}>
          <Text style={{ color: colors.text }}>{layer.name}</Text>
        </Pressable>)}
      </ScrollView>
      <View style={{ flex: 1, minHeight: 120 }} accessibilityLabel="ZMKキーボードのレイアウトと受信キーの表示"
        onLayout={e => setViewport({ width: e.nativeEvent.layout.width, height: e.nativeEvent.layout.height })}>
        <Svg width="100%" height="100%">
          <G transform={`translate(${geometry.origin.x},${geometry.origin.y}) scale(${geometry.scale})`}>
            {layout.keys.map(key => {
              const binding = layer.keys[key.index], active = studioKeyActive(binding, model.relayPressed, model.relayConsumer);
              const lines = binding.label.split(' / '), size = Math.min(.22, (key.w - .12) / Math.max(2, ...lines.map(l => l.length * .6)));
              return <G key={key.index} transform={`rotate(${key.rotation},${key.rx},${key.ry})`}>
                <Rect x={key.x + .025} y={key.y + .025} width={key.w - .05} height={key.h - .05} rx={.08}
                  fill={active ? '#24736d' : colors.surface} stroke={active ? colors.accent : '#657789'} strokeWidth={.025} />
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
