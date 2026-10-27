import React from 'react';
import { Text, View } from 'react-native';
import type { LikiModel } from './model';
import { colors } from './Keyboard';

export function KeyboardIndicators({ model }: { model: LikiModel }) {
  return <View pointerEvents="none" style={{ paddingHorizontal: 12, paddingVertical: 4 }}>
    <Text accessibilityLabel={`ZMKの現在レイヤー ${model.studio.activeLayer}`}
      style={{ color: colors.text, paddingHorizontal: 8, paddingVertical: 4, fontSize: 12, fontWeight: 'bold' }}>L{model.studio.activeLayer}</Text>
  </View>;
}
