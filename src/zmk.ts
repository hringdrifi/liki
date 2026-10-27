// ZMK Studio RPC client. Schema: zmkfirmware/zmk-studio-messages (MIT).
import type { KeyShape } from './model.ts';
import { iosBindings } from './ios-layout.ts';

type Message = Map<number, (number | Uint8Array)[]>;
export function protobuf(data: Uint8Array): Message {
  let pos = 0;
  const varint = () => {
    let value = 0n;
    for (let shift = 0; shift < 70 && pos < data.length; shift += 7) {
      const byte = data[pos++];
      if (shift === 63 && byte > 1) throw Error('Studioの整数が不正です');
      value |= BigInt(byte & 127) << BigInt(shift);
      if (byte < 128) return Number(value > BigInt(Number.MAX_SAFE_INTEGER) ? BigInt.asIntN(32, value) : value);
    }
    throw Error('Studioの応答が不正です');
  };
  const result: Message = new Map();
  while (pos < data.length) {
    const tag = varint(), field = Math.floor(tag / 8), wire = tag & 7;
    if (!field) throw Error('Studioのフィールドが不正です');
    let value: number | Uint8Array;
    if (wire === 0) value = varint();
    else if (wire === 2) {
      const size = varint(); if (size > data.length - pos) throw Error('Studioの応答が途切れています');
      value = data.slice(pos, pos + size); pos += size;
    } else if (wire === 1 || wire === 5) {
      pos += wire === 1 ? 8 : 4; if (pos > data.length) throw Error('Studioの応答が途切れています'); continue;
    } else throw Error('Studioの形式に対応していません');
    result.set(field, [...(result.get(field) ?? []), value]);
  }
  return result;
}
const bytes = (m: Message, f: number) => m.get(f)?.[0] instanceof Uint8Array ? m.get(f)![0] as Uint8Array : new Uint8Array();
const number = (m: Message, f: number) => typeof m.get(f)?.[0] === 'number' ? m.get(f)![0] as number : 0;
const child = (m: Message, f: number) => protobuf(bytes(m, f));
const children = (m: Message, f: number) => (m.get(f) ?? []).map(b => { if (!(b instanceof Uint8Array)) throw Error('Studioの形式が不正です'); return protobuf(b); });
const string = (m: Message, f: number) => decodeURIComponent(Array.from(bytes(m, f), b => `%${b.toString(16).padStart(2, '0')}`).join(''));
const signed = (m: Message, f: number) => { const n = number(m, f); return (n % 2 ? -(n + 1) : n) / 2; };
const vi = (value: number): number[] => { const out = []; do { const low = value % 128; value = Math.floor(value / 128); out.push(low | (value ? 128 : 0)); } while (value); return out; };
export const field = (id: number, value: number | number[]) => typeof value === 'number' ? [...vi(id * 8), ...vi(value)] : [...vi(id * 8 + 2), ...vi(value.length), ...value];
export const frame = (data: number[]) => [0xab, ...data.flatMap(b => b >= 0xab && b <= 0xad ? [0xac, b] : [b]), 0xad];
export class StudioFrames {
  private data?: number[]; private escaped = false;
  push(chunk: number[]): Uint8Array[] {
    const messages: Uint8Array[] = [];
    for (const byte of chunk) {
      if (this.escaped) { this.data?.push(byte); this.escaped = false; }
      else if (byte === 0xab) this.data = [];
      else if (byte === 0xac && this.data) this.escaped = true;
      else if (byte === 0xad && this.data) { messages.push(Uint8Array.from(this.data)); this.data = undefined; }
      else this.data?.push(byte);
      if ((this.data?.length ?? 0) > 1024 * 1024) { this.data = undefined; this.escaped = false; throw Error('Studioの応答が大きすぎます'); }
    }
    return messages;
  }
}
export type StudioKey = { label: string; usages: number[] };
export type StudioBinding = { behaviorId: number; param1: number; param2: number };
export type StudioParameter = { name: string; kind: 'none' | 'constant' | 'range' | 'hid' | 'layer'; value?: number; min?: number; max?: number; keyboardMax?: number; consumerMax?: number };
export type StudioBehavior = { id: number; name: string; parameters: StudioParameter[][][] };
export type StudioLayout = { name: string; keys: KeyShape[]; layers: { id: number; name: string; bindings: StudioBinding[]; keys: StudioKey[] }[] };
const rawBinding = (binding: Message): StudioBinding => ({ behaviorId: signed(binding, 1), param1: number(binding, 2), param2: number(binding, 3) });
const encodeBinding = (binding: StudioBinding) => [...field(1, binding.behaviorId < 0 ? -binding.behaviorId * 2 - 1 : binding.behaviorId * 2), ...field(2, binding.param1), ...field(3, binding.param2)];
const int32 = (value: number) => value > 0x7fffffff ? (value >>> 0) | 0 : value;
export function studioBehavior(details: Message): StudioBehavior {
  return { id: number(details, 1), name: string(details, 2), parameters: children(details, 3).map(set => [1, 2].map(index => children(set, index).map(value => {
    const name = string(value, 1);
    if (value.has(3)) return { name, kind: 'constant' as const, value: number(value, 3) };
    if (value.has(4)) { const range = child(value, 4); return { name, kind: 'range' as const, min: int32(number(range, 1)), max: int32(number(range, 2)) }; }
    if (value.has(5)) { const hid = child(value, 5); return { name, kind: 'hid' as const, keyboardMax: number(hid, 1), consumerMax: number(hid, 2) }; }
    return { name, kind: value.has(6) ? 'layer' as const : 'none' as const };
  }))) };
}
export function studioParameterMatches(option: StudioParameter, value: number, layerIds: number[]) {
  if (option.kind === 'none') return value === 0;
  if (option.kind === 'constant') return value === option.value;
  if (option.kind === 'range') { const n = option.min! < 0 ? int32(value) : value; return n >= option.min! && n <= option.max!; }
  if (option.kind === 'layer') return layerIds.includes(value);
  const page = (value >>> 16) & 255, code = value & 65535;
  return (page === 7 && code <= option.keyboardMax! || page === 12 && code <= option.consumerMax!) && code > 0;
}
export function studioParameters(behavior: StudioBehavior, index: number, param1: number, layerIds: number[]) {
  return behavior.parameters.filter(set => index === 0 || !set[0].length || set[0].some(option => studioParameterMatches(option, param1, layerIds)))
    .flatMap(set => set[index]);
}
export function physicalLayout(response: Message) {
  const layouts = children(response, 2), layout = layouts[number(response, 1)];
  if (!layout) throw Error('ZMKの物理レイアウトがありません');
  const attrs = children(layout, 2);
  if (!attrs.length || attrs.length > 300) throw Error('ZMKのキー数に対応していません');
  const keys: KeyShape[] = attrs.map((key, index) => {
    const [w, h, x, y, rotation, rx, ry] = Array.from({ length: 7 }, (_, i) => signed(key, i + 1) / 100);
    if (w <= 0 || h <= 0 || [w, h, x, y, rx, ry].some(v => Math.abs(v) > 1000)) throw Error('ZMKのキー配置が不正です');
    return { index, x, y, w, h, x2: 0, y2: 0, w2: w, h2: h, rotation, rx, ry, color: 0xd9e0e9, labels: [], ghost: false, decal: false };
  });
  return { name: string(layout, 1), keys };
}
export function usageLabel(usage: number) {
  const page = (usage >>> 16) & 255, code = usage & 65535, mods = usage >>> 24;
  const binding = iosBindings.find(b => page === 7 ? b.code === code && !b.modifier : page === 12 && b.consumer === code);
  const name = page === 7 && code >= 0xe0 && code <= 0xe7 ? ['L Ctrl', 'L Shift', 'L Alt', 'L GUI', 'R Ctrl', 'R Shift', 'R Alt', 'R GUI'][code - 0xe0] : binding?.name ?? `HID ${page}:${code}`;
  return `${mods ? `Mod ${mods}+` : ''}${name}`;
}
export function studioBinding(binding: Message, details?: Message, layoutName?: string): StudioKey {
  const params = [number(binding, 2), number(binding, 3)];
  const metadata = details ? children(details, 3) : [];
  // Metadata identifies HID parameters even for custom-named tap/hold behaviors.
  const usageParams = [1, 2].filter(p => metadata.some(set => children(set, p).some(value => value.has(5))));
  const usages = usageParams.map(p => params[p - 1]).filter(n => [7, 12].includes((n >>> 16) & 255) && (n & 65535));
  const name = details ? string(details, 2) : `Behavior ${signed(binding, 1)}`;
  // Bifrost's JIS-host mod-morph definitions have no HID parameters in Studio metadata.
  if (!usages.length && layoutName === 'Bifrost' && params.every(p => p === 0)) {
    if (name === 'us_minus') return { label: '-', usages: [0x7002d, 0x02070087] };
    if (name === 'us_backslash') return { label: '\\', usages: [0x70087, 0x02070089] };
    if (name === 'us_semicolon') return { label: ';', usages: [0x70033, 0x70034] };
  }
  return { label: usages.length ? usages.map(usageLabel).join(' / ') : `${name}${params.some(Boolean) ? ` ${params.join(', ')}` : ''}`, usages };
}
export function studioKeyActive(key: StudioKey, pressed: number[], consumer: number) {
  return key.usages.some(usage => {
    const page = (usage >>> 16) & 255, code = usage & 65535, mods = usage >>> 24;
    return (page === 7 ? pressed.includes(code) : page === 12 && consumer === code) &&
      Array.from({ length: 8 }, (_, i) => i).every(i => !(mods & (1 << i)) || pressed.includes(0xe0 + i));
  });
}

export class StudioClient {
  layout?: StudioLayout; message = ''; layer = 0; ready = false;
  activeLayer?: number;
  get displayLayer() { return this.activeLayer !== undefined && this.layout?.layers[this.activeLayer] ? this.activeLayer : this.layer; }
  receiveLayer(bytes: unknown) {
    this.activeLayer = Array.isArray(bytes) && bytes.length === 2 && bytes[0] === 1 &&
      Number.isInteger(bytes[1]) && bytes[1] >= 0 && bytes[1] < 32 ? bytes[1] : undefined;
    this.changed();
  }
  busy = false; dirty = false;
  behaviors: StudioBehavior[] = [];
  private details = new Map<number, Message>();
  private frames = new StudioFrames(); private serial = 0; private generation = 0;
  private locked = false;
  private pending?: { id: number; resolve(m: Message): void; reject(e: Error): void; completing?: boolean };
  private write: (bytes: number[], id: number, label: string) => Promise<any>;
  private changed: () => void; private complete: (id: number) => Promise<any>;
  constructor(write: (bytes: number[], id: number, label: string) => Promise<any>, changed: () => void, complete: (id: number) => Promise<any>) {
    this.write = write; this.changed = changed; this.complete = complete;
  }
  reset(message = '') {
    this.locked = false;
    this.behaviors = []; this.details.clear(); this.dirty = false;
    // Layer notifications use their own BLE service and survive a Studio reset/lock.
    this.generation++; this.ready = false; this.layout = undefined; this.layer = 0; this.message = message; this.frames = new StudioFrames();
    if (this.pending) { this.pending.reject(Error(message || 'Studio接続を終了しました')); this.pending = undefined; }
    this.changed();
  }
  private setLockState(state: number) {
    this.locked = state !== 1;
    if (this.locked) {
      this.generation++; this.ready = false;
      this.message = 'キーボードのStudioロックを解除し、再取得してください';
      const pending = this.pending; this.pending = undefined;
      if (pending) {
        const error = Error(this.message);
        if (pending.completing) pending.reject(error);
        else void this.complete(pending.id).then(() => pending.reject(error), e => pending.reject(e));
      }
    }
    this.changed();
  }
  receive(chunk: number[]) {
    try {
      for (const data of this.frames.push(chunk)) {
        const response = protobuf(data);
        if (response.has(2)) {
          const core = child(child(response, 2), 2);
          if (core.has(1)) this.setLockState(number(core, 1));
        }
        if (!response.has(1)) continue;
        const reply = child(response, 1), pending = this.pending;
        if (!pending || pending.completing || number(reply, 1) !== pending.id) continue;
        pending.completing = true;
        // Finish the native deadline before allowing the next RPC request.
        void this.complete(pending.id).then(() => {
          if (this.pending !== pending) return;
          this.pending = undefined;
          if (reply.has(2)) {
            if (number(child(reply, 2), 2) === 1) { this.setLockState(0); pending.reject(Error(this.message)); }
            else pending.reject(Error('Studioが要求を拒否しました'));
          } else pending.resolve(reply);
        }).catch(e => {
          if (this.pending === pending) { this.pending = undefined; pending.reject(e); }
        });
      }
    } catch (e) { this.reset(String(e)); }
  }
  private request(subsystem: number, payload: number[], label: string): Promise<Message> {
    if (this.locked && subsystem !== 3) return Promise.reject(Error(this.message));
    if (this.pending) return Promise.reject(Error('Studioの通信が処理中です'));
    const id = ++this.serial;
    return new Promise((resolve, reject) => {
      this.pending = { id, resolve, reject };
      void this.write(frame([...field(1, id), ...field(subsystem, payload)]), id, label).catch(e => {
        if (this.pending?.id === id) { this.pending = undefined; reject(e); }
      });
    });
  }
  private async checkUnlocked() {
    const generation = this.generation;
    const lock = child(await this.request(3, field(2, 1), 'ロック状態確認'), 3);
    if (generation !== this.generation) throw Error('Studio接続が変更されました');
    if (!lock.has(2) || ![0, 1].includes(number(lock, 2))) throw Error('Studioのロック状態を確認できません。再取得してください');
    if (number(lock, 2) === 0) { this.setLockState(0); throw Error(this.message); }
    this.locked = false;
  }
  async load() {
    if (this.pending) return;
    const generation = this.generation;
    const request = async (subsystem: number, payload: number[], label: string) => {
      if (generation !== this.generation) throw Error('Studio接続が変更されました');
      const response = await this.request(subsystem, payload, label);
      if (generation !== this.generation) throw Error('Studio接続が変更されました');
      return response;
    };
    this.ready = false; this.message = 'ZMKレイアウトを取得中…'; this.changed();
    try {
      await this.checkUnlocked();
      const physical = physicalLayout(child(child(await request(5, field(6, 1), '物理レイアウト'), 5), 6));
      const keymap = child(child(await request(5, field(1, 1), 'キー割り当て'), 5), 1);
      const layers = children(keymap, 1), details = new Map<number, Message>();
      if (layers.length > 64 || layers.some(l => children(l, 3).length !== physical.keys.length)) throw Error('ZMKの配置とキー割り当てが一致しません');
      for (const layer of layers) for (const binding of children(layer, 3)) {
        const id = signed(binding, 1);
        if (id <= 0 || details.has(id)) continue;
        if (details.size >= 512) throw Error('ZMKのビヘイビア数に対応していません');
        details.set(id, child(child(await request(4, field(2, field(1, id)), `ビヘイビア${id}`), 4), 2));
      }
      if (generation !== this.generation) return;
      if (!layers.length || layers.some(l => children(l, 3).length !== physical.keys.length)) throw Error('ZMKの配置とキー割り当てが一致しません');
      this.details = details;
      this.layout = { ...physical, layers: layers.map(l => ({ id: number(l, 1), name: string(l, 2) || `Layer ${number(l, 1)}`,
        bindings: children(l, 3).map(rawBinding), keys: children(l, 3).map(b => studioBinding(b, details.get(signed(b, 1)), physical.name)) })) };
      this.message = ''; this.ready = true; this.changed();
    } catch (e) { if (generation === this.generation) { this.message = String(e); this.changed(); } }
  }
  async loadBehaviors() {
    if (!this.ready || this.busy || this.pending) throw Error('Studioの取得が完了してから編集してください');
    const generation = this.generation; this.busy = true; this.changed();
    try {
      const list = child(child(await this.request(4, field(1, 1), 'ビヘイビア一覧'), 4), 1), ids: number[] = [];
      for (const value of list.get(1) ?? []) {
        if (typeof value === 'number') ids.push(value);
        else { let n = 0, shift = 0; for (const byte of value) { n += (byte & 127) * 2 ** shift; if (byte < 128) { ids.push(n); n = 0; shift = 0; } else { shift += 7; if (shift > 35) throw Error('Studioの一覧が不正です'); } } if (shift) throw Error('Studioの一覧が不正です'); }
      }
      if (!ids.length || ids.length > 512) throw Error('Studioのビヘイビア一覧を取得できません');
      const details = new Map(this.details);
      for (const id of ids) {
        if (generation !== this.generation) throw Error('Studio接続が変更されました');
        if (!details.has(id)) details.set(id, child(child(await this.request(4, field(2, field(1, id)), 'ビヘイビア詳細'), 4), 2));
      }
      if (generation !== this.generation) throw Error('Studio接続が変更されました');
      this.details = details; this.behaviors = ids.map(id => studioBehavior(details.get(id)!));
    } finally { this.busy = false; this.changed(); }
  }
  private async persistChanges() {
    const generation = this.generation;
    const result = child(child(await this.request(5, field(4, 1), '保存'), 5), 4);
    if (generation !== this.generation) throw Error('Studio接続が変更されました');
    if (!result.has(1) || number(result, 1) !== 1) throw Error(`キーボードへの保存に失敗しました（${number(result, 2)}）。変更は未保存です`);
    this.dirty = false;
  }
  async saveBinding(layerIndex: number, position: number, binding: StudioBinding) {
    if (!this.ready || this.busy || this.pending || this.dirty) throw Error('Studioの処理中または未保存の変更があります');
    const layer = this.layout?.layers[layerIndex], behavior = this.behaviors.find(b => b.id === binding.behaviorId);
    if (!layer || !layer.bindings[position] || !behavior) throw Error('編集対象が不正です');
    const layerIds = this.layout!.layers.map(l => l.id);
    for (const [index, value] of [binding.param1, binding.param2].entries()) {
      const options = studioParameters(behavior, index, binding.param1, layerIds);
      if (!Number.isInteger(value) || value < 0 || value > 0xffffffff || (options.length ? !options.some(option => studioParameterMatches(option, value, layerIds)) : value !== 0)) throw Error('ビヘイビアのパラメーターが不正です');
    }
    const generation = this.generation;
    this.busy = true; this.changed();
    try {
      await this.checkUnlocked();
      const unsaved = child(await this.request(5, field(3, 1), '未保存の変更確認'), 5);
      if (generation !== this.generation) throw Error('Studio接続が変更されました');
      if (!unsaved.has(3) || number(unsaved, 3)) throw Error('キーボードに未保存の変更があります。Studioで保存または破棄してから編集してください');
      this.dirty = true;
      const result = child(await this.request(5, field(2, [...field(1, layer.id), ...field(2, position), ...field(3, encodeBinding(binding))]), 'キー割り当て変更'), 5);
      if (generation !== this.generation) throw Error('Studio接続が変更されました');
      if (!result.has(2)) throw Error('キー割り当て変更の結果を確認できません');
      if (number(result, 2)) { this.dirty = false; throw Error(`Studioがキー割り当てを拒否しました（${number(result, 2)}）`); }
      layer.bindings[position] = { ...binding }; layer.keys[position] = studioBinding(protobuf(Uint8Array.from(encodeBinding(binding))), this.details.get(binding.behaviorId), this.layout!.name);
      await this.persistChanges();
    } finally { this.busy = false; this.changed(); }
  }
  async savePending() {
    if (!this.dirty || this.busy || this.pending) throw Error('保存対象がありません');
    this.busy = true; this.changed();
    try { await this.checkUnlocked(); await this.persistChanges(); await this.load(); if (this.message) throw Error(this.message); } finally { this.busy = false; this.changed(); }
  }
  async discardPending() {
    if (!this.dirty || this.busy || this.pending) throw Error('破棄する変更がありません');
    const generation = this.generation;
    this.busy = true; this.changed();
    try {
      await this.checkUnlocked();
      const result = child(await this.request(5, field(5, 1), '変更を破棄'), 5);
      if (generation !== this.generation) throw Error('Studio接続が変更されました');
      if (!result.has(5) || number(result, 5) !== 1) throw Error('変更を破棄できませんでした');
      this.dirty = false;
    } finally { this.busy = false; this.changed(); }
    await this.load();
    if (this.message) throw Error(this.message);
  }
}
