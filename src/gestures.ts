import { LikiModel, add, sub, mul, distance, zero } from './model.ts';
import type { Point, KeyShape, Geometry } from './model.ts';
export type Touch = { id: number; position: Point; time: number; kind: 'down' | 'move' | 'up' | 'cancel' };

export class Trackpad {
  model: LikiModel; hold?: ReturnType<typeof setTimeout>; last = zero(); down = zero(); remainder = zero(); lastTap = zero();
  scrollRemainder = 0; zoomRemainder = 0; startSpan = 0; lastSpan = 0; startCenter = zero(); downTime = 0; lastTapTime = 0;
  maxPointers = 1; gesture = 0; moved = false; dragging = false; scrolling = false; tapDragArmed = false; multiMoved = false;
  starts = new Map<number, Point>();
  constructor(model: LikiModel) { this.model = model; }
  center(points: Map<number, Point>) { const [a, b] = [...points.values()]; return mul(add(a, b), .5); }
  span(points: Map<number, Point>) { const [a, b] = [...points.values()]; return distance(a, b); }
  updateMulti(points: Map<number, Point>) { for (const [id, p] of points) if (this.starts.has(id) && distance(p, this.starts.get(id)!) > 8) this.multiMoved = true; }
  touch(e: Touch, points: Map<number, Point>) {
    const m = this.model, p = e.position, time = e.time;
    if (e.kind === 'down' && points.size === 1) {
      this.last = this.down = p; this.downTime = time;
      this.tapDragArmed = m.flag('tap_drag') && m.flag('tap_to_click') && this.lastTapTime > 0 && time - this.lastTapTime >= 1 && time - this.lastTapTime <= 350 && distance(p, this.lastTap) <= 24;
      this.remainder = zero(); this.scrollRemainder = 0; this.moved = this.dragging = this.scrolling = this.multiMoved = false;
      this.maxPointers = 1; this.starts.clear(); this.gesture = 0; this.zoomRemainder = 0;
      if (m.flag('hold_to_drag')) this.hold = setTimeout(() => {
        if (m.flag('hold_to_drag') && !this.moved && !this.scrolling) { this.dragging = true; m.send('mouseDown', { button: 1 }); }
      }, 450);
    } else if (e.kind === 'down') {
      clearTimeout(this.hold); const wasDragging = this.dragging;
      if (this.dragging) { m.send('mouseUp', { button: 1 }); this.dragging = false; }
      if (!this.scrolling) this.starts = new Map(points); else this.starts.set(e.id, p);
      this.scrolling = true; this.multiMoved ||= wasDragging; this.maxPointers = Math.max(this.maxPointers, points.size);
      this.tapDragArmed = false; this.lastTapTime = 0; this.last = this.center(points);
      if (points.size === 2) { this.startSpan = this.lastSpan = this.span(points); this.startCenter = this.last; this.gesture = 0; }
    } else if (e.kind === 'move') {
      if (points.size >= 2) {
        this.updateMulti(points);
        if (points.size === 2 && this.maxPointers === 2 && this.multiMoved) {
          const c = this.center(points), span = this.span(points);
          if (!this.gesture) {
            const change = Math.abs(span - this.startSpan), travel = distance(c, this.startCenter);
            if (m.flag('trackpad_pinch_zoom') && change > 12 && change > travel * 1.2) this.gesture = 2;
            else if (travel > 8 && (travel > change * .8 || !m.flag('trackpad_pinch_zoom'))) this.gesture = 1;
          }
          if (this.gesture === 1) {
            const delta = (this.last.y - c.y) / 18 * m.num('scroll_speed') * (m.flag('reverse_scroll') ? -1 : 1) + this.scrollRemainder, ticks = Math.trunc(delta);
            this.scrollRemainder = delta - ticks; if (ticks && m.flag('two_finger_scroll')) m.send('scroll', { amount: ticks }); this.last = c;
          } else if (this.gesture === 2) {
            const delta = (span - this.lastSpan) / 24 + this.zoomRemainder, ticks = Math.trunc(delta); this.zoomRemainder = delta - ticks;
            if (ticks) m.send('zoom', { amount: ticks }); this.lastSpan = span;
          }
        }
      } else if (!this.scrolling) {
        if (distance(p, this.down) > 8) {
          this.moved = true; clearTimeout(this.hold);
          if (this.tapDragArmed && !this.dragging) { this.dragging = true; m.send('mouseDown', { button: 1 }); this.lastTapTime = 0; }
        }
        if (this.tapDragArmed && !this.dragging && !this.moved) return;
        const delta = add(mul(sub(p, this.last), 1.45 * m.num('pointer_speed') * m.density), this.remainder);
        const x = Math.trunc(delta.x), y = Math.trunc(delta.y); this.remainder = sub(delta, { x, y }); if (x || y) m.send('move', { x, y }); this.last = p;
      }
    } else if (e.kind === 'up' || e.kind === 'cancel') {
      if (points.size > 1 && e.kind === 'up') { this.updateMulti(points); if (points.size === 2) this.last = [...points].find(([id]) => id !== e.id)![1]; return; }
      clearTimeout(this.hold); if (this.scrolling) this.updateMulti(points);
      if (this.dragging) m.send('mouseUp', { button: 1 });
      else if (e.kind === 'up' && this.scrolling && !this.multiMoved && time - this.downTime < 450) {
        if (this.maxPointers === 2 && m.flag('two_finger_right_click')) m.send('click', { button: 2 });
        if (this.maxPointers === 3 && m.flag('three_finger_middle_click')) m.send('click', { button: 4 });
      } else if (e.kind === 'up' && m.flag('tap_to_click') && !this.moved && !this.scrolling && time - this.downTime < 450) {
        m.send('click', { button: 1 }); this.lastTapTime = time; this.lastTap = p;
      }
      if (this.dragging || this.scrolling || this.moved || e.kind === 'cancel') this.lastTapTime = 0;
      this.dragging = this.scrolling = this.tapDragArmed = false; this.gesture = 0; this.starts.clear();
    }
  }
  cancel() { clearTimeout(this.hold); if (this.dragging) this.model.send('mouseUp', { button: 1 }); this.dragging = this.scrolling = this.tapDragArmed = false; this.gesture = 0; this.lastTapTime = 0; this.starts.clear(); }
}

export class TrackpadGesture {
  points = new Map<number, Point>();
  trackpad: Trackpad;
  constructor(model: LikiModel) { this.trackpad = new Trackpad(model); }
  touch(event: Touch) {
    if (event.kind === 'cancel') { this.cancel(); return; }
    this.points.set(event.id, event.position);
    this.trackpad.touch(event, this.points);
    if (event.kind === 'up') this.points.delete(event.id);
  }
  cancel() { this.trackpad.cancel(); this.points.clear(); }
}

export class KeyboardGesture {
  geometry: Geometry;
  model: LikiModel; points = new Map<number, Point>(); trackpad: Trackpad; selected?: KeyShape; down = zero(); last = zero();
  moved = false; pinching = false; trackpadTouch = false; momentary = false; momentaryPointer?: number; pinchSpan = 0;
  chords = new Map<number, { key: KeyShape; layer: number; down: Point; moved: boolean }>();
  onEdit: (key: KeyShape) => void;
  constructor(model: LikiModel, onEdit: (key: KeyShape) => void, geometry = model.geometry!) {
    this.model = model; this.onEdit = onEdit; this.geometry = geometry; this.trackpad = new Trackpad(model);
  }
  setEditor(onEdit: (key: KeyShape) => void) { this.onEdit = onEdit; }
  span() { const [a, b] = [...this.points.values()]; return distance(a, b); }
  tap(key: KeyShape, layer: number) {
    this.model.send('vibrate'); if (this.model.editing) { if (!key.ghost) this.onEdit(key); }
    else void this.model.tap(key, layer).catch(this.model.onError);
  }
  touch(e: Touch) {
    const m = this.model, g = this.geometry;
    if (e.kind !== 'cancel') this.points.set(e.id, e.position);
    if (e.kind === 'down' && this.points.size === 1) {
      this.down = this.last = e.position; this.moved = this.pinching = false;
      this.selected = g.hit(e.position); this.trackpadTouch = this.selected?.ghost === true && !m.editing;
      if (this.trackpadTouch) { this.trackpad.touch(e, this.points); return; }
      if (!m.editing && this.selected && m.bindingFor(this.selected)?.action === 'MOMENTARY') {
        this.momentary = true; this.momentaryPointer = e.id; m.momentaryDown(this.selected); m.send('vibrate');
      }
      return;
    }
    if (this.momentary) {
      if (e.kind === 'down') {
        const key = g.hit(e.position); if (key && !key.ghost && m.bindingFor(key)?.action !== 'MOMENTARY') this.chords.set(e.id, { key, layer: m.layer, down: e.position, moved: false });
      } else if (e.kind === 'move') {
        const touch = this.chords.get(e.id); if (touch && distance(e.position, touch.down) > 8) touch.moved = true;
      } else if (e.kind === 'up') {
        if (e.id === this.momentaryPointer) { m.momentaryUp(); this.momentaryPointer = undefined; }
        else { const touch = this.chords.get(e.id); this.chords.delete(e.id); if (touch && !touch.moved) this.tap(touch.key, touch.layer); }
        if (this.points.size === 1) { m.momentaryUp(); this.momentary = false; this.chords.clear(); this.selected = undefined; }
      } else this.cancel();
    } else if (this.trackpadTouch) {
      this.trackpad.touch(e, this.points); if ((e.kind === 'up' && this.points.size === 1) || e.kind === 'cancel') this.trackpadTouch = false;
    } else if (e.kind === 'down' && this.points.size === 2) { this.pinching = true; this.pinchSpan = this.span(); }
    else if (e.kind === 'move') {
      if (this.points.size >= 2) {
        const span = this.span(), [a, b] = [...this.points.values()]; if (m.flag('pinch_zoom') && this.pinchSpan > 0) m.zoomKeyboard(span / this.pinchSpan, mul(add(a, b), .5)); this.pinchSpan = span;
      } else if (!this.pinching) {
        if (distance(e.position, this.down) > 8) this.moved = true;
        if (this.moved && m.flag('drag_move_keyboard')) g.pan = add(g.pan, sub(e.position, this.last)); this.last = e.position;
      }
    } else if (e.kind === 'up' && this.points.size === 1) { if (!this.moved && !this.pinching && this.selected) this.tap(this.selected, m.displayedLayer); this.selected = undefined; }
    if (e.kind === 'up') { this.points.delete(e.id); m.saveSession(); }
    if (e.kind === 'cancel') this.cancel();
  }
  cancel(notify = true) { this.trackpad.cancel(); this.model.momentaryUp(notify); this.points.clear(); this.chords.clear(); this.momentary = this.trackpadTouch = false; this.momentaryPointer = undefined; this.selected = undefined; }
}
