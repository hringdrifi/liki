module.exports = {
  name: 'Liki', slug: 'liki', version: '1.0.4', orientation: 'default', userInterfaceStyle: 'dark',
  icon: './artwork/play/store-icon-512.png',
  android: { package: 'jp.liki.pockethid', versionCode: 5,
    adaptiveIcon: { foregroundImage: './artwork/adaptive-icon-foreground.png',
      backgroundImage: './artwork/adaptive-icon-background.png', backgroundColor: '#10151c' } },
  ios: { bundleIdentifier: 'jp.liki.pockethid', supportsTablet: true,
    infoPlist: { ITSAppUsesNonExemptEncryption: false,
      NSBluetoothAlwaysUsageDescription: 'LikiをBluetoothキーボード・マウスとしてPCに接続します。' } },
  extra: { eas: { projectId: 'f10d00cb-5add-4b0d-ade2-b539b5fa045d' } },
  plugins: [
    'expo-dev-client',
    ['expo-splash-screen', { backgroundColor: '#10151c', image: './artwork/adaptive-icon-foreground.png', imageWidth: 180, resizeMode: 'contain' }],
    ['expo-build-properties', { android: { minSdkVersion: 28, compileSdkVersion: 37, targetSdkVersion: 36, cmakeVersion: '3.31.6' } }],
    './plugins/with-liki',
  ],
};
