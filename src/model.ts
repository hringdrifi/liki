import { StudioClient } from './zmk.ts';
import { parseRelayRemaps } from './remap.ts';
import type { RelayRemap } from './remap.ts';
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
export const KEY_PITCH_MIN_MM = 3, KEY_PITCH_MAX_MM = 24;
export const TRACKPAD_PANEL_MIN_PERCENT = 10, TRACKPAD_PANEL_MAX_PERCENT = 100;
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
  zoomAt(factor: number, focus: Point, minZoom: number, maxZoom: number) {
    const before = this.world(focus); this.zoom = clamp(this.zoom * factor, minZoom, maxZoom);
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
  zmkLayerMessage = '';
  zmkLayerSupported = false;
  private resetStudioView?: () => void;
  isZmkKeyboard = false; externalEditing = false; externalEditZmk = false; relayLearning = false;
  readonly studio = new StudioClient((bytes, id, label) => this.call('studioWrite', { bytes, id, label }), () => {
    if (this.studio.ready) {
      this.isZmkKeyboard = true;
      if (this.externalEditing && !this.externalEditZmk) { this.externalEditZmk = true; this.relayLearning = false; this.syncPage(); }
    }
    this.listeners.forEach(fn => fn());
  }, id => this.call('studioComplete', { id }));
  relayPressed: number[] = []; relayConsumer = 0;
  layer = 0; editLayer = 0; modifiers = 0; layerKey?: number; oneShotReturn?: number; oneShotKey?: number;
  momentaryPrevious?: number; momentaryKey?: number; momentaryTarget?: number; baseSnapshot?: string; layersSnapshot?: string;
  onError: (e: unknown) => void = () => {};
  constructor(native: Backend) { this.native = native; }
  get displayedLayer() { return this.editing ? this.editLayer : this.layer; }
  flag(key: string) { return this.settings[key] === true; }
  num(key: string) { return Number(this.settings[key] ?? 0); }
  get connected() { return this.status.connected === true; }
  get virtualKeyboardEnabled() { return !this.relayMode || this.preferences.relay_virtual_keyboard === 'true'; }
  get syncedLayer(): number | undefined {
    return this.relayMode && this.virtualKeyboardEnabled && this.zmkLayerSupported ? this.studio.activeLayer : undefined;
  }
  get shiftActive() {
    return this.relayMode && this.relayPressed.some(code => code === 0xe1 || code === 0xe5) ||
      this.virtualKeyboardEnabled && (this.modifiers & 0x22) !== 0;
  }
  get keyboardLeds(): number | undefined {
    const value = this.status.keyboardLeds;
    return this.connected && Number.isInteger(value) && value >= 0 && value <= 31 ? value : undefined;
  }
  get relayRemaps() { return parseRelayRemaps(this.preferences.relay_remaps ?? '[]'); }
  get relayEffect(): 'none' | 'matrix' | 'ripple' {
    const value = this.preferences.relay_effect;
    return value === 'none' || value === 'ripple' ? value : 'matrix';
  }
  get zmkLayoutEnabled() {
    return this.preferences.zmk_layout_enabled !== undefined ? this.preferences.zmk_layout_enabled === 'true' :
      this.preferences.relay_effect !== 'matrix' && this.preferences.relay_effect !== 'ripple';
  }
  async setZmkLayoutEnabled(enabled: boolean) {
    await this.persist({ zmk_layout_enabled: String(enabled) }); this.changed();
  }
  get showZmkLayout() {
    return !this.virtualKeyboardEnabled && this.zmkLayoutEnabled && !!this.studio.layout &&
      (this.studio.activeLayer === undefined || !!this.studio.layout.layers[this.studio.activeLayer]);
  }
  get shiftBorderColor(): number { return this.settings.shift_border_color ?? 0x80cbc4; }
  borderColorForLayer(layer: number | undefined): number {
    if (!this.editing && !this.externalEditing && this.shiftActive) return this.shiftBorderColor;
    return layer === undefined ? 0x657789 : this.settings.border_colors?.[layer] ?? 0x657789;
  }
  get trackpadBorderColor(): number {
    const layer = this.virtualKeyboardEnabled ? this.displayedLayer : this.studio.activeLayer;
    return this.borderColorForLayer(layer);
  }
  async setRelayEffect(value: 'none' | 'matrix' | 'ripple') {
    await this.persist({ relay_effect: value, zmk_layout_enabled: String(this.zmkLayoutEnabled) }); this.changed();
  }
  get relayLayout(): 'none' | 'us-jis' | 'jis-us' {
    const value = this.preferences.relay_layout;
    return value === 'us-jis' || value === 'jis-us' ? value : 'none';
  }
  async setRelayLayout(value: string) {
    if (!['none', 'us-jis', 'jis-us'].includes(value)) throw Error('配列変換の設定が不正です');
    await this.call('relayLayout', { value }); this.preferences.relay_layout = value; this.changed();
  }
  async saveRelayRemaps(rules: RelayRemap[]) {
    const value = JSON.stringify(parseRelayRemaps(JSON.stringify(rules)));
    await this.call('relayRemaps', { value }); this.preferences.relay_remaps = value; this.changed();
  }
  beginExternalEdit() {
    if (!this.status.remapSupported) throw Error('キー編集対応のネイティブビルドをインストールしてください');
    this.externalEditZmk = this.isZmkKeyboard; this.externalEditing = true; this.relayLearning = false; this.syncPage(); this.changed();
  }
  endExternalEdit() {
    if (this.studio.busy) throw Error('Studioの処理が完了するまでお待ちください');
    if (this.studio.dirty) throw Error('キーボードに未保存の変更があります。保存または破棄してください');
    this.externalEditing = this.relayLearning = false; this.syncPage(); this.changed();
  }
  learnRelayKey(enabled: boolean) { this.relayLearning = enabled; this.syncPage(); this.changed(); }
  get transport() { return this.status.transport === 'classic' ? 'classic' : 'ble'; }
  async selectTransport(value: 'ble' | 'classic') {
    this.acceptStatus(await this.call('transport', { value }));
    if (value === 'classic' && this.preferences.input_mode === 'relay') await this.persist({ input_mode: null });
    this.syncPage();
  }
  get trackpadPanelEnabled() { return this.preferences.trackpad_panel_enabled === 'true'; }
  get indicatorsVisible() { return this.preferences.indicators_visible !== 'false'; }
  async setIndicatorsVisible(visible: boolean) {
    await this.persist({ indicators_visible: visible ? null : 'false' }); this.changed();
  }
  get trackpadPanelSize() {
    const size = Number(this.preferences.trackpad_panel_size ?? 33);
    return Number.isFinite(size) ? clamp(size, TRACKPAD_PANEL_MIN_PERCENT, TRACKPAD_PANEL_MAX_PERCENT) : 33;
  }
  async setTrackpadPanelSize(size: number) {
    if (!Number.isFinite(size)) throw Error('トラックパッドのサイズが不正です');
    await this.persist({ trackpad_panel_size: String(clamp(Math.round(size), TRACKPAD_PANEL_MIN_PERCENT, TRACKPAD_PANEL_MAX_PERCENT)) }); this.changed();
  }
  get trackpadPanelPosition(): 'bottom' | 'top' | 'left' | 'right' {
    const value = this.preferences.trackpad_panel_position;
    return ['top', 'left', 'right'].includes(value) ? value : 'bottom';
  }
  async setTrackpadPanelEnabled(enabled: boolean) {
    await this.persist({ trackpad_panel_enabled: enabled ? 'true' : null }); this.changed();
  }
  async setTrackpadPanelPosition(position: 'bottom' | 'top' | 'left' | 'right') {
    await this.persist({ trackpad_panel_position: position }); this.changed();
  }
  get devices(): Data[] { return this.status.devices ?? []; }
  get saved(): Data[] {
    try { return JSON.parse(this.preferences.saved_layouts ?? '[]').filter((e: Data) => e && typeof e.name === 'string' && e.name.trim() &&
      typeof e.kle_json === 'string' && e.kle_json && [e.zoom ?? 1, e.pan_x ?? 0, e.pan_y ?? 0].every(Number.isFinite) && (e.zoom ?? 1) > 0); }
    catch { return []; }
  }
  call(method: string, args: Data = {}) { return this.native.invoke(method, args); }
  send(method: string, args: Data = {}) { void this.call(method, args).catch(this.onError); }
  haptic() {
    if (!this.flag('touch_vibration')) return;
    const strength = clamp(Math.trunc(Number(this.settings.touch_vibration_strength ?? 1)), 0, 2);
    this.send('haptic', { style: ['light', 'medium', 'heavy'][strength] });
  }
  click(button: number) { this.send('click', { button }); }
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
    if ('zmkLayer' in status) {
      const previousSync = this.syncedLayer;
      this.zmkLayerSupported = status.zmkLayer?.supported === true;
      this.zmkLayerMessage = status.zmkLayer?.message ?? '';
      this.studio.receiveLayer(status.zmkLayer?.bytes);
      if (this.syncedLayer !== undefined && (previousSync === undefined || this.layer !== this.syncedLayer)) {
        this.setLayer(this.syncedLayer); this.changed();
      }
      if (status.zmkLayer?.bytes != null && this.studio.activeLayer === undefined) {
        this.zmkLayerMessage = 'ZMKレイヤー通知の形式に対応していません'; this.changed();
      }
      return;
    }
    if (status.studioBytes) { this.studio.receive(status.studioBytes); return; }
    if (status.studioState) {
      if (status.studioState.ready) {
        this.isZmkKeyboard = true;
        if (this.externalEditing && !this.externalEditZmk) { this.externalEditZmk = true; this.relayLearning = false; this.syncPage(); }
        else this.syncPage();
      }
      this.studio.reset(status.studioState.message);
      if (status.studioState.ready) void this.studio.load();
      return;
    }
    if (status.relayInput) {
      this.relayPressed = status.relayInput.pressed; this.relayConsumer = status.relayInput.consumer;
      this.listeners.forEach(fn => fn()); return;
    }
    const keyboardChanged = JSON.stringify(this.status.keyboards ?? []) !== JSON.stringify(status.keyboards ?? []);
    const remapChanged = keyboardChanged && this.isZmkKeyboard;
    if (keyboardChanged) this.isZmkKeyboard = false;
    this.status = status;
    if (status.relaySupported === false) { this.relayMode = false; this.relayPressed = []; this.relayConsumer = 0; }
    if (this.connected !== this.showingControls && !status.restoring) { this.showingControls = this.connected; this.settingsVisible = false;
      if (!this.connected) this.externalEditing = this.relayLearning = false;
      this.syncPage(); }
    if (!this.devices.some(d => d.address === this.selectedAddress)) this.selectedAddress =
      this.devices.some(d => d.address === status.lastAddress) ? status.lastAddress : this.devices[0]?.address;
    if (remapChanged) this.syncPage();
    this.changed();
  }
  async refresh() { this.acceptStatus(await this.call('status')); this.syncPage(); }
  syncPage() {
    const studio = this.relayMode && this.showingControls;
    const relay = studio && !this.relayPaused && !this.editing && !this.settingsVisible && (!this.externalEditing || this.relayLearning);
    if (!relay) { this.relayPressed = []; this.relayConsumer = 0; }
    this.send('page', { controls: this.showingControls, settings: this.settingsVisible, relay, studio, relayLearning: this.relayLearning, remap: !this.isZmkKeyboard });
  }
  async setRelayMode(enabled: boolean, virtualKeyboard = false) {
    if (enabled && this.status.relaySupported !== true) throw Error('外付けキーボードの中継に対応したネイティブビルドをインストールしてください');
    await this.persist({ input_mode: enabled ? 'relay' : null, relay_virtual_keyboard: enabled && virtualKeyboard ? 'true' : null });
    this.relayMode = enabled; this.editing = false; this.setLayer(0); this.syncPage(); this.changed();
  }
  pauseRelay(paused: boolean) { if (this.relayPaused !== paused) { this.relayPaused = paused; this.syncPage(); } }
  zoomKeyboard(factor: number, focus: Point) {
    const g = this.geometry;
    if (!g) return;
    const pitch = g.base * this.density * 25.4 / this.xdpi;
    if (pitch > 0 && Number.isFinite(pitch)) g.zoomAt(factor, focus, KEY_PITCH_MIN_MM / pitch, KEY_PITCH_MAX_MM / pitch);
  }
  async setSetting(key: string, value: any) {
    this.settings = await this.call('setting', { key, value }); if (key === 'sticky_modifiers' && !value) this.modifiers = 0;
    if (key === 'key_pitch_mm' && this.geometry) this.geometry.zoom = 1;
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
    this.layer = this.syncedLayer ?? clamp(layer, 0, 3); this.layerKey = this.oneShotReturn = this.oneShotKey = this.momentaryPrevious = this.momentaryKey = this.momentaryTarget = undefined; this.modifiers = 0;
  }
  momentaryDown(key: KeyShape) {
    if (this.syncedLayer !== undefined) return;
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
    const b = this.bindingFor(key, this.syncedLayer ?? layer); if (!b) throw Error('メニューのキー割り当て編集で設定してください');
    if (this.syncedLayer !== undefined && b.action !== 'NONE') return;
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
  beginEdit() { this.baseSnapshot = JSON.stringify(this.overrides); this.layersSnapshot = JSON.stringify(this.layers); this.editLayer = this.layer <= 3 ? this.layer : 0; this.editing = true; this.syncPage(); this.changed(); }
  async endEdit(save: boolean) {
    if (save) await this.persist({ overrides: JSON.stringify(this.overrides), layer_overrides: JSON.stringify(this.layers) });
    else { this.overrides = JSON.parse(this.baseSnapshot!); this.layers = JSON.parse(this.layersSnapshot!); }
    this.editing = false; this.baseSnapshot = this.layersSnapshot = undefined; this.syncPage(); this.changed();
  }
  async assign(key: KeyShape, name?: string) {
    const mapping = this.editLayer ? this.layers[this.editLayer] ??= {} : this.overrides;
    if (!name) delete mapping[key.index]; else { if (!await this.resolve(name)) throw Error('未対応のキー割り当てです'); mapping[key.index] = name; } this.changed();
  }
  async applyLayout(value: string) {
    await this.readLayout(value); this.overrides = { ...this.inferred }; this.layers = {}; this.selectedName = undefined; this.setLayer(0); this.editing = false;
    if (this.relayMode) await this.setRelayMode(true, true);
    await this.setSetting('auto_key_scale_px', 0);
    await this.persist({ kle_json: this.json, overrides: JSON.stringify(this.overrides), layer_overrides: '{}', selected_saved_layout_name: null }); this.changed();
  }
  registerStudioViewReset(reset: () => void) {
    this.resetStudioView = reset;
    return () => { if (this.resetStudioView === reset) this.resetStudioView = undefined; };
  }
  async resetView() {
    if (!this.virtualKeyboardEnabled) { this.resetStudioView?.(); this.changed(); return; }
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
    if (this.relayMode) await this.setRelayMode(true, true);
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
