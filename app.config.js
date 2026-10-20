module.exports = {
  name: 'Liki', slug: 'liki', version: '1.0.2', orientation: 'default', userInterfaceStyle: 'dark',
  icon: './artwork/play/store-icon-512.png',
  android: { package: 'jp.liki.pockethid', versionCode: 3,
    adaptiveIcon: { foregroundImage: './artwork/adaptive-icon-foreground.png',
      backgroundImage: './artwork/adaptive-icon-background.png', backgroundColor: '#10151c' } },
  ios: { bundleIdentifier: 'jp.liki.pockethid', supportsTablet: true },
  plugins: [
    'expo-dev-client',
    ['expo-build-properties', { android: { minSdkVersion: 28, compileSdkVersion: 37, targetSdkVersion: 36, cmakeVersion: '3.31.6' } }],
    './plugins/with-liki',
  ],
};
