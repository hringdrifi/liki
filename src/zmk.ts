// Read-only ZMK Studio RPC client. Schema: zmkfirmware/zmk-studio-messages (MIT).
import type { KeyShape } from './model.ts';
import { iosBindings } from './ios-layout.ts';

type Message = Map<number, (number | Uint8Array)[]>;
export function protobuf(data: Uint8Array): Message {
  let pos = 0;
  const varint = () => {
    let value = 0;
    for (let shift = 0; shift < 49 && pos < data.length; shift += 7) {
      const byte = data[pos++]; value += (byte & 127) * 2 ** shift;
      if (byte < 128) return value;
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
export type StudioLayout = { name: string; keys: KeyShape[]; layers: { name: string; keys: StudioKey[] }[] };
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
function usageLabel(usage: number) {
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
  private frames = new StudioFrames(); private serial = 0; private generation = 0;
  private receivedBytes = 0;
  private pending?: { id: number; resolve(m: Message): void; reject(e: Error): void; timer: ReturnType<typeof setTimeout> };
  private write: (bytes: number[]) => Promise<any>; private changed: () => void;
  constructor(write: (bytes: number[]) => Promise<any>, changed: () => void) { this.write = write; this.changed = changed; }
  reset(message = '') {
    this.generation++; this.ready = false; this.layout = undefined; this.layer = 0; this.message = message; this.frames = new StudioFrames(); this.receivedBytes = 0;
    if (this.pending) { clearTimeout(this.pending.timer); this.pending.reject(Error('Studio接続を終了しました')); this.pending = undefined; }
    this.changed();
  }
  receive(chunk: number[]) {
    this.receivedBytes += chunk.length;
    try {
      for (const data of this.frames.push(chunk)) {
        const response = protobuf(data);
        if (!response.has(1)) continue;
        const reply = child(response, 1), pending = this.pending;
        if (!pending || number(reply, 1) !== pending.id) continue;
        clearTimeout(pending.timer); this.pending = undefined;
        if (reply.has(2)) pending.reject(Error(number(child(reply, 2), 2) === 1 ? 'キーボードのStudioロックを解除し、再取得してください' : 'Studioが要求を拒否しました'));
        else pending.resolve(reply);
      }
    } catch (e) { this.reset(String(e)); }
  }
  private request(subsystem: number, payload: number[], label: string): Promise<Message> {
    const id = ++this.serial, before = this.receivedBytes;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { if (this.pending?.id === id) { this.pending = undefined; reject(Error(`Studioの応答がありません（${label}、受信${this.receivedBytes - before}バイト）`)); } }, 8000);
      this.pending = { id, resolve, reject, timer };
      void this.write(frame([...field(1, id), ...field(subsystem, payload)])).catch(e => {
        if (this.pending?.id === id) { clearTimeout(timer); this.pending = undefined; reject(e); }
      });
    });
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
    this.message = 'ZMKレイアウトを取得中…'; this.changed();
    try {
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
      this.layout = { ...physical, layers: layers.map(l => ({ name: string(l, 2) || `Layer ${number(l, 1)}`, keys: children(l, 3).map(b => studioBinding(b, details.get(signed(b, 1)), physical.name)) })) };
      this.message = ''; this.ready = true; this.changed();
    } catch (e) { if (generation === this.generation) { this.message = String(e); this.changed(); } }
  }
}
