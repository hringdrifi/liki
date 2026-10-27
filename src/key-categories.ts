import type { Binding } from './model.ts';

export const bindingCategories = ['文字・数字', '記号', '基本キー', '修飾キー付き', 'メディアキー', 'ファンクションキー', 'システム・移動・編集', 'テンキー', '修飾キー単体', 'ショートカット', 'レイヤー'];

export function bindingInCategory(binding: Binding, category: number): boolean {
  switch (category) {
    case 0: return binding.code >= 4 && binding.code <= 39 && !binding.modifier;
    case 1: return binding.code >= 45 && binding.code <= 56;
    case 2: return binding.code >= 40 && binding.code <= 44 || binding.code === 57;
    case 4: return binding.consumer !== 0;
    case 5: return binding.code >= 58 && binding.code <= 69;
    case 6: return binding.code >= 70 && binding.code <= 82 || binding.code === 101;
    case 7: return binding.code >= 83 && binding.code <= 99;
    case 8: return !binding.code && binding.modifier !== 0;
    case 9: return !!binding.code && !!binding.modifier;
    case 10: return binding.action !== 'NONE';
    default: return false;
  }
}
