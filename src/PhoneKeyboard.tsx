import React, { useRef, useState } from 'react';
import { View, Text, TextInput, Pressable, KeyboardAvoidingView, Platform } from 'react-native';
import { LikiModel } from './model';
import { colors } from './Keyboard';
import { styles } from './Settings';
import { TrackpadPanel } from './TrackpadPanel';
import { textEdit, TEXT_KEYS } from './text-keys';

type SpecialKey = readonly [title: string, label: string, code: number];
const moveKeys: SpecialKey[] = [['Esc', 'Escape', TEXT_KEYS.escape], ['Tab', 'Tab', TEXT_KEYS.tab],
  ['←', '左矢印', TEXT_KEYS.left], ['↓', '下矢印', TEXT_KEYS.down], ['↑', '上矢印', TEXT_KEYS.up], ['→', '右矢印', TEXT_KEYS.right]];
// Keys that drive the PC's IME without first committing the text on the phone.
const imeKeys: SpecialKey[] = [['変換', 'Space（PCのIMEで変換）', TEXT_KEYS.space], ['⌫', 'Backspace', TEXT_KEYS.backspace],
  ['確定', 'Enter（PCのIMEで確定）', TEXT_KEYS.enter]];
const zenkaku: SpecialKey = ['半/全', '半角/全角', TEXT_KEYS.zenkaku];

/** Sends what is typed with the phone's own keyboard to the PC, one edit at a time. */
export function PhoneKeyboard({ model, active, run }: { model: LikiModel; active: boolean; run(work: () => Promise<any>): void }) {
  const input = useRef<React.ComponentRef<typeof TextInput>>(null), sent = useRef(''), [skipped, setSkipped] = useState('');
  // The field only mirrors what is still editable; keys that move the PC's caret start a new buffer.
  const reset = () => { sent.current = ''; input.current?.clear(); };
  const special = (code: number) => { reset(); void model.typeKeys([{ modifiers: 0, code }]); model.haptic(); };
  const changed = (text: string) => {
    const edit = textEdit(sent.current, text, model.textLayout); sent.current = text;
    void model.typeKeys(edit.keys);
    if (edit.skipped.length) setSkipped(edit.skipped.join('')); else if (edit.keys.length) setSkipped('');
  };
  const jis = model.textLayout === 'jis';
  return <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
    <View style={{ padding: 8, gap: 6 }}>
      <TextInput ref={input} accessibilityLabel="PCへ送る文字を入力" autoFocus autoCapitalize="none" autoCorrect={false} spellCheck={false}
        submitBehavior="submit" placeholder="ここをタップしてスマホのキーボードで入力" placeholderTextColor={colors.muted}
        onChangeText={changed} onSubmitEditing={() => special(TEXT_KEYS.enter)}
        onKeyPress={e => { if (e.nativeEvent.key === 'Backspace' && !sent.current) void model.typeKeys([{ modifiers: 0, code: TEXT_KEYS.backspace }]); }}
        style={[styles.text, { fontSize: 18, minHeight: 48, borderWidth: 1, borderColor: colors.accent, borderRadius: 6, paddingHorizontal: 10, backgroundColor: colors.surface }]} />
      {[moveKeys, [...(jis ? [zenkaku] : []), ...imeKeys]].map((keys, row) => <View key={row} style={{ flexDirection: 'row', gap: 4 }}>
        {keys.map(([title, label, code]) =>
          <Pressable key={title} accessibilityRole="button" accessibilityLabel={label} onPress={() => special(code)}
            style={[styles.button, { flex: 1, minHeight: 40, padding: 2, marginVertical: 0 }]}>
            <Text style={[styles.buttonText, { fontSize: 13 }]} numberOfLines={1}>{title}</Text></Pressable>)}
      </View>)}
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <Pressable accessibilityRole="button" accessibilityLabel={`PCのキーボード配列: ${jis ? 'JIS' : 'US'}。タップで切り替え`}
          onPress={() => run(() => model.setTextLayout(jis ? 'us' : 'jis'))} style={[styles.button, { minHeight: 40, paddingHorizontal: 12, marginVertical: 0 }]}>
          <Text style={styles.buttonText}>PCの配列: {jis ? 'JIS' : 'US'}</Text></Pressable>
        <Text style={[styles.muted, { flex: 1 }, !!skipped && { color: '#edb369' }]}>{skipped ? `送信できない文字: ${skipped}` :
          '英数字・記号はそのまま、かなはローマ字で送ります。'}</Text>
      </View>
    </View>
    {!model.trackpadPanelEnabled && <TrackpadPanel model={model} active={active} />}
  </KeyboardAvoidingView>;
}
