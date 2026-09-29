# Android / iOS development

The existing Android app uses platform Views and `BluetoothHidDevice`. The
`shared` module introduces Compose Multiplatform for UI that can run on both
Android and iOS. Android launches `SharedMainActivity` and uses its native
`HidController` for pairing, connection, and HID reports. The common UI
currently covers connection, KLE keyboard rendering, four layers and bindings,
saved and imported layouts, trackpad gestures, and the Android settings. The
original Android screen remains available through **従来画面を開く**. There is no
iOS app entry point yet. ZMK integration is maintained on the separate
`codex/zmk-connection-support` branch and is outside this branch's Android
parity work.

## Bluetooth roles

* Android: `BluetoothHidDevice` exposes the Bluetooth Classic HID Device
  profile. Its paired host list and connect/disconnect behavior remain native.
* iOS: Bluetooth Classic HID Device is not exposed to third party apps. The
  viable approach is a CoreBluetooth peripheral with HID over GATT (HOGP).
  This requires a native GATT HID service, report map, encrypted characteristics,
  notification subscriptions, bonding, and an initial input report on subscribe.
  The iOS implementation and host compatibility have not yet been verified.
* The common UI communicates with native backends through `HidPort`. The
  Android adapter uses the existing `HidController` for keyboard, consumer,
  mouse, scroll, and zoom reports. The iOS adapter is still outstanding.

The [darwin-bt-remote implementation](https://github.com/jqssun/darwin-bt-remote#implementation)
uses Bluetooth Classic HIDP on **macOS** and BLE HOGP on **iOS**. Its code is
AGPL-3.0-only, so it is a behavioral reference rather than source to copy into
an AGPL-3.0-or-later project.

## Porting inventory

The Android adapter currently reuses the KLE parser, binding model, saved
layout store, settings, and Bluetooth transport from `app/`. The shared UI
renders KLE key geometry and labels, layer binding categories, modifier state,
and the trackpad. Android host checks have covered key input, menu swipe,
editing layer restoration, Back navigation, pointer movement, left click, and
two-finger scrolling. Other gestures and HID actions still need real-host
verification. Keep the legacy screen available until that verification is done.
The Android implementation under `app/` remains the behavior reference.

An iOS app must be built on macOS with Xcode, then tested on a physical iPhone
against real Bluetooth hosts. Windows can compile the Android target of
`shared`, but cannot build the iOS framework or validate HOGP interoperability.

## Local verification

With JDK 21 and Android SDK 36 configured:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME = 'C:\Users\ito\AppData\Local\Android\Sdk'
.\gradlew.bat :shared:compileDebugKotlinAndroid
```
