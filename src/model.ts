import { StudioClient } from './zmk.ts';
export type Data = Record<string, any>;
export type Point = { x: number; y: number };
export type Binding = { name: string; code: number; modifier: number; layer: number; action: string; consumer: number };
export type KeyShape = { index: number; x: number; y: number; w: number; h: number; x2: number; y2: number; w2: number; h2: number;
  rx: number; ry: number; rotation: number; color: number; labels: string[]; ghost: boolean; decal: boolean };
export interface Backend {
  invoke(method: string, args?: Data): Promise<any>;
  subscribe(listener: (status: Data) => void): { remove(): void };
}
export const add = (a: Point, b: Point): Point => ({ x: a.x + b.x, y: a.y + b.y });
export const sub = (a: Point, b: Point): Point => ({ x: a.x - b.x, y: a.y - b.y });
export const mul = (a: Point, f: number): Point => ({ x: a.x * f, y: a.y * f });
export const distance = (a: Point, b: Point): number => Math.hypot(a.x - b.x, a.y - b.y);
export const zero = (): Point => ({ x: 0, y: 0 });
export const clamp = (v: number, low: number, high: number) => Math.max(low, Math.min(high, v));
export function rotate(k: KeyShape, p: Point, inverse = false): Point {
  const a = k.rotation * Math.PI / 180 * (inverse ? -1 : 1), dx = p.x - k.rx, dy = p.y - k.ry;
  return { x: k.rx + dx * Math.cos(a) - dy * Math.sin(a), y: k.ry + dx * Math.sin(a) + dy * Math.cos(a) };
}
export class Geometry {
  keys: KeyShape[]; zoom = 1; base = 1; pan = zero(); width = 0; height = 0;
  left: number; right: number; top: number; bottom: number;
  constructor(keys: KeyShape[]) {
    this.keys = keys;
    const p: Point[] = [];
    for (const k of keys) for (const [x, y, w, h] of [[k.x, k.y, k.w, k.h], [k.x + k.x2, k.y + k.y2, k.w2, k.h2]]) {
      p.push(...[{ x, y }, { x: x + w, y }, { x, y: y + h }, { x: x + w, y: y + h }].map(point => rotate(k, point)));
    }
    this.left = Math.min(...p.map(p => p.x)); this.right = Math.max(...p.map(p => p.x));
    this.top = Math.min(...p.map(p => p.y)); this.bottom = Math.max(...p.map(p => p.y));
  }
  get scale() { return this.base * this.zoom; }
  get origin(): Point { return { x: (this.width - (this.right - this.left) * this.scale) / 2 - this.left * this.scale + this.pan.x,
    y: (this.height - (this.bottom - this.top) * this.scale) / 2 - this.top * this.scale + this.pan.y }; }
  world(p: Point) { return mul(sub(p, this.origin), 1 / this.scale); }
  hit(p: Point) {
    const world = this.world(p);
    const inside = (p: Point, x: number, y: number, w: number, h: number) => p.x >= x && p.y >= y && p.x < x + w && p.y < y + h;
    return [...this.keys].reverse().find(k => {
      if (k.decal) return false;
      const local = rotate(k, world, true);
      return inside(local, k.x, k.y, k.w, k.h) || inside(local, k.x + k.x2, k.y + k.y2, k.w2, k.h2);
    });
  }
  zoomAt(factor: number, focus: Point, manual: boolean) {
    const before = this.world(focus); this.zoom = clamp(this.zoom * factor, manual ? .25 : 1, 5);
    this.pan = add(this.pan, sub(focus, add(this.origin, mul(before, this.scale))));
  }
  save(density: number): Data { return { zoom: this.zoom, pan_x: this.pan.x * density, pan_y: this.pan.y * density }; }
  restore(value: Data, density: number) {
    const z = value.zoom ?? 1, x = value.pan_x ?? 0, y = value.pan_y ?? 0;
    if (![z, x, y].every(Number.isFinite) || z <= 0) throw Error('表示状態が不正です');
    this.zoom = z; this.pan = { x: x / density, y: y / density };
  }
}

export class LikiModel {
  native: Backend; listeners = new Set<() => void>(); subscription?: { remove(): void };
  settings: Data = {}; status: Data = {}; preferences: Data = {}; bindings = new Map<string, Binding>(); options: Binding[] = [];
  keys: KeyShape[] = []; geometry?: Geometry; json = ''; layoutName = ''; privacy = ''; error?: string;
  selectedName?: string; selectedAddress?: string; overrides: Data = {}; layers: Data = {}; inferred: Data = {};
  density = 1; xdpi = 160; initialized = false; editing = false; settingsVisible = false; showingControls = false;
  relayMode = false; relayPaused = false;
  readonly studio = new StudioClient(bytes => this.call('studioWrite', { bytes }), () => this.listeners.forEach(fn => fn()));
  relayPressed: number[] = []; relayConsumer = 0;
  layer = 0; editLayer = 0; modifiers = 0; layerKey?: number; oneShotReturn?: number; oneShotKey?: number;
  momentaryPrevious?: number; momentaryKey?: number; momentaryTarget?: number; baseSnapshot?: string; layersSnapshot?: string;
  onError: (e: unknown) => void = () => {};
  constructor(native: Backend) { this.native = native; }
  get displayedLayer() { return this.editing ? this.editLayer : this.layer; }
  flag(key: string) { return this.settings[key] === true; }
  num(key: string) { return Number(this.settings[key] ?? 0); }
  get connected() { return this.status.connected === true; }
  get devices(): Data[] { return this.status.devices ?? []; }
  get saved(): Data[] {
    try { return JSON.parse(this.preferences.saved_layouts ?? '[]').filter((e: Data) => e && typeof e.name === 'string' && e.name.trim() &&
      typeof e.kle_json === 'string' && e.kle_json && [e.zoom ?? 1, e.pan_x ?? 0, e.pan_y ?? 0].every(Number.isFinite) && (e.zoom ?? 1) > 0); }
    catch { return []; }
  }
  call(method: string, args: Data = {}) { return this.native.invoke(method, args); }
  send(method: string, args: Data = {}) { void this.call(method, args).catch(this.onError); }
  changed() { this.saveSession(); this.listeners.forEach(fn => fn()); }
  saveSession() {
    if (!this.initialized) return;
    this.send('session', { value: JSON.stringify({ layer: this.momentaryPrevious ?? this.layer,
      layerKey: this.momentaryPrevious !== undefined ? this.momentaryKey : this.layerKey,
      oneShotReturn: this.oneShotReturn, oneShotKey: this.oneShotKey, editing: this.editing, editLayer: this.editLayer,
      baseSnapshot: this.baseSnapshot, layersSnapshot: this.layersSnapshot,
      ...(this.editing ? { overrides: this.overrides, layers: this.layers } : {}), view: this.geometry?.save(this.density) }) });
  }
  async initialize() {
    try {
      this.subscription?.remove(); this.subscription = this.native.subscribe(s => this.acceptStatus(s));
      const data = await this.call('initialize'); this.settings = data.settings; this.preferences = data.preferences;
      this.relayMode = this.preferences.input_mode === 'relay';
      this.density = data.density; this.xdpi = data.xdpi > 0 ? data.xdpi : this.density * 160; this.privacy = data.privacy;
      this.options = data.bindings; this.options.forEach(b => this.bindings.set(b.name, b));
      await this.readLayout(data.layout.json); this.overrides = JSON.parse(data.layout.overrides); this.layers = JSON.parse(data.layout.layers);
      this.selectedName = data.layout.selected ?? undefined;
      if (data.session) {
        const s = JSON.parse(data.session); this.layer = clamp(s.layer ?? 0, 0, 3); this.layerKey = s.layerKey;
        this.oneShotReturn = s.oneShotReturn; this.oneShotKey = s.oneShotKey; if (s.view) this.geometry!.restore(s.view, this.density);
        this.editing = s.editing === true;
        if (this.editing) { this.editLayer = clamp(s.editLayer ?? 0, 0, 3); this.baseSnapshot = s.baseSnapshot; this.layersSnapshot = s.layersSnapshot;
          this.overrides = s.overrides; this.layers = s.layers; }
      }
      await this.resolveBindings(); this.initialized = true; this.error = undefined; this.acceptStatus(data.status); this.syncPage(); await this.call('ready');
    } catch (e) { this.error = String(e); this.changed(); }
  }
  acceptStatus(status: Data) {
    if (status.studioBytes) { this.studio.receive(status.studioBytes); return; }
    if (status.studioState) {
      this.studio.reset(status.studioState.message);
      if (status.studioState.ready) void this.studio.load();
      return;
    }
    if (status.relayInput) {
      this.relayPressed = status.relayInput.pressed; this.relayConsumer = status.relayInput.consumer;
      this.listeners.forEach(fn => fn()); return;
    }
    this.status = status;
    if (this.connected !== this.showingControls && !status.restoring) { this.showingControls = this.connected; this.settingsVisible = false; this.syncPage(); }
    if (!this.devices.some(d => d.address === this.selectedAddress)) this.selectedAddress =
      this.devices.some(d => d.address === status.lastAddress) ? status.lastAddress : this.devices[0]?.address;
    this.changed();
  }
  async refresh() { this.acceptStatus(await this.call('status')); this.syncPage(); }
  syncPage() {
    const relay = this.relayMode && !this.relayPaused && !this.editing && !this.settingsVisible && this.showingControls;
    if (!relay) { this.relayPressed = []; this.relayConsumer = 0; }
    this.send('page', { controls: this.showingControls, settings: this.settingsVisible, relay });
  }
  async setRelayMode(enabled: boolean) {
    if (enabled && this.status.relaySupported !== true) throw Error('外付けキーボードの中継に対応したネイティブビルドをインストールしてください');
    await this.persist({ input_mode: enabled ? 'relay' : null });
    this.relayMode = enabled; this.editing = false; this.setLayer(0); this.syncPage(); this.changed();
  }
  pauseRelay(paused: boolean) { if (this.relayPaused !== paused) { this.relayPaused = paused; this.syncPage(); } }
  async setSetting(key: string, value: any) {
    this.settings = await this.call('setting', { key, value }); if (key === 'sticky_modifiers' && !value) this.modifiers = 0;
    this.fitGeometry(); this.changed();
  }
  setViewport(geometry: Geometry, width: number, height: number) {
    if (this.geometry !== geometry) return;
    geometry.width = width; geometry.height = height; this.fitGeometry();
  }
  private fitGeometry() {
    const g = this.geometry;
    if (!g || g.width <= 0 || g.height <= 0) return;
    const pitch = this.num('key_pitch_mm'), saved = this.num('auto_key_scale_px');
    g.base = pitch > 0 ? pitch * this.xdpi / 25.4 / this.density : saved > 0 ? saved / this.density :
      Math.min((g.width - 20) / Math.max(1, g.right - g.left), (g.height - 20) / Math.max(1, g.bottom - g.top));
    if (pitch <= 0 && saved <= 0 && g.base > 0) {
      this.settings.auto_key_scale_px = g.base * this.density;
      this.send('setting', { key: 'auto_key_scale_px', value: g.base * this.density });
    }
  }
  async persist(values: Data) {
    await this.call('preferences', { values }); Object.entries(values).forEach(([k, v]) => { if (v === null) delete this.preferences[k]; else this.preferences[k] = v; });
  }
  async readLayout(value: string) {
    const parsed = await this.call('parse', { json: value }), inferred = JSON.parse(parsed.inferred);
    for (const name of Object.values(inferred)) await this.resolve(String(name));
    const width = this.geometry?.width ?? 0, height = this.geometry?.height ?? 0;
    this.json = value; this.layoutName = parsed.name; this.keys = parsed.keys; this.geometry = new Geometry(this.keys); this.inferred = inferred;
    this.setViewport(this.geometry, width, height);
  }
  async resolve(name: string): Promise<Binding | undefined> {
    if (!this.bindings.has(name)) { const b = await this.call('binding', { name }); if (b) this.bindings.set(name, b); }
    return this.bindings.get(name);
  }
  async resolveBindings() {
    for (const mapping of [this.overrides, ...Object.values(this.layers)]) for (const name of Object.values(mapping as Data)) if (typeof name === 'string') await this.resolve(name);
  }
  bindingFor(key: KeyShape, layer = this.displayedLayer) { return this.bindings.get(layer ? this.layers[layer]?.[key.index] : undefined) ?? this.bindings.get(this.overrides[key.index]); }
  overridden(key: KeyShape) { return Object.hasOwn(this.displayedLayer ? this.layers[this.displayedLayer] ?? {} : this.overrides, key.index); }
  setLayer(layer: number) {
    this.layer = clamp(layer, 0, 3); this.layerKey = this.oneShotReturn = this.oneShotKey = this.momentaryPrevious = this.momentaryKey = this.momentaryTarget = undefined; this.modifiers = 0;
  }
  momentaryDown(key: KeyShape) {
    const b = this.bindingFor(key); if (b?.action !== 'MOMENTARY') return;
    const previous = this.layer, marker = this.layerKey; this.setLayer(b.layer); this.momentaryPrevious = previous; this.momentaryKey = marker;
    this.momentaryTarget = b.layer; this.layerKey = key.index; this.changed();
  }
  momentaryUp(notify = true) {
    const previous = this.momentaryPrevious, marker = this.momentaryKey, target = this.momentaryTarget;
    this.momentaryPrevious = this.momentaryKey = this.momentaryTarget = undefined;
    if (previous !== undefined && this.layer === target) { this.setLayer(previous); this.layerKey = marker; if (notify) this.changed(); }
  }
  async tap(key: KeyShape, layer = this.layer) {
    const b = this.bindingFor(key, layer); if (!b) throw Error('メニューのキー割り当て編集で設定してください');
    switch (b.action) {
      case 'BASE': this.setLayer(0); break;
      case 'TOGGLE': this.setLayer(this.layer === b.layer ? 0 : b.layer); if (this.layer) this.layerKey = key.index; break;
      case 'CYCLE': this.setLayer((this.layer + 1) % 4); if (this.layer) this.layerKey = key.index; break;
      case 'ONE_SHOT': {
        const previous = this.layer, marker = this.layerKey; this.setLayer(b.layer); this.oneShotReturn = previous === b.layer ? 0 : previous;
        this.oneShotKey = previous === b.layer ? undefined : marker; this.layerKey = key.index; break;
      }
      case 'MOMENTARY': return;
      default:
        if (b.modifier && !b.code) {
          if (this.flag('sticky_modifiers')) this.modifiers ^= b.modifier; else await this.call('key', { modifiers: b.modifier, code: 0 });
        } else {
          if (b.consumer) await this.call('consumer', { usage: b.consumer });
          else await this.call('key', { modifiers: (this.flag('sticky_modifiers') ? this.modifiers : 0) | b.modifier, code: b.code });
          this.modifiers = 0;
          if (this.oneShotReturn !== undefined) { const previous = this.oneShotReturn, marker = this.oneShotKey; this.setLayer(previous); this.layerKey = marker; }
        }
    }
    this.changed();
  }
  beginEdit() { this.baseSnapshot = JSON.stringify(this.overrides); this.layersSnapshot = JSON.stringify(this.layers); this.editLayer = this.layer; this.editing = true; this.changed(); }
  async endEdit(save: boolean) {
    if (save) await this.persist({ overrides: JSON.stringify(this.overrides), layer_overrides: JSON.stringify(this.layers) });
    else { this.overrides = JSON.parse(this.baseSnapshot!); this.layers = JSON.parse(this.layersSnapshot!); }
    this.editing = false; this.baseSnapshot = this.layersSnapshot = undefined; this.changed();
  }
  async assign(key: KeyShape, name?: string) {
    const mapping = this.editLayer ? this.layers[this.editLayer] ??= {} : this.overrides;
    if (!name) delete mapping[key.index]; else { if (!await this.resolve(name)) throw Error('未対応のキー割り当てです'); mapping[key.index] = name; } this.changed();
  }
  async applyLayout(value: string) {
    await this.readLayout(value); this.overrides = { ...this.inferred }; this.layers = {}; this.selectedName = undefined; this.setLayer(0); this.editing = false;
    if (this.relayMode) await this.setRelayMode(false);
    await this.setSetting('auto_key_scale_px', 0);
    await this.persist({ kle_json: this.json, overrides: JSON.stringify(this.overrides), layer_overrides: '{}', selected_saved_layout_name: null }); this.changed();
  }
  async resetView() {
    if (this.geometry) { this.geometry.zoom = 1; this.geometry.pan = zero(); }
    await this.setSetting('key_pitch_mm', 0); await this.setSetting('auto_key_scale_px', 0); this.changed();
  }
  async saveLayout(name: string) {
    const entries = this.saved, entry = { name, kle_json: this.json, overrides: JSON.stringify(this.overrides), layer_overrides: JSON.stringify(this.layers), ...this.geometry!.save(this.density) };
    const i = entries.findIndex(e => e.name.toLowerCase() === name.toLowerCase()); if (i < 0) entries.push(entry); else entries[i] = entry;
    await this.persist({ saved_layouts: JSON.stringify(entries), selected_saved_layout_name: name }); this.selectedName = name; this.changed();
  }
  async restoreLayout(entry: Data) {
    const base = JSON.parse(entry.overrides ?? '{}'), extra = JSON.parse(entry.layer_overrides ?? '{}');
    const state = new Geometry(this.keys); state.restore(entry, this.density);
    await this.readLayout(entry.kle_json); this.overrides = base; this.layers = extra; this.geometry!.zoom = state.zoom; this.geometry!.pan = state.pan;
    if (this.relayMode) await this.setRelayMode(false);
    this.selectedName = entry.name; this.setLayer(0); await this.resolveBindings(); await this.setSetting('auto_key_scale_px', 0);
    await this.persist({ kle_json: this.json, overrides: JSON.stringify(base), layer_overrides: JSON.stringify(extra), selected_saved_layout_name: entry.name }); this.changed();
  }
  async deleteLayout(name: string) {
    const values: Data = { saved_layouts: JSON.stringify(this.saved.filter(e => e.name.toLowerCase() !== name.toLowerCase())) };
    if (this.selectedName?.toLowerCase() === name.toLowerCase()) { values.selected_saved_layout_name = null; this.selectedName = undefined; }
    await this.persist(values); this.changed();
  }
  dispose() { this.subscription?.remove(); this.listeners.clear(); this.studio.reset(); }
}
