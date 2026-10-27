# React Native + Expo の移植記録

この文書は2026年10月20〜21日の移植作業と当時の検証記録です。以下のバージョン・テスト件数・未検証項目は当時の状態を示します。現行版の導入には[ビルドと導入手順](build-and-install.md)、操作と検証範囲には[使い方ガイド](usage.md)と[BLEの検証記録](android-ble-hid.md)を参照してください。

ブランチ: `codex/expo-faithful-port`。基点: main `2c2f3f4`。

Flutter版は `codex/flutter-faithful-port` / `1987ee2` に保存し、Android端末へのインストールと起動を確認してから、このブランチの作業を開始した。

## 構成と機能

- `App.tsx`: 接続、右端メニュー、レイアウト選択、名前付き保存・復元・削除、4レイヤーの割り当て編集、修飾式。
- `src/Keyboard.tsx`: SVGによるKLE描画。回転、変形キー、ラベル、Ghosted領域、レイヤーごとの枠色。
- `src/model.ts` / `gestures.ts`: 修飾キー、4レイヤー、ワンショット、押している間の切り替え、表示のパン/ズーム、全トラックパッド操作。
- `src/Settings.tsx`: 全設定をExpo版専用の保存領域に保存する。
- `modules/liki-hid/`: Android HID、権限、公開、ファイル選択、KLE解析と設定保存を持つ独立したローカルExpoモジュール。旧画面・旧アプリへの参照はない。
- `plugins/with-liki.js`: Bluetooth権限、機能宣言、Androidの画面再生成時の状態保存。

パッケージ名は `jp.liki.pockethid`、versionName/versionCode は `1.0.2` / `3` のまま。ライセンスは GPL-3.0-or-later。

## Windowsでの開発

Node 24とJDK 21を使用する。生成元のバージョンはExpo SDK 58 / React Native 0.88 / React 19.3。実際の依存関係は `package-lock.json` に固定する。

Expo 58の依存ライブラリが必要とする `compileSdk 37` とBuild-Tools 37を使用する。CMake 3.31.6をAndroid SDKに導入する（Android StudioのSDK Toolsで「Show Package Details」から選択できる）。`app.config.js` の `expo-build-properties` で同じバージョンを指定し、長いパスに対応した同梱Ninjaを使用する。`minSdk 28` と `targetSdk 36` はmainの設定を維持する。

1. リポジトリ直下で `npm ci`。
2. `npm run lint`、`npm run typecheck` と `npm test`。
3. `npx expo prebuild --platform android --no-install`。Androidの生成物はGit除外し、再生成時にも必要な変更はconfig pluginから適用する。
4. JDK 21と `ANDROID_HOME` を設定し、`android/` で `gradlew.bat :app:assembleDebug`。
5. 開発APKを入れた端末向けにリポジトリ直下で `npm start`（`expo start --dev-client`）。USB接続でMetroを使う場合は `adb reverse tcp:8081 tcp:8081`。

APKの出力先は `android/app/build/outputs/apk/debug/app-debug.apk`。Expo GoではローカルHIDモジュールを呼び出せない。

## Macを使わないEAS Build

リポジトリ直下でExpoアカウントにログインし、設定済みのEASプロジェクトへのアクセスを確認してから実行する。自分のフォークでは、自分がアクセスできるExpoプロジェクトを設定する。

```text
npx eas-cli@latest build --platform android --profile development
```

`development` は専用の開発クライアント。JS/TSの変更はMetroから読み込める。Kotlinやネイティブ依存関係を変更した場合は開発クライアントを再ビルドする。`preview` プロファイルはJSを同梱した内部配布用APKに使う。

EASはリポジトリ直下で実行する。必要なネイティブコードとKLEファイルは `modules/liki-hid/`、アイコンは `artwork/` に含まれる。鍵やローカル設定はGit除外を維持する。

iOS用の開発ビルドはEASとApple Developerの署名設定を使ってクラウドで作成する。2026-10-20にiPhone 11向けの未対応表示版をビルド・インストール・起動した。その後のBLE HID追加の詳細と検証範囲は末尾を参照。

## 検証の範囲

自動テストはAndroid境界を模擬して、修飾キー、全レイヤー、押している指を先に離したときのコード入力、編集の保存/キャンセル、保存レイアウトの保存・復元、回転キーと表示ズーム、トラックパッドの各操作を確認する。

Androidの実際のPCへの転送結果とiOSのHID送信結果は別途確認が必要。初期移植検証後、端末のFlutter版をExpo版に入れ替え、接続画面の起動まで確認した。

## 2026-10-20 の検証結果（Ninja更新前）

- `npm run lint`: 成功。
- `npm run typecheck`: 成功。
- `npm test`: 15件成功。描画時の状態更新を避け、画面回転・手動ピッチ・表示リセットの照合も含む。
- `npx expo install --check`: 対応バージョンと一致。
- `npx expo export --platform android --output-dir dist`: 成功。Hermesバンドル約1.8MBを生成。
- `gradlew.bat :liki-hid:compileDebugKotlin`: 構成整理後も成功。旧アプリへのソース参照・Activity除外設定なしで、独立したモジュールをコンパイルした。
- `gradlew.bat :app:assembleDebug`: **失敗、APK未作成**。`react-native-safe-area-context` のC++コード生成物でNinjaが `Filename longer than 260 characters` と報告する。現在のブロッカーはWindowsのパス長制限。

コンパイル用SDKを37に上げたことで、当初のExpoライブラリのAARメタデータ検査エラーは解消している。全体のAPKビルド成功や、Expo版の実機動作確認を意味するものではない。

使用したCMake 3.22.1付属のNinja 1.10.2は長いパスに対応しない。リポジトリ直下への構成整理後も同じNinjaエラーでAPKビルドが失敗した。lint、型チェック、15件のテスト、Androidプロジェクト再生成とHermesバンドル生成は整理後も成功した。

ユーザー指示により、Android公式配布のCMake 3.31.6（同梱Ninja 1.12.1）を導入し、`expo-build-properties` の `android.cmakeVersion` で指定した。この選択は [Expo公式の説明](https://github.com/expo/expo/blob/main/packages/expo-modules-autolinking/android/expo-gradle-plugin/README.md) に従っている。配布アーカイブはGoogleのSDKリポジトリに記載されたSHA-1で検証した。生成されたCMakeコマンドが `cmake/3.31.6/bin/ninja.exe` を参照することも確認した。

## Ninja更新後の検証（2026-10-20）

- `npm run lint` / `npm run typecheck`: 成功。
- `npx expo prebuild --platform android --no-install`: 成功。生成された `android/gradle.properties` に `android.cmakeVersion=3.31.6` を確認。
- CMake 3.31.6 / Ninja 1.12.1で `gradlew.bat :app:assembleDebug`: **成功**。arm64-v8a、armeabi-v7a、x86、x86_64のビルドを完了し、パス長エラーを解消した。
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`。applicationId `jp.liki.pockethid`、versionName/versionCode `1.0.2` / `3` を出力メタデータで確認。

このAPKはexpo-dev-clientの開発用で、画面の実行にはMetroを起動する。Android端末（2407FPN8ER）へのインストール、Metroからの画面読み込み、接続画面の「準備完了」表示を確認した。PCへのHID送信は未確認。

初回のFlutter版からの入れ替えは、デバッグ署名が異なり `INSTALL_FAILED_UPDATE_INCOMPATIBLE` になるため、許可済みの旧設定削除を伴ってFlutter版をアンインストールし、`adb install --no-streaming` でExpo版をインストールした。端末側の確認でもインストール許可が必要だった。

このWindows環境では `--localhost` のMetroがIPv6の `::1` だけで待ち受けたため、起動するプロセスで `$env:NODE_OPTIONS = '--dns-result-order=ipv4first'` を設定してから `npm start -- --localhost` を実行し、`127.0.0.1:8081` の応答を確認した。`adb reverse tcp:8081 tcp:8081` を設定し、開発クライアントを `exp+liki://expo-development-client/?url=http%3A%2F%2F127.0.0.1%3A8081` で開いた。

## 設定削除時のnull変換エラー修正（2026-10-20）

レイアウト選択や保存レイアウト削除では、設定を消すために `null` を送る。汎用の `invoke` の `Map<String, Any?>` の中へ設定マップを入れると、Expo 58のAny変換が内側のnullをObjectとして扱い、`Value is null, expected an Object` で拒否した。修正前の実機で同じエラーを再現した。

設定保存だけを `preferences(Map<String, String?>)` という型付きのExpoモジュール関数にし、JavaScript側もこの関数を呼ぶように修正した。保存データや削除の意味は変更していない。

lint・型チェック・15件のテスト、APKビルドが成功。修正版APK（231,090,419 bytes）を設定を保持して実機に上書きインストールした。実機のネイティブ境界で文字列の保存、nullによる削除、既存の選択レイアウトを変えないことを確認し、エラー表示のない接続画面の起動も確認した。

## レイアウト切り替え後の空白画面修正（2026-10-20）

レイアウト読み込み時にGeometryを作り直すと、画面の幅・高さが0に戻っていた。同じサイズのKeyboard Viewは `onLayout` を再通知しないため、その後も0のまま描画してキーが画面外に配置された。読み込み時に直前の表示領域の寸法を引き継いで再計算するよう修正した。

画面サイズ通知が一度しか来ない状態でレイアウトを連続変更・保存レイアウト復元しても、キーが表示領域内に残りヒットテストできる回帰テストを追加した。このテストが修正前に幅0で失敗し、修正後はlint・型チェックと全16件のテストが成功することを確認した。

実機のアプリをリロードし、標準キーボード87キーと標準トラックパッド13領域の切り替え後も表示領域が維持され、全キーの中心が表示範囲内にあることを確認した。確認後は元のカスタムレイアウトと設定に戻し、キーが描画されることをスクリーンショットでも確認した。JavaScript側の修正のため、実機にはMetro経由で反映した。

## キー文字の描画修正（2026-10-20）

SVG内の文字を0.1〜0.3程度の極小フォントで計測してから拡大していたため、Android上で字間や文字位置が崩れていた。元のAndroid Canvas実装と同様、文字の座標とフォントサイズを表示倍率で換算し、文字グループでは倍率を相殺して実際の表示サイズで字形を計測するよう修正した。キーの配置・回転・KLEの文字位置指定は維持している。

全角文字も考慮して文字幅の目安を求め、長い日本語の割り当て名は2行に分けてキー内に収める。通常のKLEラベル、割り当て名、継承表示を対象とした。

lint・型チェックが成功。実機の回転キーを含むカスタムレイアウトで、文字位置とLower / Enter / Raiseの字間の改善を画面で確認した。割り当て名表示では長い日本語名が枠内に収まることも確認し、確認後は元の表示設定・レイヤーへ戻した。修正はMetro経由で実機に反映している。

## カメラ側からメニューを開けない問題の修正（2026-10-20）

メニューのスワイプ領域がSafeArea内の操作画面に置かれていたため、横向きでカメラが右側に来ると物理的な画面端から触れなかった。スワイプ領域をSafeAreaView直下に移し、右側の余白分も含めて画面端まで広げた。キーボードと目印はカメラを避ける位置を維持する。

lint・型チェックが成功し、Metro経由で実機へ反映した。実機の左右の横向きでネイティブViewを測定し、画面幅834.46dpに対して操作領域の右端が同じ834.46dpになることを確認した。カメラ側では幅66.46dp、反対側では24dpとなる。カメラ側の画面端から左へ50dp移動するタッチコールバックでメニューが開くことも確認した。端末上で指を使う実際のスワイプは未確認。検証後に端末接続が切れたため、画面向き設定は検証用の横向き（2）のまま。元の自動（0）へ戻す処理は未完了。

## 初回起動のキー割り当て修正（2026-10-20）

LayoutRepositoryが未保存の割り当てを空のJSONとして返し、初期キーボードを押すと「メニューのキー割り当て編集で設定してください」と表示されていた。保存値がないときだけ既存の標準割り当て推定を使うよう修正した。保存済みの割り当ては、明示的に空にしたものも含めて保持する。

修正前の実機で初期割り当て0件・標準定義87件を確認した。修正後は既存16テストとAPKビルド（23秒）が成功し、設定を保持して上書きインストールした。実機で保存値を一時的に切り替え、未保存なら標準87件、保存済みの空JSON・編集値ならそのまま返すことを確認した。検証後は元の保存値へ復元した。実際のBluetoothキー入力はこの検証では送信していない。

## スプラッシュ画面（2026-10-20）

`expo-splash-screen` を追加し、背景を `#10151c`、画像を既存の透明な `artwork/adaptive-icon-foreground.png`、画像幅を180に設定した。文字、表示時間の延長、アニメーションは追加していない。prebuild・lint・型チェックが成功し、生成されたAndroidテーマの背景色・アイコン指定とSplashScreenManagerの登録を確認した。

JDK 21で `:app:assembleDebug` が9分で成功。既存APKと署名が一致することを確認し、226,565,611 bytesのAPKを `adb install --no-streaming -r` で設定を保持して上書きした。実機で濃紺背景・中央のアイコンを撮影して確認した。依存追加前から動いていたMetroを再起動し、アプリの接続画面「準備完了」まで表示できた。開発ビルドはスプラッシュの全設定を再現しないため、配布時の見た目はリリースビルドでの確認も必要。

依存追加時の監査結果は23件（high 15、moderate 8）。自動修正は実行していない。

## 依存関係の監査

2026-10-20 の `npm audit` では22件（high 15、moderate 7）が残っている。Expo/Metroなどの依存経路に `braces`、`node-forge`、`uuid` の指摘がある。自動修正にはExpo 44やReact Native 0.72への大幅なダウングレード提案も含まれるため、この移植では `audit fix --force` や依存関係の上書きを行っていない。対応は未解決として記録する。

## 旧設定の扱いと構成整理

ユーザー指定により、旧Android版・Flutter版の保存設定との互換処理を削除した。Expo版は専用の保存領域（expo_settings / expo_layout）を使い、初回起動時は初期設定になる。旧settings / layout保存領域はExpoネイティブ処理の初期化時に削除する。Expo版で新しく保存した設定・レイアウトは以後の起動でも保持する。旧レイアウトの自動更新・割り当てスキーマ移行・旧レイアウト資産は削除した。

Expoプロジェクトはリポジトリ直下に配置した。旧appとルートの旧Gradle構成は削除し、Bluetooth HID・KLE解析・設定保存と必要な資産だけをローカルモジュールに取り込んだ。旧実装の参照にはGit履歴とFlutterブランチを使う。

## iOS BLE HID（2026-10-21）

`darwin-bt-remote` の `ad7a76ce6132254fbd6085af87cea8d10aa8a82d` にあるHOGP実装を基に、`modules/liki-hid/ios/` にSwiftのExpoモジュールを追加した。出典と変更点を各ファイルに記載し、AGPL-3.0-onlyの原文を同ディレクトリに保存した。

- CoreBluetoothの接続イベント登録後に、Battery → Device Information → HIDの順にサービスを追加する。UUIDは128-bit表記、HID特性の順序・Report Reference・暗号化要求・購読直後の初期レポートは参照実装に従う。
- キー・修飾キー、マウス3ボタン・相対移動・スクロール、Consumer Controlを既存のJS操作に接続する。通知キューが満杯の場合はFIFOで再送し、キーの解放を保持する。バックグラウンド移行時と切断時は入力を解放する。
- iOSはPC側から接続する。iOSの接続画面ではAndroid専用のペアリング済みPC一覧と接続開始ボタンを表示しない。公開名は「Liki」。アプリを開いて使用する。バックグラウンド広告・復元は追加していない。
- AndroidのKLE仕様・標準割り当てをiOS用TypeScript境界に移植し、保存済みの空の割り当ても維持する。設定とレイアウトはUserDefaultsに保存し、ファイル選択はUIDocumentPickerを使う。表示密度から計算するキーピッチは概算。
- Bluetooth利用目的をInfo.plistに追加する。独自モジュール追加のため、既存のiOS開発クライアントは再ビルド・再インストールが必要。

型チェック、lint、既存16件とiOS追加4件のテスト、iOS Hermesバンドル生成が成功。Swiftのコンパイルと署名はEASで確認する。実際のBLEペアリング、PCへのキー・マウス送信、実機の設定操作は未検証であり、コードとJSテストの成功を実機動作確認とは扱わない。

初回のEASビルドはCocoaPodsのライセンスファイル拡張子の検査で失敗した。ファイル名を `.txt` に修正した後、再ビルドが **FINISHED** となり、Swiftコンパイル・アーカイブ・署名付きIPAの生成に成功した。開発用のApple署名設定とAd Hocプロファイルを使っている。署名付きIPAは公開リリースに含めない。BLE HID版のペアリング・入力送信の確認は未完了。

### iOSのレイアウト資産同梱修正（2026-10-21）

上記ビルドの実機で「レイアウト資産を開けません」が発生した。公開IPAを取得して検査すると `Payload/Liki.app/LikiHid.bundle/` にInfo.plistだけがあり、JSONは欠落していた。ios/配下のPodspecから `../android/` を指す指定では、Podのルート外の資産が同梱されなかった。

Podspecをモジュール直下へ移し、Swiftは `ios/*.swift`、資産は `android/src/main/assets/*.json` を指定した。Expoの `apple.podspecPath` に新しい位置を明記し、自動リンクの結果も確認した。修正版の再ビルドは **FINISHED**。そのIPA内の同じbundleに `default-kle.json`（969 bytes）と `ghosted-trackpad.json`（299 bytes）が存在し、双方のSHA-256が元ファイルと一致することを確認した。修正版の実機への上書きインストールと初期化の再確認はユーザー側で行う。BLEペアリングと入力送信も引き続き未確認。
