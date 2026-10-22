# Likiのビルドと導入手順

現在のReact Native / Expo版を対象に、Android APKの導入、WindowsでのAndroid開発ビルド、EASでのiOS開発ビルドを説明します。接続後の操作は[使い方ガイド](usage.md)を参照してください。

## 1. 使うビルドを選ぶ

| 目的 | ビルド・入手方法 | Metro |
| --- | --- | --- |
| Androidで配布版を使う | GitHub Releasesの署名済みAPK | 不要 |
| Androidでコードを変更して試す | ローカルのdebug APK、またはEASの`development` | 必要 |
| AndroidでJSを同梱した内部検証用APKを作る | EASの`preview` | 不要 |
| WindowsからiPhoneで開発する | EASのiOS `development` | 必要 |

独自のHIDネイティブモジュールが必要なため、**Expo Goでは動作しません**。Bluetoothの確認には対応する実機を使います。iOSは16.4以上・arm64、Androidは9以上が対象ですが、Bluetooth機能の対応は[使い方ガイド](usage.md)の条件も確認してください。

## 2. Androidの配布APKを導入する

1. [GitHub Releases](https://github.com/hringdrifi/liki/releases)で対象バージョンのAndroid APKを取得します。
2. APKをAndroid端末で開きます。要求された場合は、使用したブラウザーやファイルアプリに対して「この提供元を許可」などのインストール許可を設定します。
3. インストール後にLikiを起動し、Bluetooth接続・公開の権限を許可します。
4. [使い方ガイド](usage.md)に従い、PCのBluetooth設定からペアリングします。

配布APKはJavaScriptを同梱しているため、Node.jsやMetroは不要です。既存アプリへ上書きする場合はパッケージ名・署名の互換性が必要です。署名不一致で導入できない場合、アンインストールすると端末内の設定・保存レイアウトが消えるため、その影響を確認してから対応を判断してください。

## 3. ソースと依存関係を準備する

以降のコマンドはPowerShellを想定します。Git、Node.js 24とnpmを用意し、ソースを取得します。すでにチェックアウト済みの場合はそのリポジトリを使ってください。

```powershell
git clone https://github.com/hringdrifi/liki.git
Set-Location liki
npm ci
npm run typecheck
npm run lint
npm test
```

バージョン固定には`package-lock.json`を使います。チェックが失敗した場合は原因を確認し、成功扱いにして次へ進めないでください。

主な設定ファイルは次のとおりです。

| ファイル | 内容 |
| --- | --- |
| `app.config.js` | アプリ識別子・バージョン・Android SDK設定・Expoプロジェクト設定 |
| `eas.json` | `development`と`preview`のビルド設定 |
| `modules/liki-hid/` | Kotlin/SwiftのHID実装とレイアウト資産 |
| `plugins/with-liki.js` | Androidの権限・入力イベント処理などを生成コードへ反映 |

## 4. WindowsでAndroid開発APKをビルドする

### 必要なツール

- JDK 21
- Android SDK Platform 37、Build-Tools 37、Platform-Tools（adb）
- Android SDKのCMake 3.31.6

現在の設定は`compileSdk 37`、`targetSdk 36`、`minSdk 28`です。CMakeは`app.config.js`から3.31.6を指定しています。Android StudioのSDK Managerから必要なSDK・ツールを導入し、ライセンスを確認してください。

### Androidプロジェクトを生成してビルドする

リポジトリ直下で実行します。パスは自分の環境に合わせます。

```powershell
$env:JAVA_HOME = '<JDK 21のインストール先>'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
npx expo prebuild --platform android --no-install
Push-Location android
try {
    .\gradlew.bat :app:assembleDebug
} finally {
    Pop-Location
}
```

成功時のAPKは`android/app/build/outputs/apk/debug/app-debug.apk`です。現在はExpoから`android/`を生成するため、旧Android版のリポジトリ直下にあるGradle Wrapperを前提にしないでください。必要なネイティブ変更はローカルモジュールやconfig pluginへ反映します。

### USBで導入する

Android端末で開発者向けオプションとUSBデバッグを有効にし、PCへ接続して端末側の接続許可を承認します。

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb devices -l
& $adb install -r 'android/app/build/outputs/apk/debug/app-debug.apk'
```

複数の端末が表示される場合は、各adbコマンドに`-s <端末のシリアル>`を付けて対象を指定します。`-r`は既存アプリを保持した上書きですが、署名不一致では失敗します。

### USB経由でMetroを起動する

リポジトリ直下で実行します。

```powershell
& $adb reverse tcp:8081 tcp:8081
$env:NODE_OPTIONS = '--dns-result-order=ipv4first'
npm start -- --localhost --port 8081
```

Metroを起動したままLikiの開発クライアントを開きます。必要なら接続先に`http://127.0.0.1:8081`を指定します。USBは開発画面の読み込みに使い、PCへのHID入力はBluetoothで送ります。

## 5. EASでAndroid APKを作る

ローカルAndroid SDKを使わず、EASのクラウドで作成する方法です。ソースとnpm依存関係を準備した後、Expoへログインします。

```powershell
npx --yes eas-cli@latest whoami
```

未ログインの場合は次を実行し、ブラウザーで認証します。

```powershell
npx --yes eas-cli@latest login --device
```

既存プロジェクトのメンバーは設定済みのEASプロジェクトを使います。自分のフォークでビルドする場合は、自分がアクセスできるExpoプロジェクトと署名環境を用意してください。

開発クライアントを作る場合:

```powershell
npx --yes eas-cli@latest build --platform android --profile development
```

Metroなしで起動する内部検証用APKを作る場合:

```powershell
npx --yes eas-cli@latest build --platform android --profile preview
```

完了後にEASの案内からAPKを取得し、端末へインストールします。既存の署名鍵があるプロジェクトでは、既存APKとの更新互換性を確認してください。`preview`で作成したことだけでは、GitHub配布版と署名が同じであるとは限りません。

## 6. WindowsからiOS開発ビルドを作る

WindowsではiOSのネイティブビルドをローカル実行せず、EAS Buildを使います。Expoアカウント、Apple Developerの署名環境、実機iPhoneが必要です。

### iPhoneを登録する

Expoへのログイン後、リポジトリ直下で実行します。

```powershell
npx --yes eas-cli@latest device:create
```

登録方式はWebsiteを選び、表示されたURLを対象iPhoneのSafariで開いて登録します。端末登録とビルドで同じApple Developerチームを使用します。ログイン情報・2段階認証は本人が入力してください。

### ビルドして導入する

```powershell
npx --yes eas-cli@latest build --platform ios --profile development
```

`development`はAd Hocによる内部配布用の開発ビルドです。ビルド時のプロビジョニングプロファイルに含まれる端末だけにインストールできます。新しい端末を登録した場合は、プロファイルを更新して再ビルドします。

ビルド完了後、インストールページを対象iPhoneのSafariで開き、Installを押します。iOSに要求された場合は開発者モードを有効にし、画面の案内に従います。Appleの利用規約更新によって処理が止まった場合は、Account Holderによる確認・承諾が必要です。

このリポジトリでは署名済みIPAを公開配布しません。各自の署名環境を使用し、IPA・署名用資格情報・端末識別情報・開発ビルドのダウンロードリンクを公開ドキュメントへ含めないでください。

## 7. Wi-Fi経由で開発クライアントを起動する

Android・iOS共通です。スマホと開発PCを同じネットワークへ接続し、PCの**Wi-Fi側のIPv4アドレス**を確認します。有線LAN・VPNなど別のアダプターのアドレスと取り違えないようにします。

リポジトリ直下で実行します。

```powershell
$env:NODE_OPTIONS = '--dns-result-order=ipv4first'
$env:REACT_NATIVE_PACKAGER_HOSTNAME = '<PCのWi-Fi側IPv4>'
npm start -- --lan --port 8081
```

Likiの開発クライアントで`http://<PCのWi-Fi側IPv4>:8081`を開きます。読み込めない場合はネットワーク間の隔離や、ファイアウォールのNode.jsへのTCP受信・ローカルポート8081の許可を確認します。必要な許可はスマホからの接続に限定し、ファイアウォール全体を無効にしないでください。

## 8. 変更後に必要な作業

| 変更した内容 | 反映方法 |
| --- | --- |
| JS/TSの画面や操作ロジック | Metroの再読み込み |
| Kotlin・Swift・ネイティブ依存関係 | ネイティブアプリを再ビルドして上書きインストール |
| config plugin・権限・Info.plistなど | ネイティブ設定の再生成と再ビルド・上書きインストール |
| JS同梱の配布・preview APK | JS変更も含めてAPKを再ビルド |

バージョンは`app.config.js`と`package.json`・`package-lock.json`で管理します。Androidリリース時は`versionCode`も増やします。この手順は開発・内部検証用の導入を対象とし、ストア公開やGitHub Releaseの作成は含みません。

## 9. 実機で確認する

ビルド成功、インストール成功、Bluetooth入力成功は別々に確認します。

1. Likiが起動し、標準キーボードと標準トラックパッドを表示できる。
2. Bluetooth権限を許可してPCとペアリング・接続できる。
3. PCの入力欄へ文字・修飾キーを送れる。
4. ポインター移動、クリック、ドラッグ、スクロールが動く。
5. 外部機器を使う場合は、中継モードで実際の入力を確認する。
6. 切断・再接続後の状態を確認し、キーやボタンの押しっぱなしが残らない。

Android BLEの再起動後の再接続とiOSの一部入力には未解決・未検証の項目があります。[使い方ガイド](usage.md)と[BLEの検証記録](android-ble-hid.md)を参照してください。

## 参考

- [Expo: Development builds](https://docs.expo.dev/develop/development-builds/introduction/)
- [Expo: Internal distribution](https://docs.expo.dev/build/internal-distribution/)
- [Expo移植メモと過去の検証記録](expo-port.md)
