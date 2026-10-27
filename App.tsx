import React, { useEffect, useRef, useState } from 'react';
import { View, Text, ScrollView, Pressable, Modal, TextInput, Alert, BackHandler, AppState, ActivityIndicator, Platform } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider, SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { backend, unavailable } from './src/native';
import { LikiModel } from './src/model';
import type { KeyShape, Data } from './src/model';
import { Keyboard, colors } from './src/Keyboard';
import { KeyboardIndicators } from './src/KeyboardIndicators';
import { StudioKeyboard } from './src/StudioKeyboard';
import { TrackpadPanel } from './src/TrackpadPanel';
import { KeyRain } from './src/KeyRain';
import { ExternalKeyEditor } from './src/ExternalKeyEditor';
import { bindingCategories, bindingInCategory } from './src/key-categories';
import { Settings, Button, Section, styles } from './src/Settings';
import type { Choose, Prompt } from './src/Settings';

type Dialog = { title: string; items: string[]; resolve(value: number | null): void } |
  { title: string; text: string; validate(s: string): boolean; resolve(value: string | null): void };

export default function App() { return <SafeAreaProvider><Liki /></SafeAreaProvider>; }
function Liki() {
  const insets = useSafeAreaInsets();
  const model = useRef(new LikiModel(backend)).current, [, render] = useState(0);
  const [menu, setMenu] = useState(false), [dialog, setDialog] = useState<Dialog | null>(null);
  const menuStart = useRef({ x: 0, y: 0 });
  const error = (e: unknown) => Alert.alert('Liki', String(e));
  const run = (work: () => Promise<any>) => { void work().catch(error); };
  model.onError = error;
  useEffect(() => {
    const update = () => render(n => n + 1); model.listeners.add(update);
    if (!unavailable) void model.initialize();
    const app = AppState.addEventListener('change', state => { if (state === 'active' && model.initialized) run(() => model.refresh()); });
    return () => { app.remove(); model.listeners.delete(update); model.dispose(); };
  }, [model]);
  useEffect(() => {
    const back = BackHandler.addEventListener('hardwareBackPress', () => {
      if (menu) setMenu(false); else if (model.editing) run(() => model.endEdit(false));
      else if (model.externalEditing) { try { model.endExternalEdit(); } catch (reason) { error(reason); } }
      else if (model.settingsVisible) { model.settingsVisible = false; model.syncPage(); model.changed(); }
      else return false; return true;
    }); return () => back.remove();
  }, [menu, model]);
  useEffect(() => { model.pauseRelay(menu || !!dialog); }, [menu, dialog, model]);
  const choose: Choose = (title, items) => new Promise(resolve => setDialog({ title, items, resolve }));
  const prompt: Prompt = (title, text, validate) => new Promise(resolve => setDialog({ title, text, validate, resolve }));
  const confirm = (title: string): Promise<boolean> => new Promise(resolve => Alert.alert(title, undefined,
    [{ text: 'キャンセル', style: 'cancel', onPress: () => resolve(false) }, { text: '確定', onPress: () => resolve(true) }],
    { cancelable: true, onDismiss: () => resolve(false) }));
  const privacy = () => Alert.alert('プライバシーポリシー', model.privacy);
  const closeDialog = () => { dialog?.resolve(null); setDialog(null); };
  const action = (work: () => Promise<any>) => { setMenu(false); run(work); };
  const saveLayout = async () => {
    const name = await prompt('レイアウトに名前を付けて保存', model.selectedName ?? '', s => s.trim().length > 0 && s.trim().length <= 40);
    if (name === null) return;
    if (!model.saved.some(e => e.name.toLowerCase() === name.toLowerCase()) || await confirm(`「${name}」を上書きしますか？`)) await model.saveLayout(name);
  };
  const chooseLayout = async () => {
    const entries = model.saved, i = await choose('レイアウトを選択', ['標準トラックパッド', '標準キーボード（TKL）', ...entries.map(e => `保存: ${e.name}`),
      'KLE JSONファイルを開く']);
    if (i === null) return;
    if (i < 2) await model.applyLayout(await model.call('asset', { name: i === 0 ? 'ghosted-trackpad.json' : 'default-kle.json' }));
    else if (i < entries.length + 2) await model.restoreLayout(entries[i - 2]);
    else { const value = await model.call('pickFile'); if (value !== null) await model.applyLayout(value); }
  };
  const deleteLayout = async () => {
    const entries = model.saved;
    if (!entries.length) { Alert.alert('Liki', '保存したレイアウトはありません'); return; }
    const at = await choose('保存したレイアウトを削除', entries.map(e => e.name));
    if (at !== null && await confirm(`「${entries[at].name}」を削除しますか？`)) await model.deleteLayout(entries[at].name);
  };
  const modifierChoice = async (key: KeyShape) => {
    const selected: string[] = [], mods = ['LCTL', 'LSFT', 'LALT', 'LGUI', 'RCTL', 'RSFT', 'RALT', 'RGUI'];
    const bases = model.options.filter(b => b.code && !b.modifier && !b.consumer && b.action === 'NONE');
    while (true) {
      const available = mods.filter(s => !selected.includes(s)), at = await choose(`修飾キー付き → ${selected.join('+')}`, available);
      if (at === null) return; selected.push(available[at]); const add = selected.length < 8;
      const base = await choose(`修飾キー付き → ${selected.join('+')} → キー`, [...(add ? ['修飾キーを追加…'] : []), ...bases.map(b => b.name)]);
      if (base === null) return; if (add && base === 0) continue;
      await model.assign(key, selected.reduceRight((inner, mod) => `${mod}(${inner})`, bases[base - (add ? 1 : 0)].name)); return;
    }
  };
  const editBinding = async (key: KeyShape) => {
    const names = [...bindingCategories, model.editLayer ? 'ベースを継承' : '自動割り当て'];
    const i = await choose(`レイヤー${model.editLayer}・「${key.labels.find(s => s) ?? ''}」の割り当て`, names);
    if (i === null) return;
    if (i === 11) {
      const inferred = model.editLayer ? undefined : model.inferred[key.index];
      await model.assign(key, inferred);
      if (!model.editLayer && !inferred) Alert.alert('Liki', 'このキーには自動割り当てできるラベルがありません');
      return;
    }
    if (i === 3) { await modifierChoice(key); return; }
    const options = model.options.filter(b => bindingInCategory(b, i));
    const at = await choose(names[i], options.map(b => b.name)); if (at !== null) await model.assign(key, options[at].name);
  };
  const sharedEffect = model.trackpadPanelEnabled && !model.editing && model.relayMode && !model.showKeyboardEffect;
  const controls = model.relayMode ? <StudioKeyboard model={model} showEffect={!sharedEffect} /> : <View style={{ flex: 1 }}>
    <View style={{ flex: 1, padding: 8 }}>
      {model.editing && <><View style={{ flexDirection: 'row' }}>{[0, 1, 2, 3].map(i => <View style={{ flex: 1, opacity: i === model.editLayer ? 1 : .55 }} key={i}>
        <Button title={`レイヤー ${i}`} onPress={() => { model.editLayer = i; model.changed(); }} /></View>)}</View>
        <View style={{ flexDirection: 'row', gap: 8 }}><View style={{ flex: 1 }}><Button title="キャンセル" onPress={() => run(() => model.endEdit(false))} /></View>
          <View style={{ flex: 1 }}><Button title="保存" onPress={() => run(() => model.endEdit(true))} /></View></View></>}
      {model.geometry && <Keyboard model={model} onEdit={key => run(() => editBinding(key))} />}
    </View>
    {!model.editing && <View pointerEvents="none" style={{ position: 'absolute', top: 12, left: 12, backgroundColor: colors.surface, paddingHorizontal: 8, paddingVertical: 4, borderRadius: 8 }}>
      <Text accessibilityLabel={`現在のレイヤー ${model.layer}`} style={[styles.text, { fontSize: 12, fontWeight: 'bold' }]}>L{model.layer}{model.oneShotReturn !== undefined ? '・1回' : ''}</Text></View>}
  </View>;
  const controlScreen = model.trackpadPanelEnabled && !model.editing ? <View style={{ flex: 1 }}>
    {sharedEffect && <View pointerEvents="none" style={{ position: 'absolute', top: 0, right: 0, bottom: 0, left: 0 }}>
      <KeyRain key={model.relayEffect} model={model} />
    </View>}
    <View style={{ flex: 1,
      flexDirection: ({ bottom: 'column', top: 'column-reverse', left: 'row-reverse', right: 'row' } as const)[model.trackpadPanelPosition] }}>
      <View style={{ flex: 100 - model.trackpadPanelSize, display: model.trackpadPanelSize === 100 ? 'none' : 'flex' }}>{controls}</View>
      <View style={{ flex: model.trackpadPanelSize }}><TrackpadPanel model={model} active={!menu && !dialog} /></View>
    </View>
  </View> : controls;
  const connection = <ScrollView contentContainerStyle={{ padding: 20, paddingTop: 24 }}>
    <Text style={[styles.text, { fontSize: 36, fontWeight: 'bold' }]}>Liki</Text><Text style={[styles.buttonText, { fontWeight: 'bold' }]}>Pocket HID</Text>
    <Text style={styles.text}>スマホをPCのキーボード・トラックパッドに</Text>
    {Platform.OS === 'android' && model.status.transports?.includes('classic') && <>
      <Section title="接続方式" />
      <View style={{ flexDirection: 'row', gap: 8 }}>{(['ble', 'classic'] as const).map(transport =>
        <Pressable key={transport} accessibilityRole="radio" accessibilityState={{ checked: model.transport === transport, disabled: !!model.status.connecting || !!model.status.cancelling }}
          disabled={!!model.status.connecting || !!model.status.cancelling} onPress={() => { if (model.transport !== transport) run(() => model.selectTransport(transport)); }}
          style={[styles.button, { flex: 1 }, model.transport === transport && { backgroundColor: '#24736d' }]}>
          <Text style={[styles.buttonText, { color: model.transport === transport ? colors.text : colors.accent }]}>{transport === 'ble' ? 'Bluetooth LE (BLE)' : 'Bluetooth Classic'}</Text>
        </Pressable>)}</View>
    </>}
    <Text style={[styles.text, { color: model.connected ? colors.accent : colors.muted, paddingVertical: 26, fontWeight: 'bold' }]}>{model.connected ? '●' : '○'} {model.status.message ?? ''}</Text>
    <Section title="1. PCとペアリング" /><Text style={styles.text}>下のボタンを押した後、PCのBluetooth設定からこのスマホを追加します。</Text>
    <Button title={model.transport === 'classic' ? 'Classicペアリング待機' : 'BLEペアリング待機'} disabled={model.transport === 'classic' && (!model.status.registered || !!model.status.connecting || !!model.status.cancelling)} onPress={() => run(() => model.call('discoverable'))} />
    <Text style={[styles.muted, { paddingVertical: 12 }]}>{Platform.OS === 'ios' ? 'PC側から「Liki」に接続してください。iOSではPCの一覧取得やアプリ側からの接続はできません。' : model.transport === 'classic' ? '初回はPCのBluetooth設定からこのスマホを追加してください。' : 'PCのBluetooth設定から、このスマホのBluetooth名を選んで接続してください。BLE HID送信は実験中です。'}</Text>
    {model.transport === 'classic' && <><Section title="2. 接続先のPCを選択" />
      {model.devices.length ? model.devices.map(device => <Pressable key={device.address} accessibilityRole="radio"
        accessibilityState={{ checked: model.selectedAddress === device.address }} disabled={!!model.status.connecting || !!model.status.cancelling}
        onPress={() => { model.selectedAddress = device.address; model.changed(); }}
        style={[styles.button, { alignItems: 'flex-start', paddingHorizontal: 12 }, model.selectedAddress === device.address && { backgroundColor: '#24736d' }]}>
        <Text style={styles.buttonText}>{model.selectedAddress === device.address ? '●' : '○'} {device.name}</Text>
      </Pressable>) : <Text style={styles.muted}>ペアリング済みのPCがありません</Text>}
      <Button title="一覧を更新" onPress={() => run(() => model.refresh())} />
      {model.status.connecting || model.status.cancelling ? <Button title={model.status.cancelling ? 'キャンセル中…' : '接続をキャンセル'}
        disabled={!!model.status.cancelling} onPress={() => run(() => model.call('cancelConnection'))} /> :
        <Button title="選択したPCに接続" disabled={!model.selectedAddress || !model.status.registered}
          onPress={() => run(() => model.call('connect', { address: model.selectedAddress }))} />}
    </>}
    <Text style={[styles.muted, { paddingVertical: 12 }]}>接続すると操作画面へ切り替わります。PCから自動接続される場合もあります。</Text><Button title="プライバシーポリシー" onPress={privacy} />
  </ScrollView>;
  return <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
    {Platform.OS === 'ios' && <StatusBar style="light" hidden={model.showingControls && !model.settingsVisible && model.flag('fullscreen_controls')} />}
    {unavailable ? <View style={{ padding: 20 }}><Text style={styles.text}>{unavailable}</Text></View> : model.error ? <View style={{ padding: 20 }}><Text style={styles.text}>初期化できません: {model.error}</Text>
      <Button title="再試行" onPress={() => run(() => model.initialize())} /></View> : !model.initialized ? <ActivityIndicator color={colors.accent} /> :
      model.settingsVisible ? <Settings model={model} onBack={() => { model.settingsVisible = false; model.syncPage(); model.changed(); }} onPrivacy={privacy} choose={choose} prompt={prompt} run={run} /> : model.showingControls ? model.externalEditing ? <ExternalKeyEditor model={model} /> : <View style={{ flex: 1 }}>
        {!model.editing && model.indicatorsVisible && <KeyboardIndicators model={model} />}{controlScreen}
      </View> : connection}
    {model.showingControls && !model.settingsVisible && !model.editing && !model.externalEditing && <View
      style={{ position: 'absolute', right: 0, top: '50%', marginTop: -54, height: 108, width: 24 + insets.right, justifyContent: 'center', alignItems: 'flex-end' }}
      accessibilityLabel="右端中央から左へスワイプしてメニューを開く"
      onTouchStart={e => { menuStart.current = { x: e.nativeEvent.pageX, y: e.nativeEvent.pageY }; }}
      onTouchMove={e => { const dx = e.nativeEvent.pageX - menuStart.current.x, dy = e.nativeEvent.pageY - menuStart.current.y; if (dx < -42 && Math.abs(dx) > Math.abs(dy) * 1.25) setMenu(true); }}>
      <View style={{ backgroundColor: colors.accent, width: 3, height: 48, borderRadius: 2, marginRight: insets.right + 2 }} />
    </View>}
    {menu && model.showingControls && <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, flexDirection: 'row' }}>
      <Pressable accessibilityLabel="メニューを閉じる" style={{ flex: 1, backgroundColor: '#00000096' }} onPress={() => setMenu(false)} />
      <SafeAreaView edges={['top', 'right', 'bottom']} style={{ width: '80%', maxWidth: 300, backgroundColor: colors.surface }}>
      <ScrollView contentContainerStyle={{ padding: 12 }}>
        <Text style={[styles.text, { fontSize: 24, fontWeight: 'bold', padding: 12 }]}>Liki</Text>
        <Section title="入力方法" />
        {([[true, '外部キーボード・マウス'], [false, '仮想キーボード・タッチパッド']] as const).filter(([relay]) => !relay || model.status.relaySupported !== false).map(([relay, title]) =>
          <Pressable key={title} accessibilityRole="radio" accessibilityLabel={title} accessibilityState={{ checked: model.relayMode === relay }}
            onPress={() => { if (model.relayMode === relay) setMenu(false); else action(() => model.setRelayMode(relay)); }}
            style={[styles.button, { alignItems: 'flex-start', paddingHorizontal: 12 }, model.relayMode === relay && { backgroundColor: '#24736d' }]}>
            <Text style={[styles.buttonText, { color: model.relayMode === relay ? colors.text : colors.accent }]}>{model.relayMode === relay ? '●' : '○'} {title}</Text>
          </Pressable>)}
        {model.relayMode ? <><Section title="外部機器" />
          <Text style={[styles.text, { padding: 12 }]}>接続された入力デバイス</Text>
          {model.status.keyboards?.length ? model.status.keyboards.map((device: { id: string | number; name: string }) =>
            <Text style={[styles.text, { paddingHorizontal: 12, paddingBottom: 8 }]} key={device.id}>● {device.name}</Text>) :
            <Text style={[styles.muted, { paddingHorizontal: 12, paddingBottom: 8 }]}>接続された入力デバイスはありません</Text>}
          {model.isZmkKeyboard && !!model.studio.message && <Text style={[styles.muted, { padding: 12 }]}>{model.studio.message}</Text>}
          <Text style={[styles.muted, { padding: 12 }]}>ZMKレイヤー: {model.studio.activeLayer === undefined ? model.zmkLayerMessage || '未取得' : `L${model.studio.activeLayer}`}</Text>
          <Button align="left" title="ZMKレイヤー連携を再接続" onPress={() => action(() => model.call('zmkLayerRetry'))} />
          <Button align="left" title="キー割り当てを編集" onPress={() => { try { model.beginExternalEdit(); setMenu(false); } catch (reason) { error(reason); } }} />
          <Button align="left" title={`配列変換: ${{ none: '変換なし', 'us-jis': 'US → JIS', 'jis-us': 'JIS → US' }[model.relayLayout]}`} onPress={() => action(async () => {
            const index = await choose('配列変換（外部キーボード → 接続先の配列）', ['変換なし', 'US → JIS', 'JIS → US']);
            if (index !== null) await model.setRelayLayout(['none', 'us-jis', 'jis-us'][index]);
          })} />
          {model.isZmkKeyboard && <Button align="left" title="ZMKレイアウトを再取得" onPress={() => action(() => model.call('studioRetry'))} />}</> : <>
          <Section title="レイアウト" />
          {model.selectedName && <Text style={styles.text}>保存: {model.selectedName}</Text>}
          <Button align="left" title="レイアウトを選択" onPress={() => action(chooseLayout)} />
          <Button align="left" title="キー割り当てを編集" onPress={() => { setMenu(false); model.beginEdit(); }} />
          <Button align="left" title="現在のレイアウトを保存" onPress={() => action(saveLayout)} />
          <Button align="left" title="保存したレイアウトを削除" disabled={!model.saved.length} onPress={() => action(deleteLayout)} />
        </>}
        <Button align="left" title="表示をリセット" onPress={() => action(() => model.resetView())} />
        <Section title="共通" />
        <Button align="left" title="設定" onPress={() => { setMenu(false); model.settingsVisible = true; model.syncPage(); model.changed(); }} />
        <Button align="left" title="PCとの接続を切断" onPress={() => action(() => model.call('disconnect'))} /></ScrollView></SafeAreaView></View>}
    <Modal visible={!!dialog} transparent animationType="fade" onRequestClose={closeDialog}>
      <View style={{ flex: 1, justifyContent: 'center', backgroundColor: '#00000099', padding: 24 }}><View style={{ backgroundColor: colors.surface, maxHeight: '85%', borderRadius: 12, padding: 16 }}>
        <Text style={[styles.text, { fontWeight: 'bold', fontSize: 20, marginBottom: 12 }]}>{dialog?.title}</Text>
        {dialog && 'items' in dialog ? <ScrollView>{dialog.items.map((s, i) => <Button key={i} title={s} onPress={() => { setDialog(null); dialog.resolve(i); }} />)}</ScrollView> :
          dialog && 'text' in dialog ? <><TextInput autoFocus value={dialog.text} onChangeText={text => setDialog({ ...dialog, text })} style={[styles.text, { borderWidth: 1, borderColor: colors.muted, padding: 10 }]} />
            <Button title="保存" disabled={!dialog.validate(dialog.text.trim())} onPress={() => { setDialog(null); dialog.resolve(dialog.text.trim()); }} /></> : null}
        <Button title="閉じる" onPress={closeDialog} /></View></View>
    </Modal>
  </SafeAreaView>;
}
