# Android / iOS development

The existing Android app uses platform Views and `BluetoothHidDevice`. The
`shared` module introduces Compose Multiplatform for UI that can run on both
Android and iOS. It currently contains only a basic keyboard and trackpad
screen and is not yet wired into either application entry point. The existing
Android launcher continues to use its original UI and behavior.

## Bluetooth roles

* Android: `BluetoothHidDevice` exposes the Bluetooth Classic HID Device
  profile. Its paired host list and connect/disconnect behavior remain native.
* iOS: Bluetooth Classic HID Device is not exposed to third party apps. The
  viable approach is a CoreBluetooth peripheral with HID over GATT (HOGP).
  This requires a native GATT HID service, report map, encrypted characteristics,
  notification subscriptions, bonding, and an initial input report on subscribe.
  The iOS implementation and host compatibility have not yet been verified.
* A common UI must communicate with these backends through a platform interface.
  `HidPort` is the beginning of that contract; it needs device discovery,
  connection state, consumer reports, held keys, scrolling, and error handling
  before it can replace the Android UI.

The [darwin-bt-remote implementation](https://github.com/jqssun/darwin-bt-remote#implementation)
uses Bluetooth Classic HIDP on **macOS** and BLE HOGP on **iOS**. Its code is
AGPL-3.0-only, so it is a behavioral reference rather than source to copy into
an AGPL-3.0-or-later project.

## Porting inventory

The shared implementation still needs the KLE parser and renderer, layers and
bindings, saved layouts, modifiers, keyboard gestures, full trackpad gestures,
settings, and privacy view. Native platform adapters need pairing, connection
selection, file import, settings storage, and the Bluetooth transports. The
Android implementation under `app/` remains the behavior reference.

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
