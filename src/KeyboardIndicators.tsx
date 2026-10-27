import React from 'react';
import { Text, View } from 'react-native';
import type { LikiModel } from './model';
import { colors } from './Keyboard';

export function KeyboardIndicators({ model }: { model: LikiModel }) {
  const leds = model.keyboardLeds;
  const indicators: { label: string; active: boolean | undefined }[] = [
    ...([['Caps Lock', 2], ['Num Lock', 1], ['Scroll Lock', 4]] as const).map(([label, bit]) =>
      ({ label, active: leds === undefined ? undefined : (leds & bit) !== 0 })),
  ];
  return <View pointerEvents="none" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, paddingHorizontal: 12, paddingVertical: 4 }}>
    {model.relayMode && !model.virtualKeyboardEnabled && model.studio.activeLayer !== undefined && <Text accessibilityLabel={`ZMKの現在レイヤー ${model.studio.activeLayer}`}
      style={{ color: colors.text, paddingHorizontal: 8, paddingVertical: 4, fontSize: 12, fontWeight: 'bold' }}>L{model.studio.activeLayer}</Text>}
    {indicators.map(({ label, active }) => <View key={label} accessibilityLabel={`${label}: ${active === undefined ? '不明' : active ? 'オン' : 'オフ'}`}
      style={{ backgroundColor: active ? '#24736d' : colors.surface, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 4 }}>
      <Text style={{ color: active ? colors.text : colors.muted, fontSize: 12, fontWeight: 'bold' }}>{active === undefined ? '?' : active ? '●' : '○'} {label}</Text>
    </View>)}
  </View>;
}
