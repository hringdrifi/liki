import GameController
import UIKit

/// Observes physical keyboards only while the relay page is active.
@MainActor
final class KeyboardRelay {
    private let connected: () -> Bool
    private let send: (Int, [Int]) -> Void
    private let changed: () -> Void
    private var observers: [NSObjectProtocol] = []
    private var input: GCKeyboardInput?
    private var pressed = Set<Int>()
    private var enabled = false
    private var generation = 0

    init(connected: @escaping () -> Bool, send: @escaping (Int, [Int]) -> Void, changed: @escaping () -> Void) {
        self.connected = connected; self.send = send; self.changed = changed
        for name in [Notification.Name.GCKeyboardDidConnect, .GCKeyboardDidDisconnect, UIApplication.didBecomeActiveNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.attach(); self?.changed() }
            })
        }
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.detach() }
        })
    }

    var devices: [[String: Any]] {
        guard let keyboard = GCKeyboard.coalesced else { return [] }
        // iOS exposes multiple keyboards as one coalesced device.
        return [["id": "physical-keyboard", "name": keyboard.vendorName ?? "外付けキーボード"]]
    }

    func enable(_ value: Bool) {
        guard enabled != value else { return }
        enabled = value; attach()
    }

    private func attach() {
        detach()
        guard enabled, UIApplication.shared.applicationState == .active, let keyboard = GCKeyboard.coalesced else { return }
        keyboard.handlerQueue = .main
        input = keyboard.keyboardInput
        let token = generation
        input?.keyChangedHandler = { [weak self] _, _, code, down in
            MainActor.assumeIsolated {
                guard let self, self.generation == token, self.enabled, self.connected(), UIApplication.shared.applicationState == .active else { return }
                let usage = code.rawValue
                guard (4...0xdd).contains(usage) || (0xe0...0xe7).contains(usage) else { return }
                if down { self.pressed.insert(usage) } else { self.pressed.remove(usage) }
                let modifiers = self.pressed.filter { (0xe0...0xe7).contains($0) }.reduce(0) { $0 | (1 << ($1 - 0xe0)) }
                let keys = self.pressed.filter { $0 < 0xe0 }.sorted()
                self.send(modifiers, keys)
            }
        }
    }

    func clear() { guard !pressed.isEmpty else { return }; pressed.removeAll(); send(0, []) }
    private func detach() { generation += 1; input?.keyChangedHandler = nil; input = nil; clear() }
    func close() { enabled = false; detach(); observers.forEach { NotificationCenter.default.removeObserver($0) }; observers.removeAll() }
}
