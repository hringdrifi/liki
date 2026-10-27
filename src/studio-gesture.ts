import { add, sub, mul, distance, zero, KEY_PITCH_MIN_MM, KEY_PITCH_MAX_MM } from './model.ts';
import type { Geometry, LikiModel, Point } from './model.ts';
import type { Touch } from './gestures.ts';

/** Display gestures only: never sends key or mouse reports to the host. */
export class StudioKeyboardGesture {
  points = new Map<number, Point>();
  private down = zero();
  private last = zero();
  private center = zero();
  private span = 0;
  private moved = false;
  private multiple = false;
  private selected?: number;
  private model: LikiModel;
  readonly geometry: Geometry;
  private select?: (index: number) => void;
  constructor(model: LikiModel, geometry: Geometry, select?: (index: number) => void) {
    this.model = model; this.geometry = geometry; this.select = select;
  }
  setSelector(select?: (index: number) => void) { this.select = select; }
  setViewport(width: number, height: number) {
    const geometry = this.geometry;
    geometry.width = width; geometry.height = height;
    geometry.base = Math.max(.01, Math.min((width - 12) / Math.max(1, geometry.right - geometry.left), (height - 12) / Math.max(1, geometry.bottom - geometry.top)));
  }
  touch(event: Touch) {
    if (event.kind === 'cancel') { this.cancel(); return; }
    this.points.set(event.id, event.position);
    if (event.kind === 'down') {
      if (this.points.size === 1) {
        this.down = this.last = event.position; this.moved = this.multiple = false;
        this.selected = this.geometry.hit(event.position)?.index;
      } else {
        this.multiple = true; this.selected = undefined;
        const [a, b] = [...this.points.values()];
        this.span = distance(a, b); this.center = mul(add(a, b), .5);
      }
    } else if (event.kind === 'move') {
      if (this.points.size >= 2) {
        const [a, b] = [...this.points.values()], center = mul(add(a, b), .5), span = distance(a, b);
        if (this.model.flag('drag_move_keyboard')) this.geometry.pan = add(this.geometry.pan, sub(center, this.center));
        const pitch = this.geometry.base * this.model.density * 25.4 / this.model.xdpi;
        if (this.model.flag('pinch_zoom') && this.span > 0 && pitch > 0 && Number.isFinite(pitch)) {
          this.geometry.zoomAt(span / this.span, center, KEY_PITCH_MIN_MM / pitch, KEY_PITCH_MAX_MM / pitch);
        }
        this.span = span; this.center = center;
      } else if (!this.multiple) {
        if (distance(event.position, this.down) > 8) this.moved = true;
        if (this.moved && this.model.flag('drag_move_keyboard')) this.geometry.pan = add(this.geometry.pan, sub(event.position, this.last));
        this.last = event.position;
      }
    } else if (event.kind === 'up') {
      if (this.points.size === 1 && !this.multiple && !this.moved && distance(event.position, this.down) <= 8 &&
          this.selected !== undefined && this.geometry.hit(event.position)?.index === this.selected) this.select?.(this.selected);
      this.points.delete(event.id);
      if (this.points.size < 2) this.span = 0;
    }
  }
  cancel() { this.points.clear(); this.selected = undefined; this.span = 0; }
  reset() { this.cancel(); this.geometry.zoom = 1; this.geometry.pan = zero(); }
}
