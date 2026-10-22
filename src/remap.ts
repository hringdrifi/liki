import type { Binding } from './model.ts';
import { usageLabel } from './zmk.ts';

export type RelayRemap = { source: number; target: number[] };
export function validUsage(usage: number) {
  if (!Number.isInteger(usage) || usage < 0 || usage > 0xfffff) return false;
  const page = usage >>> 16, code = usage & 0xffff;
  return page === 7 && (code >= 4 && code <= 0xdd || code >= 0xe0 && code <= 0xe7) || page === 12 && code > 0;
}
export function parseRelayRemaps(json: string): RelayRemap[] {
  const values: unknown = JSON.parse(json);
  if (!Array.isArray(values) || values.length > 256) throw Error('読み替え設定が不正です');
  const seen = new Set<number>();
  return values.map(rule => {
    if (!rule || !validUsage(rule.source) || seen.has(rule.source) || !Array.isArray(rule.target) || rule.target.length > 16 || !rule.target.every(validUsage)) throw Error('読み替え設定が不正です');
    seen.add(rule.source); return { source: rule.source, target: [...new Set<number>(rule.target)] };
  });
}
export function bindingUsage(binding: Binding) {
  if (binding.consumer) return 0xc0000 | binding.consumer;
  if (!binding.code && binding.modifier && !(binding.modifier & (binding.modifier - 1))) return 0x700e0 + Math.log2(binding.modifier);
  return ((binding.modifier << 24) | 0x70000 | binding.code) >>> 0;
}
export function expandUsage(usage: number) {
  if (!usage) return [];
  const modifiers = usage >>> 24, result = Array.from({ length: 8 }, (_, index) => index).filter(index => modifiers & (1 << index)).map(index => 0x700e0 + index);
  if (usage & 0xffff) result.push(usage & 0xffffff);
  return result;
}
export const remapLabel = (usages: number[]) => usages.length ? usages.map(usageLabel).join(' + ') : '無効化';
