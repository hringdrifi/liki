import React from 'react';
import { View, Text, Switch, ScrollView, Pressable, StyleSheet } from 'react-native';
import Slider from '@react-native-community/slider';
import { LikiModel, KEY_PITCH_MIN_MM, KEY_PITCH_MAX_MM, TRACKPAD_PANEL_MIN_PERCENT, TRACKPAD_PANEL_MAX_PERCENT } from './model';
import { colors, hex } from './Keyboard';
export type Choose = (title: string, items: string[]) => Promise<number | null>;
export type Prompt = (title: string, initial: string, validate: (s: string) => boolean) => Promise<string | null>;
export function Button({ title, onPress, disabled = false, align = 'center' }: { title: string; onPress(): void; disabled?: boolean; align?: 'left' | 'center' }) {
  return <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={[styles.button, align === 'left' && { alignItems: 'flex-start', paddingHorizontal: 12 }, disabled && { opacity: .45 }]}><Text style={styles.buttonText}>{title}</Text></Pressable>;
}
export const Section = ({ title }: { title: string }) => <Text style={styles.section}>{title}</Text>;
export const styles = StyleSheet.create({
  text: { color: colors.text, fontSize: 14 }, muted: { color: colors.muted, fontSize: 13 },
  button: { minHeight: 48, justifyContent: 'center', alignItems: 'center', backgroundColor: colors.surface, borderRadius: 6, padding: 8, marginVertical: 3 },
  buttonText: { color: colors.accent, fontSize: 14 }, section: { color: colors.text, fontSize: 20, fontWeight: 'bold', marginTop: 24, marginBottom: 8 },
  subsection: { color: colors.text, fontSize: 16, fontWeight: 'bold', marginTop: 16, marginBottom: 8 },
});

export function Settings({ model: m, onBack, onPrivacy, choose, prompt, run }: {
  model: LikiModel; onBack(): void; onPrivacy(): void; choose: Choose; prompt: Prompt; run(work: () => Promise<any>): void;
}) {
  const toggle = (key: string, title: string, detail: string) => <View key={key} style={{ paddingVertical: 8 }}>
    <View style={{ flexDirection: 'row', alignItems: 'center' }}><Text style={[styles.text, { flex: 1, fontSize: 16 }]}>{title}</Text>
      <Switch accessibilityLabel={title} value={m.flag(key)} onValueChange={v => run(() => m.setSetting(key, v))} trackColor={{ true: colors.accent }} /></View>
    <Text style={styles.muted}>{detail}</Text></View>;
  const slider = (key: string, title: string, min: number, max: number, suffix: string, current = m.num(key) > 0 ? m.num(key) : min) => {
    const value = Math.max(min, Math.min(max, current));
    return <View key={key} style={{ paddingVertical: 8 }}><Text style={styles.text}>{title}: {(key === 'key_pitch_mm' ? current : value).toFixed(1)}{suffix}</Text>
      <Slider accessibilityLabel={title} minimumValue={min} maximumValue={max} step={.1} value={value}
        minimumTrackTintColor={colors.accent} onValueChange={v => run(() => m.setSetting(key, v))} /></View>;
  };
  const choice = (key: string, title: string, items: string[]) => <Button key={key} title={`${title}: ${items[m.num(key)]}`} onPress={() => run(async () => {
    const i = await choose(title, items); if (i !== null) await m.setSetting(key, i);
  })} />;
  const border = async (layer: number | 'shift') => {
    const title = layer === 'shift' ? 'Shift有効時の枠色' : `レイヤー ${layer} の枠色`;
    const current = layer === 'shift' ? m.shiftBorderColor : m.settings.border_colors[layer];
    const entries = [['グレー', 0x657789], ['青', 0x71a9e8], ['緑', 0x80cbc4], ['黄', 0xedb369], ['赤', 0xe87c73], ['紫', 0xbe9ce7]] as const;
    const i = await choose(title, [...entries.map(([name, color]) => `${name}  ${hex(color)}`), 'カスタム色を入力…']);
    if (i === null) return;
    let color: number;
    if (i < entries.length) color = entries[i][1];
    else {
      const value = await prompt(`${title}（#RRGGBB）`, hex(current), s => /^#[0-9a-fA-F]{6}$/.test(s));
      if (value === null) return; color = parseInt(value.slice(1), 16);
    }
    await m.setSetting(layer === 'shift' ? 'shift_border_color' : `layer_border_color_${layer}`, color | 0xff000000);
  };
  const pitch = m.geometry ? m.geometry.scale * m.density * 25.4 / m.xdpi : 6;
  return <ScrollView contentContainerStyle={{ padding: 20, paddingBottom: 28 }}>
    <Button title="‹  操作画面に戻る" onPress={onBack} /><Text style={[styles.text, { fontSize: 28, fontWeight: 'bold', marginTop: 20 }]}>設定</Text>
    <Text style={styles.muted}>変更はすぐに保存されます。</Text>
    <Section title="画面" />{choice('screen_orientation', '画面の向き', ['自動（端末の設定に従う）', '縦向き', '横向き', '横向き（反対向き）'])}
    {toggle('fullscreen_controls', '全画面表示', '接続後の操作画面でシステムバーを隠す。画面端からスワイプすると一時表示')}
    {toggle('keep_screen_on', '自動消灯を防ぐ', 'Likiを開いている間は自動消灯しない')}
    <Section title="状態表示と枠色" />
    <View style={{ paddingVertical: 8 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center' }}><Text style={[styles.text, { flex: 1, fontSize: 16 }]}>インジケーターを表示</Text>
        <Switch accessibilityLabel="インジケーターを表示" value={m.indicatorsVisible}
          onValueChange={value => run(() => m.setIndicatorsVisible(value))} trackColor={{ true: colors.accent }} /></View>
      <Text style={styles.muted}>Caps Lock・Num Lock・Scroll Lockなどの状態を表示</Text>
    </View>
    <Text style={styles.subsection}>レイヤーとShiftの枠色</Text>
    {[0, 1, 2, 3].map(layer => <View key={layer} style={{ flexDirection: 'row', alignItems: 'center' }}>
      <View style={{ backgroundColor: hex(m.settings.border_colors[layer]), width: 18, height: 18, borderRadius: 9, marginRight: 10 }} />
      <View style={{ flex: 1 }}><Button title={`レイヤー ${layer} の枠色  ${hex(m.settings.border_colors[layer]).toUpperCase()}`} onPress={() => run(() => border(layer))} /></View></View>)}
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      <View style={{ backgroundColor: hex(m.shiftBorderColor), width: 18, height: 18, borderRadius: 9, marginRight: 10 }} />
      <View style={{ flex: 1 }}><Button title={`Shift有効時の枠色  ${hex(m.shiftBorderColor).toUpperCase()}`} onPress={() => run(() => border('shift'))} /></View>
    </View>
    <Text style={styles.muted}>Shift有効時は、すべてのキーとトラックパッドの枠をこの色で表示します。レイヤーの枠色より優先されます。</Text>
    <Section title="振動" />{toggle('touch_vibration', 'タッチ時に振動', '指を置いた時に振動します。指を離した時は振動しません')}
    {choice('touch_vibration_strength', '指を置いた時の振動', ['弱', '標準', '強'])}
    <Section title="仮想キーボード" />
    {slider('key_pitch_mm', 'キーの大きさ（概算）', KEY_PITCH_MIN_MM, KEY_PITCH_MAX_MM, ' mm', pitch)}
    <Text style={styles.muted}>端末の画面密度から換算した概算値です。画面に収めるにはメニューの「表示をリセット」を押します。</Text>
    {toggle('kle_json_key_labels', 'KLEのキーラベルを使う', 'オン: KLEの文字を表示し、空欄ならキー割り当て名を表示。オフ: 有効なキー割り当て名を表示')}
    {toggle('sticky_modifiers', '修飾キーを保持', 'Ctrl・Shiftなどを次のキー入力まで保持')}
    {toggle('pinch_zoom', 'ピンチで拡大縮小', '2本指でキーボードの表示倍率を変更')}
    {toggle('drag_move_keyboard', 'ドラッグでキーボードを移動', '通常キーの上を1本指で動かして表示位置を変更')}
    <Section title="トラックパッド" />
    <Text style={styles.subsection}>領域</Text>
    <View style={{ paddingVertical: 8, flexDirection: 'row', alignItems: 'center' }}><Text style={[styles.text, { flex: 1, fontSize: 16 }]}>独立したトラックパッド領域</Text>
      <Switch accessibilityLabel="独立したトラックパッド領域" value={m.trackpadPanelEnabled}
        onValueChange={value => run(() => m.setTrackpadPanelEnabled(value))} trackColor={{ true: colors.accent }} /></View>
    <Button title={`トラックパッドの位置: ${{ bottom: '下', top: '上', left: '左', right: '右' }[m.trackpadPanelPosition]}`} onPress={() => run(async () => {
      const positions = ['bottom', 'top', 'left', 'right'] as const;
      const index = await choose('トラックパッドの位置', ['下', '上', '左', '右']);
      if (index !== null) await m.setTrackpadPanelPosition(positions[index]);
    })} />
    <View style={{ paddingVertical: 8 }}><Text style={styles.text}>トラックパッド領域の大きさ: {m.trackpadPanelSize}%</Text>
      <Slider accessibilityLabel="トラックパッド領域の大きさ" minimumValue={TRACKPAD_PANEL_MIN_PERCENT} maximumValue={TRACKPAD_PANEL_MAX_PERCENT}
        step={1} value={m.trackpadPanelSize} minimumTrackTintColor={colors.accent} onValueChange={value => run(() => m.setTrackpadPanelSize(value))} /></View>
    <Text style={styles.subsection}>ポインター</Text>
    {slider('pointer_speed', 'ポインターの速度', .5, 3, '倍')}
    {choice('pointer_send_mode', 'カーソルの送信間隔', ['即時（毎回送信）', '16ms（送信多め）', '32ms（標準）'])}
    <Text style={styles.muted}>同じ速さのまま、カーソル移動の送信頻度を変えられます。</Text>
    <Text style={styles.subsection}>クリック・ドラッグ</Text>
    {toggle('tap_to_click', 'タップでクリック', '1本指でタップして左クリック')}
    {toggle('tap_drag', 'ダブルタップでドラッグ', 'タップでクリックを有効にして、2回目のタップ後に指を動かす')}
    {toggle('hold_to_drag', '長押しでドラッグ', '1本指で押し続けてドラッグ')}
    {toggle('two_finger_right_click', '2本指タップで右クリック', '動かさずに2本指でタップ')}
    {toggle('three_finger_middle_click', '3本指タップで中クリック', '動かさずに3本指でタップ')}
    <Text style={styles.subsection}>スクロール・ズーム</Text>
    {toggle('two_finger_scroll', '2本指でスクロール', 'トラックパッドを2本指で上下に動かす')}
    {slider('scroll_speed', 'スクロール速度', .5, 3, '倍')}{toggle('reverse_scroll', 'スクロール方向を反転', '2本指で動かしたときのスクロール方向を逆にする')}
    {toggle('trackpad_pinch_zoom', 'ピンチでズーム', '対応するPC画面へCtrl＋ホイールとして送信')}
    <Section title="外部キーボード・マウス" />
    <View style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 8 }}>
      <Text style={[styles.text, { flex: 1, fontSize: 16 }]}>ZMKレイアウトを表示</Text>
      <Switch accessibilityLabel="ZMKレイアウトを表示" value={m.zmkLayoutEnabled} onValueChange={value => run(() => m.setZmkLayoutEnabled(value))} trackColor={{ true: colors.accent }} />
    </View>
    <Text style={styles.muted}>ZMK Studioから取得した配置を表示します。仮想キーボードが有効な間は仮想キーボードを優先します。</Text>
    <Button title={`入力エフェクト: ${{ none: 'なし', matrix: 'マトリックス', ripple: 'リップル' }[m.relayEffect]}`} onPress={() => run(async () => {
      const index = await choose('入力エフェクト', ['なし', 'マトリックス', 'リップル']);
      if (index !== null) await m.setRelayEffect((['none', 'matrix', 'ripple'] as const)[index]);
    })} />
    <Text style={styles.muted}>外部キー入力のエフェクトを、キーボードやトラックパッドの背景に表示します。ZMKレイアウトの表示とは別に選べます。</Text>
    {slider('external_mouse_speed', '外部マウスの速度', .5, 3, '倍')}
    <Section title="このアプリについて" />
    <Button title="プライバシーポリシー" onPress={onPrivacy} />
  </ScrollView>;
}
