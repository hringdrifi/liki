import { requireOptionalNativeModule } from 'expo-modules-core';
import { PermissionsAndroid, Platform } from 'react-native';
import type { Backend, Data } from './model';
import { createIosBackend } from './ios-backend';
import { impactAsync, ImpactFeedbackStyle } from 'expo-haptics';

type HidModule = { invoke(method: string, args: Data): Promise<any>; preferences(values: Record<string, string | null>): Promise<void>; pickFile(): Promise<string | null>;
  addListener(event: string, listener: (status: Data) => void): { remove(): void } };
const hid = Platform.OS === 'android' || Platform.OS === 'ios' ? requireOptionalNativeModule<HidModule>('LikiHid') : null;
export const unavailable = Platform.OS !== 'android' && Platform.OS !== 'ios' ? 'この版は Bluetooth キーボード・マウス機能に未対応です。' :
  !hid ? 'Liki の開発ビルドをインストールしてください。Expo Go では Bluetooth HID を利用できません。' : undefined;

const platformBackend: Backend = Platform.OS === 'ios' && hid ? createIosBackend(hid) : {
  async invoke(method, args = {}) {
    if (!hid) throw Error(unavailable);
    if (Platform.OS === 'android' && Number(Platform.Version) >= 31 && (method === 'ready' || method === 'transport' || method === 'discoverable')) {
      const permission = method === 'discoverable' ? PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE : PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT;
      if (!await PermissionsAndroid.check(permission)) {
        const granted = await PermissionsAndroid.request(permission);
        if (granted !== PermissionsAndroid.RESULTS.GRANTED && method === 'discoverable') throw Error('Bluetooth公開権限が必要です');
      }
    }
    if (method === 'preferences') return hid.preferences(args.values);
    return method === 'pickFile' ? hid.pickFile() : hid.invoke(method, args);
  },
  subscribe(listener) {
    if (!hid) throw Error(unavailable);
    return hid.addListener('statusChanged', listener);
  },
};

export const backend: Backend = {
  ...platformBackend,
  async invoke(method, args = {}) {
    if (method === 'haptic') {
      if (Platform.OS === 'android' && !await hid?.invoke('hapticsEnabled', {})) return;
      return impactAsync(args.style as ImpactFeedbackStyle);
    }
    return platformBackend.invoke(method, args);
  },
};
