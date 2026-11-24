import React, { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { GestureResponderEvent, TextStyle } from 'react-native';
import { colors } from './Keyboard';
import { directions, flickDirection, kanaActionKeys, kanaPunctuationKey, kanaRows } from './kana-flick';
import type { FlickDirection, KanaVariantAction } from './kana-flick';
import type { LikiModel } from './model';

const variantKeys: Record<string, KanaVariantAction> = { '゛': 'cycle', '゜': 'semiVoiced', 小: 'small' };
const positions: Record<FlickDirection, TextStyle> = {
  center: { fontSize: 24, fontWeight: 'bold' }, left: { position: 'absolute', left: 7 },
  up: { position: 'absolute', top: 3 }, right: { position: 'absolute', right: 7 },
  down: { position: 'absolute', bottom: 3 },
};

export function KanaKeyboard({ model, active, onSymbols, onAlphanumeric }: {
  model: LikiModel; active: boolean; onSymbols(): void; onAlphanumeric(): void;
}) {
  const [replacement, setReplacement] = useState<string>();
  const [showSpace, setShowSpace] = useState(false);
  const [preview, setPreview] = useState<{ key: string; direction: FlickDirection }>();
  const touch = useRef<{ key: string; x: number; y: number; id: number } | undefined>(undefined);
  const reset = () => { touch.current = undefined; setPreview(undefined); };
  useEffect(() => () => model.forgetLastKana(), [model]);

  const select = (character: string) => {
    if (!active || !character.trim()) return;
    if (Object.hasOwn(variantKeys, character)) {
      setReplacement(model.changeLastKana(variantKeys[character]));
      return;
    }
    setShowSpace(false);
    if (character === kanaPunctuationKey[0]) {
      setReplacement(model.tapKanaPunctuation());
    } else {
      model.sendKana(character);
      setReplacement(undefined);
    }
  };
  const directionFor = (event: GestureResponderEvent, key: string) => {
    const start = touch.current;
    const point = event.nativeEvent;
    if (start?.key === key && start.id === point.identifier) return flickDirection(point.pageX - start.x, point.pageY - start.y);
  };
  const special = (key: string) => {
    if (!active) return;
    setReplacement(undefined);
    model.haptic();
    if (key === '記号') onSymbols();
    else if (key === '英数') onAlphanumeric();
    else {
      model.sendVirtualKeys([{ code: kanaActionKeys[key], modifiers: 0 }]);
      if (key === '確定') setShowSpace(true);
    }
  };

  return <View style={styles.panel}>
    <View style={[styles.variantStatus, replacement && styles.variantSelected]}>
      <Text accessibilityLiveRegion="polite" numberOfLines={1} adjustsFontSizeToFit style={styles.variant}>
        {replacement ?? ''}
      </Text>
    </View>
    {kanaRows.map(row => <View key={row[1]} style={[styles.row, preview && row.includes(preview.key) && styles.raised]}>
      {row.map(key => {
        const isSpace = key === '変換' && showSpace;
        if (key === '記号' || key === '英数' || Object.hasOwn(kanaActionKeys, key)) return <Pressable key={key} accessibilityRole="button" accessibilityLabel={isSpace ? 'スペース' : key} disabled={!active}
          onPress={() => special(key)} style={({ pressed }) => [styles.key, pressed && styles.keyActive]}>
          <Text style={styles.action} numberOfLines={1} adjustsFontSizeToFit>{isSpace ? '␣' : key === '左' ? '←' : key === '右' ? '→' : key}</Text>
        </Pressable>;
        const characters = Array.from(key);
        const isVariantKey = key === '゛゜小';
        const activePreview = preview?.key === key ? preview : undefined;
        return <View key={key} style={[styles.key, activePreview && [styles.keyActive, styles.raised]]}
          accessible accessibilityRole="button" accessibilityState={{ disabled: !active }}
          accessibilityLabel={isVariantKey ? '直前のかなを濁音、半濁音、小文字に変更' : characters[0]}
          accessibilityActions={directions.filter((_, i) => characters[i]?.trim()).map(name => ({ name: name === 'center' ? 'activate' : name,
            label: isVariantKey && name === 'center' ? '直前のかなの形を切り替え' : characters[directions.indexOf(name)] }))}
          onAccessibilityAction={event => {
            const name = event.nativeEvent.actionName;
            select(characters[name === 'activate' ? 0 : directions.indexOf(name as FlickDirection)] ?? '');
          }}
          onStartShouldSetResponder={() => active}
          onTouchStart={event => { if (event.nativeEvent.touches.length > 1) reset(); }}
          onResponderGrant={event => {
            if (event.nativeEvent.touches.length > 1) return;
            const { pageX, pageY, identifier } = event.nativeEvent;
            touch.current = { key, x: pageX, y: pageY, id: identifier };
            setPreview({ key, direction: 'center' });
            model.haptic();
          }}
          onResponderMove={event => {
            if (event.nativeEvent.touches.length > 1) { reset(); return; }
            const direction = directionFor(event, key);
            if (direction) setPreview({ key, direction });
          }}
          onResponderRelease={event => {
            const direction = directionFor(event, key);
            reset();
            if (direction) select(characters[directions.indexOf(direction)] ?? '');
          }} onResponderTerminate={reset}>
          <Text accessible={false} numberOfLines={1} adjustsFontSizeToFit style={styles.keyLabel}>
            {isVariantKey ? key : characters[0]}
          </Text>
          {activePreview && <View pointerEvents="none" accessibilityElementsHidden importantForAccessibility="no-hide-descendants"
            style={styles.popup}>
            {characters.map((label, i) => label.trim() ? <Text key={directions[i]} style={[styles.label,
              positions[directions[i]], activePreview.direction === directions[i] && styles.highlight]}>
              {label}
            </Text> : null)}
          </View>}
        </View>;
      })}
    </View>)}
  </View>;
}

const styles = StyleSheet.create({
  panel: { flex: 1, justifyContent: 'flex-end', gap: 4, padding: 8, width: '100%', maxWidth: 520, alignSelf: 'center' },
  row: { flex: 1, flexDirection: 'row', gap: 4, maxHeight: 88 },
  key: { flex: 1, borderRadius: 6, backgroundColor: colors.surface, borderWidth: 1, borderColor: 'transparent', alignItems: 'center', justifyContent: 'center' },
  keyActive: { backgroundColor: '#24736d' },
  raised: { zIndex: 1 },
  keyLabel: { color: colors.text, fontSize: 23, fontWeight: 'bold', maxWidth: '92%' },
  popup: { position: 'absolute', bottom: '100%', marginBottom: 4, width: 78, height: 72, borderRadius: 8,
    borderWidth: 1, borderColor: colors.accent, backgroundColor: colors.background, alignItems: 'center', justifyContent: 'center' },
  label: { color: colors.text, fontSize: 15 }, highlight: { color: colors.accent, fontWeight: 'bold' },
  variantStatus: { height: 30, justifyContent: 'center', borderRadius: 6, borderWidth: 1, borderColor: 'transparent', paddingHorizontal: 8 },
  variantSelected: { backgroundColor: '#26485b', borderColor: colors.accent },
  variant: { color: colors.text, fontSize: 14, fontWeight: 'bold', textAlign: 'center' },
  action: { color: colors.accent, fontSize: 14, maxWidth: '92%' },
});
