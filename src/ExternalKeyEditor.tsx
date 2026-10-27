import React, { useEffect, useRef, useState } from 'react';
import { Alert, Modal, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import type { LikiModel } from './model';
import { Button, Section, styles } from './Settings';
import { colors } from './Keyboard';
import { StudioKeyboard } from './StudioKeyboard';
import { studioParameters, usageLabel } from './zmk';
import type { StudioBinding, StudioParameter } from './zmk';
import { bindingUsage, expandUsage, remapLabel, validUsage } from './remap';
import { bindingCategories, bindingInCategory } from './key-categories';

type Choice = { label: string; value: number };
type Picker = { title: string; options: Choice[]; select(value: number): void };
export function ExternalKeyEditor({ model: m }: { model: LikiModel }) {
  const studio = m.studio, zmk = m.externalEditZmk;
  const [picker, setPicker] = useState<Picker>(), [search, setSearch] = useState('');
  const [error, setError] = useState(''), [working, setWorking] = useState(false);
  const [source, setSource] = useState<number>(), [target, setTarget] = useState<number[]>(), [original, setOriginal] = useState<number>();
  const [position, setPosition] = useState<number>(), [draft, setDraft] = useState<StudioBinding>();
  const [parameters, setParameters] = useState(['0', '0']);
  const catalogRequested = useRef(false);
  const busy = working || studio.busy;
  const perform = async (work: () => Promise<unknown>) => {
    setError(''); setWorking(true);
    try { await work(); } catch (reason) { setError(String(reason)); } finally { setWorking(false); }
  };
  useEffect(() => {
    if (!zmk) return;
    if (!studio.ready) { catalogRequested.current = false; return; }
    if (catalogRequested.current) return;
    catalogRequested.current = true;
    void studio.loadBehaviors().catch(reason => setError(String(reason)));
  }, [zmk, studio, studio.ready]);
  useEffect(() => {
    if (zmk) return;
    const listener = m.native.subscribe(event => {
      if (!m.relayLearning || !event.relayInput) return;
      const input = event.relayInput;
      const code = input.pressed.find((usage: number) => usage < 0xe0) ?? input.pressed[0];
      const usage = code !== undefined ? 0x70000 | code : input.consumer ? 0xc0000 | input.consumer : undefined;
      if (usage !== undefined && validUsage(usage)) { setSource(usage); m.learnRelayKey(false); }
    });
    return () => { listener.remove(); if (m.relayLearning) m.learnRelayKey(false); };
  }, [m, zmk]);
  const outputs = [...new Map<number, Choice>(m.options.filter(binding => binding.action === 'NONE' && (binding.code || binding.modifier || binding.consumer))
    .map(binding => { const value = bindingUsage(binding); return [value, { label: binding.name, value }] as const; })).values()];
  const sources = outputs.map(choice => ({ ...choice, usages: expandUsage(choice.value) }))
    .filter(choice => choice.usages.length === 1).map(choice => ({ label: choice.label, value: choice.usages[0] }));
  const openPicker = (title: string, options: Choice[], select: (value: number) => void) => {
    if (m.relayLearning) m.learnRelayKey(false);
    setSearch(''); setPicker({ title, options, select });
  };
  const openKeyPicker = (choices: Choice[], select: (value: number) => void) => {
    const allowed = new Set(choices.map(choice => choice.value));
    const categoryChoices = (category: number) => {
      const values = new Set(m.options.filter(binding => bindingInCategory(binding, category)).map(bindingUsage));
      return choices.filter(choice => values.has(choice.value));
    };
    const bases = m.options.filter(binding => binding.code && !binding.modifier && !binding.consumer && binding.action === 'NONE')
      .map(binding => ({ label: binding.name, value: bindingUsage(binding) })).filter(choice => allowed.has(choice.value));
    const modified = (selected: number[] = []) => {
      const names = ['LCTL', 'LSFT', 'LALT', 'LGUI', 'RCTL', 'RSFT', 'RALT', 'RGUI'];
      openPicker(`修飾キー付き → ${selected.map(index => names[index]).join('+')}`, names.map((label, value) => ({ label, value }))
        .filter(choice => !selected.includes(choice.value)), value => {
        const next = [...selected, value], mask = next.reduce((result, index) => result | (1 << index), 0);
        openPicker(`修飾キー付き → ${next.map(index => names[index]).join('+')} → キー`,
          [...(next.length < 8 ? [{ label: '修飾キーを追加…', value: -1 }] : []), ...bases], key => {
            if (key === -1) modified(next);
            else select(((key & 0xffffff) | (mask << 24)) >>> 0);
          });
      });
    };
    openPicker('割り当てを選択', [...bindingCategories.flatMap((label, value) =>
      (value === 3 ? bases.length : categoryChoices(value).length) ? [{ label, value }] : []),
    { label: 'すべてのキー', value: -1 }], category => {
      if (category === 3) modified();
      else openPicker(category === -1 ? 'すべてのキー' : bindingCategories[category], category === -1 ? choices : categoryChoices(category), select);
    });
  };
  const clearRemap = () => { if (m.relayLearning) m.learnRelayKey(false); setSource(undefined); setTarget(undefined); setOriginal(undefined); };
  const saveRemap = () => perform(async () => {
    if (source === undefined || target === undefined) throw Error('入力キーと送信するキーを選択してください');
    const rules = m.relayRemaps.filter(rule => rule.source !== original);
    if (rules.some(rule => rule.source === source)) throw Error('その入力キーは登録済みです');
    await m.saveRelayRemaps([...rules, { source, target }]); clearRemap();
  });
  const chooseKey = (index: number) => {
    const binding = studio.layout!.layers[studio.layer].bindings[index];
    setPosition(index); setDraft({ ...binding }); setParameters([String(binding.param1), String(binding.param2)]); setError('');
  };
  const behavior = studio.behaviors.find(value => value.id === draft?.behaviorId);
  const layerIds = studio.layout?.layers.map(layer => layer.id) ?? [];
  const numericParameter = (value: string) => {
    if (!value.trim() || !/^-?\d+$/.test(value)) throw Error('パラメーターに整数を入力してください');
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number < -0x80000000 || number > 0xffffffff) throw Error('パラメーターが範囲外です');
    return number >>> 0;
  };
  const parameterChoices = (options: StudioParameter[]): Choice[] => options.flatMap(option => {
    if (option.kind === 'none') return [{ label: option.name || 'なし', value: 0 }];
    if (option.kind === 'constant') return [{ label: option.name || String(option.value), value: option.value! }];
    if (option.kind === 'layer') return studio.layout!.layers.map(layer => ({ label: layer.name, value: layer.id }));
    if (option.kind === 'hid') return outputs.filter(choice => {
      const page = (choice.value >>> 16) & 255, code = choice.value & 0xffff;
      return code > 0 && (page === 7 && code <= option.keyboardMax! || page === 12 && code <= option.consumerMax!);
    });
    return [];
  });
  return <View style={{ flex: 1 }}>
    <View style={{ paddingHorizontal: 16 }}>
      <Button align="left" title="‹ 操作画面に戻る" disabled={busy || studio.dirty} onPress={() => m.endExternalEdit()} />
      <Text style={[styles.text, { fontSize: 24, fontWeight: 'bold' }]}>{zmk ? 'ZMKキー割り当て' : 'キーの読み替え'}</Text>
      {!!error && <Text style={{ color: '#ff9c9c', paddingVertical: 8 }}>{error}</Text>}
    </View>
    {zmk ? <>
      <View style={{ flex: 1 }}>{studio.ready ? <StudioKeyboard model={m} selectedKey={position}
        onSelectKey={busy || studio.dirty ? undefined : chooseKey} onSelectLayer={() => { setPosition(undefined); setDraft(undefined); }} /> :
        <Text style={[styles.muted, { padding: 16 }]}>{studio.message || 'ZMKレイアウトを取得中…'}</Text>}</View>
      <ScrollView style={{ flexGrow: 0, maxHeight: '50%' }} contentContainerStyle={{ padding: 16 }}>
        {studio.dirty ? <><Text style={styles.text}>キーボードに未保存の変更があります</Text>
          <Button title="保存を再試行" disabled={busy} onPress={() => void perform(() => studio.savePending())} />
          <Button title="未保存の変更を破棄" disabled={busy} onPress={() => Alert.alert('変更を破棄', 'キーボードの未保存の変更を破棄しますか？', [
            { text: 'キャンセル', style: 'cancel' }, { text: '破棄', style: 'destructive', onPress: () => void perform(() => studio.discardPending()) },
          ])} /></> : <>
          {!studio.behaviors.length && studio.ready && <Button title={studio.busy ? '割り当て候補を取得中…' : '割り当て候補を再取得'} disabled={busy}
            onPress={() => void perform(() => studio.loadBehaviors())} />}
          {position === undefined ? <Text style={styles.muted}>配置上のキーを選択してください</Text> : <>
            <Text style={styles.text}>{studio.layout?.layers[studio.layer].name} · キー {position + 1}</Text>
            <Button align="left" title={`動作: ${behavior?.name ?? '候補を選択'}`} disabled={busy || !studio.behaviors.length}
              onPress={() => openPicker('動作を選択', studio.behaviors.map(value => ({ label: value.name, value: value.id })), value => {
                setDraft({ behaviorId: value, param1: 0, param2: 0 }); setParameters(['0', '0']);
              })} />
            {behavior && [0, 1].map(index => {
              const options = studioParameters(behavior, index, Number(parameters[0]) >>> 0, layerIds);
              if (!options.length || options.every(option => option.kind === 'none')) return null;
              const choices = parameterChoices(options), current = Number(parameters[index]) >>> 0;
              const label = choices.find(choice => choice.value === current)?.label ?? (options.some(option => option.kind === 'hid') && current ? remapLabel(expandUsage(current)) : parameters[index]);
              return <View key={index}><Text style={styles.text}>{options.find(option => option.name)?.name || `パラメーター ${index + 1}`}</Text>
                {options.some(option => option.kind === 'range') && <TextInput value={parameters[index]} editable={!busy} keyboardType="numbers-and-punctuation"
                  onChangeText={value => setParameters(current => current.map((old, at) => at === index ? value : old))}
                  style={[styles.text, { borderWidth: 1, borderColor: colors.muted, borderRadius: 6, padding: 10 }]} />}
                {!!choices.length && <Button align="left" title={label || '選択'} disabled={busy}
                  onPress={() => {
                    const select = (value: number) => setParameters(current => current.map((old, at) => at === index ? String(value) : old));
                    if (options.some(option => option.kind === 'hid')) openKeyPicker(choices, select);
                    else openPicker('値を選択', choices, select);
                  }} />}
                {options.some(option => option.kind === 'hid') && ((current >>> 16) & 255) === 7 && <Modifiers value={current >>> 24} disabled={busy}
                  onChange={mask => setParameters(values => values.map((old, at) => at === index ? String(((current & 0xffffff) | (mask << 24)) >>> 0) : old))} />}
              </View>;
            })}
            <Button title="キーボードに保存" disabled={busy || !behavior} onPress={() => void perform(async () => {
              await studio.saveBinding(studio.layer, position, { behaviorId: draft!.behaviorId, param1: numericParameter(parameters[0]), param2: numericParameter(parameters[1]) });
            })} />
          </>}
        </>}
      </ScrollView>
    </> : <ScrollView contentContainerStyle={{ padding: 16 }}>
      <View style={{ flexDirection: 'row' }}><Text style={[styles.text, { flex: 1, fontWeight: 'bold' }]}>入力キー</Text><Text style={[styles.text, { flex: 1, fontWeight: 'bold' }]}>送信するキー</Text></View>
      {m.relayRemaps.map(rule => <View key={rule.source} style={{ borderBottomWidth: 1, borderBottomColor: colors.surface, paddingVertical: 12 }}>
        <View style={{ flexDirection: 'row', gap: 8 }}><Text style={[styles.text, { flex: 1 }]}>{usageLabel(rule.source)}</Text><Text style={styles.muted}>→</Text><Text style={[styles.text, { flex: 1 }]}>{remapLabel(rule.target)}</Text></View>
        <View style={{ flexDirection: 'row', gap: 12 }}><View style={{ flex: 1 }}><Button title="変更" disabled={busy} onPress={() => { setOriginal(rule.source); setSource(rule.source); setTarget(rule.target); }} /></View>
          <View style={{ flex: 1 }}><Button title="削除" disabled={busy} onPress={() => void perform(() => m.saveRelayRemaps(m.relayRemaps.filter(value => value.source !== rule.source)))} /></View></View>
      </View>)}
      <Section title={original === undefined ? '読み替えを追加' : '読み替えを変更'} />
      <Button align="left" title={`入力: ${source === undefined ? 'キーを選択' : usageLabel(source)}`} disabled={busy}
        onPress={() => openPicker('入力キーを選択', sources, setSource)} />
      <Button title={m.relayLearning ? '入力待ち… 検出をやめる' : '入力キーを押して検出'} disabled={busy} onPress={() => m.learnRelayKey(!m.relayLearning)} />
      <Button align="left" title={`送信: ${target === undefined ? 'キーを選択' : remapLabel(target)}`} disabled={busy}
        onPress={() => openPicker('送信するキーを選択', [{ label: '無効化', value: 0 }, ...outputs], value => setTarget(expandUsage(value)))} />
      {target !== undefined && target.length > 0 && <Modifiers disabled={busy} value={target.reduce((mask, usage) => usage >= 0x700e0 && usage <= 0x700e7 ? mask | (1 << (usage - 0x700e0)) : mask, 0)}
        onChange={mask => setTarget([...target.filter(usage => usage < 0x700e0 || usage > 0x700e7),
          ...Array.from({ length: 8 }, (_, index) => index).filter(index => mask & (1 << index)).map(index => 0x700e0 + index)])} />}
      <Button title={original === undefined ? '登録' : '変更を保存'} disabled={busy || m.relayLearning || source === undefined || target === undefined} onPress={() => void saveRemap()} />
      {(source !== undefined || target !== undefined) && <Button title="キャンセル" onPress={clearRemap} />}
    </ScrollView>}
    <Modal visible={!!picker} transparent animationType="fade" onRequestClose={() => setPicker(undefined)}>
      <View style={{ flex: 1, justifyContent: 'center', backgroundColor: '#00000099', padding: 24 }}><View style={{ backgroundColor: colors.surface, maxHeight: '85%', padding: 16, borderRadius: 12 }}>
        <Text style={[styles.text, { fontSize: 20, fontWeight: 'bold' }]}>{picker?.title}</Text>
        <TextInput value={search} onChangeText={setSearch} placeholder="検索" placeholderTextColor={colors.muted} style={[styles.text, { padding: 12 }]} />
        <ScrollView>{picker?.options.filter(choice => choice.label.toLowerCase().includes(search.toLowerCase())).map((choice, index) =>
          <Button align="left" key={`${choice.value}-${index}`} title={choice.label} onPress={() => { setPicker(undefined); picker.select(choice.value); }} />)}</ScrollView>
        <Button title="閉じる" onPress={() => setPicker(undefined)} />
      </View></View>
    </Modal>
  </View>;
}

function Modifiers({ value, disabled, onChange }: { value: number; disabled: boolean; onChange(value: number): void }) {
  return <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginVertical: 8 }}>
    {['L Ctrl', 'L Shift', 'L Alt', 'L GUI', 'R Ctrl', 'R Shift', 'R Alt', 'R GUI'].map((name, index) => <Pressable key={name}
      accessibilityRole="checkbox" accessibilityState={{ checked: !!(value & (1 << index)), disabled }} disabled={disabled}
      onPress={() => onChange(value ^ (1 << index))} style={{ padding: 8, borderRadius: 6, backgroundColor: value & (1 << index) ? '#24736d' : colors.surface }}>
      <Text style={styles.text}>{name}</Text>
    </Pressable>)}
  </View>;
}
