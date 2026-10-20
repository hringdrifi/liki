import type { Backend, Data } from './model.ts';
import { iosBinding, iosBindings, parseIosLayout } from './ios-layout.ts';

type Native = {
  invoke(method: string, args: Data): Promise<any>;
  preferences(values: Record<string, string | null>): Promise<void>;
  pickFile(): Promise<string | null>;
  addListener(event: string, listener: (status: Data) => void): { remove(): void };
};
export function createIosBackend(native: Native): Backend {
  const assets = new Map<string, string>();
  const normalize = (s: string) => s.replace(/\r\n/g, '\n').trim();
  const asset = async (name: string): Promise<string> => {
    if (!assets.has(name)) assets.set(name, await native.invoke('asset', { name }));
    return assets.get(name)!;
  };
  const parse = async (json: string) => {
    for (const name of ['default-kle.json', 'ghosted-trackpad.json']) await asset(name);
    return parseIosLayout(json, [...assets].find(([, value]) => normalize(value) === normalize(json))?.[0]);
  };
  return {
    async invoke(method, args = {}) {
      if (method === 'initialize') {
        const data = await native.invoke(method, args), p = data.preferences;
        const json = p.kle_json ?? await asset('default-kle.json'), parsed = await parse(json);
        return { ...data, bindings: iosBindings, layout: { json, overrides: p.overrides ?? parsed.inferred,
          layers: p.layer_overrides ?? '{}', selected: p.selected_saved_layout_name ?? null } };
      }
      if (method === 'parse') return parse(args.json);
      if (method === 'binding') return iosBinding(args.name) ?? null;
      if (method === 'asset') return asset(args.name);
      if (method === 'preferences') return native.preferences(args.values);
      if (method === 'pickFile') return native.pickFile();
      return native.invoke(method, args);
    },
    subscribe(listener) { return native.addListener('statusChanged', listener); },
  };
}
