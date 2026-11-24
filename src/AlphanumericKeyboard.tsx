import React, { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors } from './Keyboard';
import type { LikiModel } from './model';

const letters = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];

export function AlphanumericKeyboard({ model, active, onKana, onSymbols }: {
  model: LikiModel; active: boolean; onKana(): void; onSymbols(): void;
}) {
  const [shift, setShift] = useState(false);
  const caps = ((model.keyboardLeds ?? 0) & 2) !== 0;
  const send = (code: number, modifiers = 0) => {
    model.sendVirtualKeys([{ code, modifiers }]);
    setShift(false);
  };
  const key = (label: string, onPress: () => void, accessibilityLabel = label, width = 1, selected = false) =>
    <Pressable key={accessibilityLabel} accessibilityRole="button" accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled: !active, selected }} disabled={!active} onPressIn={() => model.haptic()} onPress={onPress}
      style={({ pressed }) => [styles.key, { flex: width }, (selected || pressed) && styles.selected]}>
      <Text adjustsFontSizeToFit numberOfLines={1} style={styles.label}>{label}</Text>
    </Pressable>;

  return <View style={styles.panel}>
    <View style={styles.row}>{Array.from('1234567890', (digit, i) => key(digit, () => send(0x1e + i)))}</View>
    {letters.map(row => <View key={row} style={styles.row}>
      {row === 'zxcvbnm' && key('⇧', () => setShift(!shift), 'Shift', 1.5, shift)}
      {Array.from(row, letter => key(shift !== caps ? letter.toUpperCase() : letter,
        () => send(letter.charCodeAt(0) - 93, shift ? 2 : 0)))}
      {row === 'zxcvbnm' && key('⌫', () => send(0x2a), '削除', 1.5)}
    </View>)}
    <View style={styles.row}>
      {key('かな', onKana, 'かな配列に切り替える', 1.5)}
      {key('記号', onSymbols, '記号を選ぶ', 1.5)}
      {key(',', () => send(0x36))}
      {key('空白', () => send(0x2c), 'Space', 4)}
      {key('.', () => send(0x37))}
      {key('改行', () => send(0x28), 'Enter', 2)}
    </View>
  </View>;
}

const styles = StyleSheet.create({
  panel: { flex: 1, justifyContent: 'flex-end', gap: 4, padding: 8, width: '100%', maxWidth: 640, alignSelf: 'center' },
  row: { flex: 1, flexDirection: 'row', gap: 4, maxHeight: 64 },
  key: { borderRadius: 6, backgroundColor: colors.surface, alignItems: 'center', justifyContent: 'center' },
  selected: { backgroundColor: '#24736d' },
  label: { color: colors.text, fontSize: 18 },
});
