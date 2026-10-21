import CoreBluetooth
import UIKit

/// Read-only transport to an already connected Studio keyboard; never scans nearby devices.
@MainActor
final class StudioTransport: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private static let service = CBUUID(string: "00000000-0196-6107-c967-c5cfb1c2482a")
    private static let rpc = CBUUID(string: "00000001-0196-6107-c967-c5cfb1c2482a")
    private let emit: ([String: Any]) -> Void
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var characteristic: CBCharacteristic?
    private var queue: [Data] = []
    private var writing = false
    private var enabled = false
    private var resettingNotifications = false
    private var receivedBytes = 0, completedWrites = 0
    private var timeout: DispatchWorkItem?
    init(emit: @escaping ([String: Any]) -> Void) { self.emit = emit; super.init() }
    private func state(_ message: String, ready: Bool = false) { emit(["studioState": ["message": message, "ready": ready]]) }
    func enable(_ value: Bool) {
        guard enabled != value else { return }
        enabled = value
        if value {
            state("Studioキーボードを確認中…")
            if central == nil { central = CBCentralManager(delegate: self, queue: .main) }
            else { find() }
        } else { disconnect(); state("") }
    }
    func retry() {
        guard enabled else { return }
        disconnect()
        state("Studioキーボードを確認中…")
        central = CBCentralManager(delegate: self, queue: .main)
    }
    private func disconnect() {
        timeout?.cancel(); timeout = nil
        peripheral?.delegate = nil
        if let peripheral { central?.cancelPeripheralConnection(peripheral) }
        peripheral = nil; characteristic = nil; queue.removeAll(); writing = false
        resettingNotifications = false; receivedBytes = 0; completedWrites = 0
        central?.delegate = nil; central = nil
    }
    private func fail(_ message: String) { disconnect(); state(message) }
    private func find() {
        guard enabled, peripheral == nil, UIApplication.shared.applicationState == .active else { return }
        guard central?.state == .poweredOn else { state("Studio取得にはBluetoothをオンにしてください"); return }
        let devices = central!.retrieveConnectedPeripherals(withServices: [Self.service])
        guard devices.count == 1 else {
            state(devices.isEmpty ? "接続済みのZMK Studioキーボードがありません。接続後に再取得してください" : "Studioキーボードを1台だけ接続して再取得してください"); return
        }
        let device = devices[0]; peripheral = device; device.delegate = self
        state("ZMK Studioへ接続中…")
        let work = DispatchWorkItem { [weak self] in self?.fail("Studioへの接続がタイムアウトしました") }
        timeout = work; DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: work)
        central!.connect(device)
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central === self.central else { return }
        if central.state == .poweredOn { find() } else if enabled, central.state != .unknown, central.state != .resetting { fail("StudioのBluetooth接続を利用できません。再取得してください") }
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard central === self.central, peripheral === self.peripheral else { return }
        peripheral.discoverServices([Self.service])
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if central === self.central, peripheral === self.peripheral { fail("Studioへ接続できませんでした") }
    }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if central === self.central, peripheral === self.peripheral { fail("Studio接続が切れました。再取得してください") }
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === self.peripheral else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.service }) else { fail("Studioサービスを取得できません"); return }
        peripheral.discoverCharacteristics([Self.rpc], for: service)
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === self.peripheral else { return }
        guard error == nil, let rpc = service.characteristics?.first(where: { $0.uuid == Self.rpc }) else { fail("Studio通信を開始できません"); return }
        characteristic = rpc
        resettingNotifications = true
        peripheral.setNotifyValue(false, for: rpc)
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === self.peripheral, characteristic === self.characteristic else { return }
        guard error == nil else { fail("Studioの通知を受信できません"); return }
        if resettingNotifications {
            guard !characteristic.isNotifying else { fail("Studioの通知を解除できません"); return }
            resettingNotifications = false
            peripheral.setNotifyValue(true, for: characteristic)
            return
        }
        guard characteristic.isNotifying else { fail("Studioの通知を受信できません"); return }
        timeout?.cancel(); timeout = nil; state("", ready: true)
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === self.peripheral, characteristic === self.characteristic else { return }
        guard error == nil, let value = characteristic.value else { fail("Studioの受信に失敗しました"); return }
        receivedBytes += value.count
        emit(["studioBytes": value.map(Int.init)])
    }
    func write(_ bytes: [UInt8]) throws {
        guard let peripheral, characteristic != nil else { throw NSError(domain: "Liki", code: 10, userInfo: [NSLocalizedDescriptionKey: "Studioが接続されていません"]) }
        let size = peripheral.maximumWriteValueLength(for: .withResponse)
        guard bytes.count <= 4096, queue.count < 256 else { throw NSError(domain: "Liki", code: 11) }
        for start in stride(from: 0, to: bytes.count, by: size) { queue.append(Data(bytes[start..<min(start + size, bytes.count)])) }
        next()
    }
    private func next() {
        guard !writing, !queue.isEmpty, let peripheral, let characteristic else { return }
        writing = true; peripheral.writeValue(queue.removeFirst(), for: characteristic, type: .withResponse)
    }
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === self.peripheral, characteristic === self.characteristic else { return }
        guard error == nil else { fail("Studioへの送信に失敗しました"); return }
        completedWrites += 1
        writing = false; next()
    }
    var diagnostics: [String: Any] {
        ["enabled": enabled, "notifying": characteristic?.isNotifying == true,
         "resettingNotifications": resettingNotifications, "receivedBytes": receivedBytes,
         "completedWrites": completedWrites, "writing": writing, "queuedWrites": queue.count,
         "maximumWriteLength": peripheral?.maximumWriteValueLength(for: .withResponse) ?? 0]
    }
    func close() { enabled = false; disconnect(); central?.delegate = nil; central = nil }
}
