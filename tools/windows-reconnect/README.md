# Liki 再接続サポート（Windows）

登録したペアリング済みBLE端末への接続を要求・維持する、Windows用の通知領域アプリ。
Android/iOSのLikiを起動し直したとき、WindowsがHID接続を再開するための接続経路を確保する。
入力の受信・中継、HIDレポートの購読、ペアリングの作成・削除は行わない。

## 使い方

1. 最初にLikiを公開した状態でWindowsとペアリングし、PCへの入力を確認する。
2. `LikiReconnect.exe`を起動し、自動で接続する端末にチェックを付ける。
3. 起動したままLikiを利用する。画面の×は通知領域への移動。終了は通知領域のメニューから行う。

チェックを外すと、その端末への支援を停止して接続維持用の参照を解放する。
Windowsのペアリングは削除しない。登録は端末の識別子で保存するため、同名の端末も区別する。
Windowsで削除して再ペアリングした場合は「一覧を更新」し、必要に応じて新しい端末を登録する。

「接続済み」はBLEの接続状態であり、Likiのキー入力成功を保証する表示ではない。
「Likiの公開待ち」の場合はスマホでLikiを起動し、BLEペアリング待機を開始する。
「選択端末を再確認」は、選択した登録端末の再試行間隔をリセットしてサービスを再探索する。

登録ファイルは `%LOCALAPPDATA%\LikiReconnect\devices.json`。端末の識別子と表示名だけを保存する。
自動起動の登録やWindows設定の変更は行わない。アプリの起動に管理者権限は不要。

## 接続処理

- 登録端末ごとに`BluetoothLEDevice`と`GattSession`を保持し、`MaintainConnection=true`を設定する。
- 未接続時は`GetGattServicesAsync(Uncached)`で接続・探索を要求する。
- 失敗時は2、4、8、16、30秒の間隔で再試行し、その後は30秒を上限とする。処理時間は別途かかる。
- サービス変更時は短時間待って再探索し、通常の接続済み端末には定期的な再探索を行わない。
- 端末ごとにGATT処理を直列化し、登録解除・終了時には維持要求と参照を解放する。

## ビルド

Windows 10 version 1703以降 / Windows 11と.NET Framework 4.8を対象とする。
ビルドにはWindows SDK（Windows.winmd）と.NET FrameworkのC#コンパイラ・参照アセンブリが必要。
外部パッケージは使用しない。

```powershell
.\tools\windows-reconnect\build.ps1 -Test
```

出力はGit除外済みの `build\windows-reconnect\LikiReconnect.exe`。
WindowsのBluetoothアクセスがセキュリティ製品等で制限されている場合は、その設定の確認が必要。

参照: [Microsoft: Bluetooth GATT Client](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client)

ライセンスはリポジトリのAGPL-3.0-or-laterに従う。
