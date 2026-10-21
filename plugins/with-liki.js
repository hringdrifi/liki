const { withAndroidManifest, withMainActivity } = require('expo/config-plugins');

module.exports = function withLiki(config) {
  config = withAndroidManifest(config, config => {
    const manifest = config.modResults.manifest;
    const permissions = manifest['uses-permission'] ??= [];
    for (const name of ['BLUETOOTH', 'BLUETOOTH_ADMIN', 'BLUETOOTH_CONNECT', 'BLUETOOTH_ADVERTISE']) {
      const full = `android.permission.${name}`;
      let entry = permissions.find(p => p.$['android:name'] === full);
      if (!entry) { entry = { $: { 'android:name': full } }; permissions.push(entry); }
      if (name === 'BLUETOOTH' || name === 'BLUETOOTH_ADMIN') entry.$['android:maxSdkVersion'] = '30';
    }
    const features = manifest['uses-feature'] ??= [];
    if (!features.some(f => f.$['android:name'] === 'android.hardware.bluetooth')) features.push({ $: {
      'android:name': 'android.hardware.bluetooth', 'android:required': 'true',
    } });
    if (!features.some(f => f.$['android:name'] === 'android.hardware.bluetooth_le')) features.push({ $: {
      'android:name': 'android.hardware.bluetooth_le', 'android:required': 'true',
    } });
    return config;
  });
  return withMainActivity(config, config => {
    let source = config.modResults.contents;
    if (config.modResults.language !== 'kt') throw Error('Liki requires the generated Kotlin ReactActivity');
    if (!source.includes('outState.putString("liki_session"')) {
      if (source.includes('override fun onSaveInstanceState')) throw Error('ReactActivity already has an onSaveInstanceState override; integrate Liki state before prebuilding');
      const create = /override fun onCreate\(savedInstanceState: Bundle\?\)\s*\{/;
      if (!create.test(source)) throw Error('Cannot find ReactActivity.onCreate to restore Liki state');
      source = source.replace(create, match => `${match}\n    jp.liki.pockethid.LikiSession.value = savedInstanceState?.getString("liki_session")\n    jp.liki.pockethid.LikiSession.reconnectAddress = savedInstanceState?.getString("liki_reconnect")`);
      const end = source.lastIndexOf('}');
      source = `${source.slice(0, end)}\n  override fun onSaveInstanceState(outState: Bundle) {\n    outState.putString("liki_session", jp.liki.pockethid.LikiSession.value)\n    outState.putString("liki_reconnect", jp.liki.pockethid.LikiSession.reconnectAddress ?: jp.liki.pockethid.LikiSession.connectedAddress)\n    super.onSaveInstanceState(outState)\n  }\n${source.slice(end)}`;
    }
    if (!source.includes('LikiKeyboardRelay.dispatchKeyEvent')) {
      if (source.includes('override fun dispatchKeyEvent') || source.includes('override fun onWindowFocusChanged')) {
        throw Error('ReactActivity already handles keyboard events or window focus; integrate keyboard relay before prebuilding');
      }
      const end = source.lastIndexOf('}');
      source = `${source.slice(0, end)}
  override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
    if (jp.liki.pockethid.LikiKeyboardRelay.dispatchKeyEvent(event)) return true
    return super.dispatchKeyEvent(event)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    jp.liki.pockethid.LikiKeyboardRelay.onWindowFocusChanged(hasFocus)
  }
${source.slice(end)}`;
    }
    if (!source.includes('LikiKeyboardRelay.dispatchGenericMotionEvent')) {
      if (source.includes('override fun dispatchGenericMotionEvent')) throw Error('ReactActivity already handles mouse events; integrate mouse relay before prebuilding');
      const end = source.lastIndexOf('}');
      source = `${source.slice(0, end)}
  override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
    if (jp.liki.pockethid.LikiKeyboardRelay.dispatchGenericMotionEvent(event)) return true
    return super.dispatchGenericMotionEvent(event)
  }
${source.slice(end)}`;
    }
    config.modResults.contents = source;
    return config;
  });
};
