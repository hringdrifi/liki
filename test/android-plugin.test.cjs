const test = require('node:test');
const assert = require('node:assert/strict');
const withLiki = require('../plugins/with-liki');

async function activity(source) {
  const config = withLiki({ name: 'Liki', slug: 'liki' });
  const result = await config.mods.android.mainActivity({ ...config,
    modResults: { language: 'kt', contents: source },
    modRequest: { platform: 'android', modName: 'mainActivity', projectRoot: process.cwd() },
  });
  return result.modResults.contents;
}

test('Android prebuild adds relay hooks once, including to an already generated Liki activity', async () => {
  const first = await activity('class MainActivity { override fun onCreate(savedInstanceState: Bundle?) {} }');
  assert.match(first, /LikiKeyboardRelay.dispatchKeyEvent/);
  assert.match(first, /LikiKeyboardRelay.onWindowFocusChanged/);
  assert.match(first, /LikiKeyboardRelay.dispatchGenericMotionEvent/);
  assert.equal(await activity(first), first);
  const previous = 'class MainActivity { override fun onSaveInstanceState(outState: Bundle) { outState.putString("liki_session", "") } }';
  assert.match(await activity(previous), /LikiKeyboardRelay.dispatchKeyEvent/);
});

test('Android prebuild upgrades a keyboard-only activity and preserves mouse handler ownership', async () => {
  const previous = 'class MainActivity { override fun onSaveInstanceState(outState: Bundle) { outState.putString("liki_session", "") } fun dispatch() { LikiKeyboardRelay.dispatchKeyEvent(event) } }';
  const upgraded = await activity(previous);
  assert.match(upgraded, /LikiKeyboardRelay.dispatchGenericMotionEvent/);
  assert.equal(await activity(upgraded), upgraded);
  await assert.rejects(activity(previous.replace('fun dispatch()', 'override fun dispatchGenericMotionEvent()')), /already handles mouse events/);
});

test('Android prebuild reports an existing keyboard handler instead of replacing it', async () => {
  const previous = 'class MainActivity { override fun onSaveInstanceState(outState: Bundle) { outState.putString("liki_session", "") } override fun dispatchKeyEvent(event: KeyEvent) {} }';
  await assert.rejects(activity(previous), /already handles keyboard events/);
});
