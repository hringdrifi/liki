import ExpoModulesCore
import CoreBluetooth
import Combine
import UniformTypeIdentifiers
import UIKit

public class LikiHidModule: Module {
    private var bridge: LikiBridge?

    @MainActor private func backend() -> LikiBridge {
        if let bridge { return bridge }
        let created = LikiBridge { [weak self] status in self?.sendEvent("statusChanged", status) }
        bridge = created
        return created
    }

    public func definition() -> ModuleDefinition {
        Name("LikiHid")
        Events("statusChanged")
        AsyncFunction("invoke") { (method: String, args: [String: Any]) -> Any? in
            try MainActor.assumeIsolated { try self.backend().invoke(method, args) }
        }.runOnQueue(.main)
        AsyncFunction("preferences") { (values: [String: String?]) in
            MainActor.assumeIsolated { self.backend().savePreferences(values) }
        }.runOnQueue(.main)
        AsyncFunction("pickFile") { (promise: Promise) in
            MainActor.assumeIsolated { self.backend().pickFile(promise) }
        }.runOnQueue(.main)
        OnAppEntersBackground {
            MainActor.assumeIsolated { self.bridge?.background() }
        }
        OnDestroy {
            DispatchQueue.main.async { self.bridge?.close(); self.bridge = nil }
        }
    }
}

// The Expo app delegate asks subscribers for the current supported orientation mask.
public class LikiAppDelegateSubscriber: ExpoAppDelegateSubscriber {
    static var orientations: UIInterfaceOrientationMask = .allButUpsideDown
    public func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Self.orientations
    }
}

@MainActor
private final class LikiBridge: NSObject, CBCentralManagerDelegate, UIDocumentPickerDelegate {
    private let defaults = UserDefaults.standard
    private let hid = HIDPeripheral()
    private var central: CBCentralManager?
    private var observation: AnyCancellable?
    private let emit: ([String: Any?]) -> Void
    private var pickerPromise: Promise?
    private var session: String?
    private var buttons = 0
    private var generation = 0
    private var nextKeyTime = Date.distantPast
    private var pendingX = 0, pendingY = 0
    private var mouseWork: DispatchWorkItem?
    private var nextMouseTime = Date.distantPast
    private var controls = false, settingsPage = false
    private var isStarting = false
    private var wasConnected = false
    private var errorMessage: String?
    private var relayRequested = false
    private var relayRemaps: [Int: [Int]] = [:]
    private var relayLayout = "none"
    private var relayLearning = false, studioConnected = false
    private var relayRemapEnabled = true
    private lazy var layerTransport = LikiLayerTransport { [weak self] event in self?.emit(event) }
    private lazy var studio: StudioTransport = StudioTransport { [weak self] event in
        guard let self else { return }
        if let state = event["studioState"] as? [String: Any] {
            let ready = state["ready"] as? Bool == true
            if self.studioConnected != ready { self.relay.clear(); self.studioConnected = ready }
        }
        self.emit(event)
    }
    private lazy var relay: KeyboardRelay = KeyboardRelay(connected: { [weak self] in self?.connected == true },
        send: { [weak self] modifiers, keys in
            guard let self else { return }
            self.emit(["relayInput": ["pressed": keys + (0..<8).filter { modifiers & (1 << $0) != 0 }.map { 0xe0 + $0 }, "consumer": 0]])
            if self.connected && !self.relayLearning {
                let mapped = KeyboardRemapper.translate(modifiers: modifiers, keys: keys, rules: self.studioConnected || !self.relayRemapEnabled ? [:] : self.relayRemaps, layout: self.relayLayout)
                self.hid.sendKeyboard(modifiers: mapped.modifiers, codes: mapped.keys); self.hid.sendConsumer(usage: mapped.consumer)
            }
        }, changed: { [weak self] in
            guard let self else { return }; self.publish()
            if self.relayRequested { self.studio.retry(); self.layerTransport.retry() }
        })
    private lazy var mouseRelay = MouseRelay(connected: { [weak self] in self?.connected == true },
        speed: { [weak self] in
            let value = (self?.settings["external_mouse_speed"] as? NSNumber)?.floatValue ?? 1
            return value.isFinite ? min(3, max(0.5, value)) : 1
        },
        send: { [weak self] buttons, x, y, wheel in
            guard let self, self.connected else { return }
            if self.buttons != buttons { self.flushMouse(); self.buttons = buttons; self.mouse(0, 0, 0) }
            if x != 0 || y != 0 { self.move(x, y) }
            if wheel != 0 { self.flushMouse(); self.mouse(0, 0, wheel) }
        }, changed: { [weak self] in self?.publish() })

    private let settingsDefaults: [String: Any] = [
        "sticky_modifiers": true, "kle_json_key_labels": true, "pinch_zoom": true, "drag_move_keyboard": true,
        "keep_screen_on": false, "fullscreen_controls": false, "touch_vibration": false, "screen_orientation": 0,
        "touch_vibration_strength": 1,
        "shift_border_color": 0x80cbc4 | -16777216,
        "key_pitch_mm": 0.0, "auto_key_scale_px": 0.0, "tap_to_click": true, "hold_to_drag": true,
        "two_finger_scroll": true, "trackpad_pinch_zoom": true, "two_finger_right_click": true, "tap_drag": true,
        "three_finger_middle_click": true, "pointer_speed": 1.0, "pointer_send_mode": 2,
        "external_mouse_speed": 1.0, "scroll_speed": 1.0, "reverse_scroll": false
    ]
    init(emit: @escaping ([String: Any?]) -> Void) {
        self.emit = emit
        super.init()
        observation = hid.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async { self?.publish() }
        }
        _ = relay
        _ = mouseRelay
    }
    private var preferences: [String: String] { defaults.dictionary(forKey: "liki.expo_layout") as? [String: String] ?? [:] }
    private var storedSettings: [String: Any] { defaults.dictionary(forKey: "liki.expo_settings") ?? [:] }
    private var settings: [String: Any] {
        var result = settingsDefaults.merging(storedSettings) { _, saved in saved }
        result["border_colors"] = [0x657789, 0x71a9e8, 0xedb369, 0xbe9ce7].enumerated().map { index, color in
            storedSettings["layer_border_color_\(index)"] as? Int ?? (color | -16777216)
        }
        return result
    }
    private var connected: Bool { hid.hasInputSubscriber }
    private var status: [String: Any?] {
        let message: String
        if let errorMessage { message = errorMessage }
        else if let error = hid.lastError { message = error }
        else if hid.state == .unauthorized { message = "設定でLikiのBluetoothアクセスを許可してください" }
        else if hid.state == .poweredOff { message = "Bluetoothをオンにしてください" }
        else if hid.state == .unsupported { message = "Bluetooth LEに対応していません" }
        else if connected { message = "BLE HID接続中" }
        else if hid.isAdvertising { message = "公開中です。PCのBluetooth設定からLikiを追加してください" }
        else if hid.isHIDServiceAdded { message = "準備完了" }
        else if central?.state == .poweredOn { message = "準備完了。BLEペアリング待機を押してください" }
        else { message = "Bluetoothを準備中…" }
        return ["message": message, "connected": connected, "connecting": false, "cancelling": false,
                "keyboardLeds": connected ? hid.keyboardLEDs.map { Int($0.rawValue) } : nil,
                "restoring": false, "registered": hid.isHIDServiceAdded,
                "relaySupported": true, "remapSupported": true, "keyboards": relay.devices + mouseRelay.devices,
                "devices": [], "address": hid.subscribedCentrals.keys.first?.uuidString,
                "lastAddress": preferences["last_device_address"]]
    }
    private func publish() {
        if wasConnected && !connected { releaseInputs() }
        wasConnected = connected
        emit(status)
    }
    func savePreferences(_ values: [String: String?]) {
        var saved = preferences
        for (key, value) in values { saved[key] = value }
        defaults.set(saved, forKey: "liki.expo_layout")
    }
    private func asset(_ name: String) throws -> String {
        guard ["default-kle.json", "ghosted-trackpad.json"].contains(name),
              let url = Bundle(for: LikiHidModule.self).url(forResource: "LikiHid", withExtension: "bundle"),
              let bundle = Bundle(url: url), let file = bundle.url(forResource: name, withExtension: nil) else {
            throw NSError(domain: "Liki", code: 1, userInfo: [NSLocalizedDescriptionKey: "レイアウト資産を開けません"])
        }
        return try String(contentsOf: file, encoding: .utf8)
    }
    func invoke(_ method: String, _ args: [String: Any]) throws -> Any? {
        func int(_ key: String) -> Int { (args[key] as? NSNumber)?.intValue ?? 0 }
        switch method {
        case "initialize":
            layerTransport.enable(false)
            relayRemaps = try KeyboardRemapper.parse(preferences["relay_remaps"] ?? "[]")
            relayLayout = preferences["relay_layout"] ?? "none"
            applyWindowSettings()
            let scale = UIScreen.main.scale
            return ["preferences": preferences, "settings": settings, "status": status, "session": session,
                    "density": scale, "xdpi": scale * 160,
                    "privacy": """
                    Liki プライバシーポリシー（2026年10月21日）

                    Likiは、Android端末・iPhone・iPadをBluetoothキーボードとマウス・トラックパッドとして使うアプリです。

                    Likiはキー入力の履歴を保存しません。キー操作とポインター操作は、利用者がBluetoothで接続した機器にのみ送信し、開発者や第三者のサーバーへ送信しません。

                    扱う情報と目的
                    Androidでは、接続先を選ぶためにペアリング済みBluetooth機器の名前とアドレスを読み取り、最後に接続した機器のアドレスを端末内に保存します。iOSでは、接続したBluetooth機器の識別子を接続状態の管理に使用します。操作設定、読み込んだキーボードレイアウト、キー割り当て、保存したレイアウトは、いずれのOSでも端末内に保存します。

                    外部への送信
                    キー操作とポインター操作は、接続先の機器へBluetooth HIDまたはBLE HIDで送信します。接続先での入力内容の扱いは、その機器と利用者の設定によります。Likiには広告・解析サービスを組み込んでおらず、入力内容や設定を開発者のサーバーへ収集する機能はありません。開発ビルドでは、利用者が接続した開発サーバー（Metro）からコードを読み込むためのネットワーク通信を行います。

                    保存、保護、削除
                    アプリが保存する情報は、AndroidまたはiOSのアプリ専用領域に置かれます。iOSではUserDefaultsなどを使用します。保存したレイアウトはアプリ内で個別に削除できます。その他のアプリ内データは、Androidの「アプリのストレージを消去」またはアンインストール、iOSの「Appを削除」で端末から削除できます。iOSの「Appを取り除く」ではデータが残ります。OSのバックアップ設定によっては、バックアップにアプリ内データが含まれる場合があります。Bluetooth通信の保護は、OSと接続先機器のペアリング設定に従います。

                    お問い合わせ: hringdrifi@gmail.com
                    """] as [String: Any?]
        case "session": session = args["value"] as? String
        case "status": return status
        #if DEBUG
        case "relayDiagnostics": return ["mouse": mouseRelay.diagnostics, "studio": studio.diagnostics]
        #endif
        case "studioRetry": studio.retry()
        case "zmkLayerRetry": layerTransport.retry()
        case "relayRemaps":
            let json = args["value"] as? String ?? "[]", rules = try KeyboardRemapper.parse(json)
            relay.clear(); relayRemaps = rules; savePreferences(["relay_remaps": json])
        case "relayLayout":
            let value = args["value"] as? String ?? ""
            guard ["none", "us-jis", "jis-us"].contains(value) else {
                throw NSError(domain: "Liki", code: 21, userInfo: [NSLocalizedDescriptionKey: "配列変換の設定が不正です"])
            }
            relay.clear(); relayLayout = value; savePreferences(["relay_layout": value])
        case "studioWrite":
            guard let bytes = args["bytes"] as? [NSNumber], !bytes.isEmpty else {
                throw NSError(domain: "Liki", code: 10, userInfo: [NSLocalizedDescriptionKey: "Studioの送信データを変換できません"])
            }
            try studio.write(bytes.map { UInt8(clamping: $0.intValue) }, id: int("id"), label: args["label"] as? String ?? "")
        case "studioComplete": try studio.complete(int("id"))
        case "asset": return try asset(args["name"] as? String ?? "")
        case "ready": prepare()
        case "discoverable": start()
        case "connect": throw NSError(domain: "Liki", code: 2, userInfo: [NSLocalizedDescriptionKey: "PCのBluetooth設定からLikiに接続してください"])
        case "disconnect", "cancelConnection":
            releaseInputs(); hid.disconnect(); central?.delegate = nil; central = nil; isStarting = false; publish()
        case "key": sequence { self.hid.sendKeyboard(modifiers: int("modifiers"), code: int("code")) }
        case "consumer": sequence { self.hid.sendConsumer(usage: int("usage")) }
        case "move": move(int("x"), int("y"))
        case "scroll": flushMouse(); mouse(0, 0, int("amount"))
        case "zoom":
            flushMouse()
            sequence { self.hid.sendKeyboard(modifiers: 1, code: 0); self.mouse(0, 0, int("amount")) }
        case "mouseDown": flushMouse(); buttons |= int("button"); mouse(0, 0, 0)
        case "mouseUp": flushMouse(); buttons &= ~int("button"); mouse(0, 0, 0)
        case "click":
            flushMouse(); buttons |= int("button"); mouse(0, 0, 0); buttons &= ~int("button"); mouse(0, 0, 0)
        case "page":
            controls = args["controls"] as? Bool ?? false; settingsPage = args["settings"] as? Bool ?? false
            let requested = args["relay"] as? Bool == true && controls && !settingsPage
            if relayRequested != requested { releaseInputs(); relayRequested = requested }
            relay.enable(requested); mouseRelay.enable(requested); applyWindowSettings()
            let learning = args["relayLearning"] as? Bool == true
            if relayLearning != learning { relay.clear(); relayLearning = learning }
            let remapEnabled = args["remap"] as? Bool != false
            if relayRemapEnabled != remapEnabled { relay.clear(); relayRemapEnabled = remapEnabled }
            studio.enable(args["studio"] as? Bool ?? requested)
            layerTransport.enable(args["studio"] as? Bool ?? requested)
        case "setting":
            let key = args["key"] as? String ?? ""
            guard settingsDefaults[key] != nil || (0...3).contains(where: { key == "layer_border_color_\($0)" }),
                  let value = args["value"] as? NSNumber, value.doubleValue.isFinite else {
                throw NSError(domain: "Liki", code: 3, userInfo: [NSLocalizedDescriptionKey: "設定値が不正です"])
            }
            var saved = storedSettings
            saved[key] = key == "touch_vibration_strength" ? NSNumber(value: min(2, max(0, value.intValue))) : value
            defaults.set(saved, forKey: "liki.expo_settings")
            applyWindowSettings(); return settings
        default: throw NSError(domain: "Liki", code: 4, userInfo: [NSLocalizedDescriptionKey: "Unknown HID command: \(method)"])
        }
        return nil
    }
    private func prepare() {
        errorMessage = nil
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main, options: [CBCentralManagerOptionShowPowerAlertKey: true])
        }
    }
    private func start() {
        isStarting = true
        prepare()
        if central?.state == .poweredOn { hid.start() }
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            // Register connection events BEFORE adding the HOGP service (upstream requirement).
            central.registerForConnectionEvents(options: nil)
            if isStarting { hid.start() }
        } else {
            releaseInputs()
            errorMessage = central.state == .unauthorized ? "設定でLikiのBluetoothアクセスを許可してください" :
                central.state == .poweredOff ? "Bluetoothをオンにしてください" : nil
        }
        publish()
    }
    private func sequence(_ press: @escaping () -> Void) {
        guard connected else { return }
        let token = generation, when = max(Date(), nextKeyTime)
        nextKeyTime = when.addingTimeInterval(0.030)
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, when.timeIntervalSinceNow)) { [weak self] in
            guard let self, self.generation == token, self.connected else { return }; press()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, when.timeIntervalSinceNow) + 0.018) { [weak self] in
            guard let self, self.generation == token, self.connected else { return }
            self.hid.sendKeyboard(modifiers: 0, code: 0); self.hid.sendConsumer(usage: 0)
        }
    }
    private func move(_ x: Int, _ y: Int) {
        guard connected else { return }
        pendingX += x; pendingY += y
        let mode = settings["pointer_send_mode"] as? Int ?? 2
        if mode == 0 || Date() >= nextMouseTime { flushMouse() }
        else if mouseWork == nil {
            let work = DispatchWorkItem { [weak self] in self?.flushMouse() }; mouseWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + max(0, nextMouseTime.timeIntervalSinceNow), execute: work)
        }
    }
    private func flushMouse() {
        mouseWork?.cancel(); mouseWork = nil
        if pendingX != 0 || pendingY != 0 { mouse(pendingX, pendingY, 0); pendingX = 0; pendingY = 0 }
        let mode = settings["pointer_send_mode"] as? Int ?? 2
        nextMouseTime = Date().addingTimeInterval(mode == 0 ? 0 : mode == 1 ? 0.016 : 0.032)
    }
    private func mouse(_ x: Int, _ y: Int, _ wheel: Int) {
        guard connected else { return }
        var x = x, y = y, wheel = wheel
        repeat {
            let dx = max(-127, min(127, x)), dy = max(-127, min(127, y)), dw = max(-127, min(127, wheel))
            hid.sendMouse(MouseReport(buttons: MouseButtons(rawValue: UInt8(buttons & 7)), dX: Int8(dx), dY: Int8(dy), wheel: Int8(dw)))
            x -= dx; y -= dy; wheel -= dw
        } while x != 0 || y != 0 || wheel != 0
    }
    func releaseInputs() {
        relay.clear()
        mouseRelay.clear()
        generation += 1; nextKeyTime = .distantPast; mouseWork?.cancel(); mouseWork = nil; pendingX = 0; pendingY = 0; buttons = 0
        hid.sendKeyboard(modifiers: 0, code: 0); hid.sendConsumer(usage: 0); hid.sendMouse(.zero)
    }
    private func applyWindowSettings() {
        UIApplication.shared.isIdleTimerDisabled = settings["keep_screen_on"] as? Bool ?? false
        let orientation = controls ? settings["screen_orientation"] as? Int ?? 0 : 0
        let mask: UIInterfaceOrientationMask = orientation == 1 ? .portrait : orientation == 2 ? .landscapeRight : orientation == 3 ? .landscapeLeft : .allButUpsideDown
        LikiAppDelegateSubscriber.orientations = mask
        if let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene {
            scene.windows.first(where: { $0.isKeyWindow })?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { [weak self] error in
                Task { @MainActor in self?.errorMessage = error.localizedDescription; self?.publish() }
            }
        }
    }
    func pickFile(_ promise: Promise) {
        guard pickerPromise == nil else { promise.reject("liki", "ファイル選択中です"); return }
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
              var view = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { promise.reject("liki", "画面を開けません"); return }
        while let presented = view.presentedViewController { view = presented }
        pickerPromise = promise
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .plainText, .data], asCopy: true)
        picker.delegate = self; picker.allowsMultipleSelection = false; view.present(picker, animated: true)
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { pickerPromise?.resolve(nil); pickerPromise = nil }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        let promise = pickerPromise; pickerPromise = nil
        do {
            guard let url = urls.first else { promise?.resolve(nil); return }
            let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
            let data = try handle.read(upToCount: 1024 * 1024 + 1) ?? Data()
            guard data.count <= 1024 * 1024, let json = String(data: data, encoding: .utf8) else {
                throw NSError(domain: "Liki", code: 5, userInfo: [NSLocalizedDescriptionKey: "UTF-8、1MB以下のJSONを選んでください"])
            }
            promise?.resolve(json)
        } catch { promise?.reject("liki", error.localizedDescription) }
    }
    func close() {
        layerTransport.close()
        studio.close()
        relay.close()
        mouseRelay.close()
        releaseInputs(); hid.disconnect(); central?.delegate = nil; central = nil; observation = nil
        UIApplication.shared.isIdleTimerDisabled = false
        LikiAppDelegateSubscriber.orientations = .allButUpsideDown
        pickerPromise?.reject("liki", "画面が閉じられました"); pickerPromise = nil
    }
    func background() { layerTransport.enable(false); studio.enable(false); releaseInputs() }
}
