import CoreBluetooth
import UIKit

/// Liki's layer service on an already connected keyboard. Does not use Studio.
@MainActor
final class LikiLayerTransport: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private static let service = CBUUID(string: "6c696b69-0000-4000-8000-000000000001")
    private static let layer = CBUUID(string: "6c696b69-0000-4000-8000-000000000002")
    private let emit: ([String: Any]) -> Void
    private var central: CBCentralManager?
    private var device: CBPeripheral?
    private var characteristic: CBCharacteristic?
    private var enabled = false
    private var targetIdentifier: UUID?
    private var supported = false
    private var timeout: DispatchWorkItem?
    private var reconnect: DispatchWorkItem?
    init(emit: @escaping ([String: Any]) -> Void) { self.emit = emit; super.init() }
    private func state(_ message: String = "", value: Data? = nil) {
        emit(["zmkLayer": ["supported": supported, "message": message, "bytes": value.map { $0.map(Int.init) as Any } ?? NSNull()]])
    }
    func enable(_ value: Bool) {
        guard enabled != value else { return }
        enabled = value; supported = false; targetIdentifier = nil; disconnect(); state()
        if value { central = CBCentralManager(delegate: self, queue: .main) }
    }
    func retry() {
        guard enabled else { return }
        disconnect(); state(); central = CBCentralManager(delegate: self, queue: .main)
    }
    private func disconnect() {
        timeout?.cancel(); timeout = nil; reconnect?.cancel(); reconnect = nil
        device?.delegate = nil
        if let device { central?.cancelPeripheralConnection(device) }
        device = nil; characteristic = nil
        central?.delegate = nil; central = nil
    }
    private func fail(_ message: String, retry: Bool = true) {
        disconnect(); state(message)
        if enabled && retry {
            let work = DispatchWorkItem { [weak self] in self?.retry() }
            reconnect = work; DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
        }
    }
    private func find() {
        guard enabled, device == nil, UIApplication.shared.applicationState == .active,
              let central, central.state == .poweredOn else { return }
        // HID covers keyboards whose custom service has not yet been discovered by iOS.
        let supportedDevices = central.retrieveConnectedPeripherals(withServices: [Self.service])
        let devices = supportedDevices.isEmpty ? central.retrieveConnectedPeripherals(withServices: [CBUUID(string: "1812")]) : supportedDevices
        guard devices.count == 1 else {
            supported = false; targetIdentifier = nil
            fail(devices.isEmpty ? "接続済みのBLEキーボードがありません" : "BLEキーボードを1台だけ接続してください"); return
        }
        let peripheral = devices[0]
        if targetIdentifier != peripheral.identifier { supported = false; targetIdentifier = peripheral.identifier; state() }
        device = peripheral; peripheral.delegate = self
        let work = DispatchWorkItem { [weak self] in self?.fail("ZMKレイヤー連携がタイムアウトしました") }
        timeout = work; DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: work)
        central.connect(peripheral)
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central === self.central else { return }
        if central.state == .poweredOn { find() }
        else if central.state != .unknown && central.state != .resetting { fail("ZMKレイヤー連携にはBluetoothをオンにしてください") }
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard central === self.central, peripheral === device else { return }
        peripheral.discoverServices([Self.service])
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if central === self.central, peripheral === device { fail("ZMKレイヤー接続に失敗しました") }
    }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if central === self.central, peripheral === device { fail("ZMKレイヤー接続が切れました") }
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === device else { return }
        guard error == nil else { fail("ZMKレイヤーサービスを取得できません"); return }
        let service = peripheral.services?.first(where: { $0.uuid == Self.service })
        supported = service != nil; state()
        guard let service else { fail("ZMKレイヤーサービスがありません", retry: false); return }
        peripheral.discoverCharacteristics([Self.layer], for: service)
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === device else { return }
        guard error == nil, let layer = service.characteristics?.first(where: { $0.uuid == Self.layer }) else { fail("ZMKレイヤー通知を取得できません"); return }
        characteristic = layer; peripheral.setNotifyValue(true, for: layer)
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === device, characteristic === self.characteristic else { return }
        guard error == nil, characteristic.isNotifying else { fail("ZMKレイヤー通知を開始できません"); return }
        peripheral.readValue(for: characteristic)
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === device, characteristic === self.characteristic else { return }
        guard error == nil, let value = characteristic.value else { fail("ZMKの現在レイヤーを取得できません"); return }
        timeout?.cancel(); timeout = nil; state(value: value)
    }
    func close() { enabled = false; supported = false; targetIdentifier = nil; disconnect(); state() }
}
