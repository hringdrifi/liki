import GameController
import UIKit

/// Forwards relative movement and the three buttons supported by Liki's HID descriptor.
@MainActor
final class MouseRelay {
    private final class Input {
        let mouse: GCMouse
        let profile: GCMouseInput
        var buttons = 0
        var x: Float = 0, y: Float = 0, wheel: Float = 0
        init(_ mouse: GCMouse, _ profile: GCMouseInput) { self.mouse = mouse; self.profile = profile }
    }
    private let connected: () -> Bool
    private let send: (Int, Int, Int, Int) -> Void
    private let changed: () -> Void
    private var observers: [NSObjectProtocol] = []
    private var inputs: [ObjectIdentifier: Input] = [:]
    private var enabled = false
    private var generation = 0

    init(connected: @escaping () -> Bool, send: @escaping (Int, Int, Int, Int) -> Void, changed: @escaping () -> Void) {
        self.connected = connected; self.send = send; self.changed = changed
        for name in [Notification.Name.GCMouseDidConnect, .GCMouseDidDisconnect, UIApplication.didBecomeActiveNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.attach(); self?.changed() }
            })
        }
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.detach() }
        })
    }

    var devices: [[String: Any]] {
        GCMouse.mice().enumerated().map { index, mouse in
            ["id": "physical-mouse-\(index)", "name": mouse.vendorName ?? "外付けマウス"]
        }
    }

    func enable(_ value: Bool) {
        guard enabled != value else { return }
        enabled = value; attach()
    }

    private var buttons: Int { inputs.values.reduce(0) { $0 | $1.buttons } }
    private func active(_ token: Int) -> Bool {
        generation == token && enabled && connected() && UIApplication.shared.applicationState == .active
    }

    private func attach() {
        detach()
        guard enabled, UIApplication.shared.applicationState == .active else { return }
        let token = generation
        for mouse in GCMouse.mice() {
            guard let profile = mouse.mouseInput else { continue }
            mouse.handlerQueue = .main
            let input = Input(mouse, profile)
            inputs[ObjectIdentifier(mouse)] = input
            profile.mouseMovedHandler = { [weak self, weak input] _, dx, dy in
                MainActor.assumeIsolated {
                    guard let self, let input, self.active(token), dx.isFinite, dy.isFinite else { return }
                    // GameController uses positive Y upwards; HID uses positive Y downwards.
                    input.x += dx; input.y -= dy
                    let x = Int(input.x), y = Int(input.y)
                    input.x -= Float(x); input.y -= Float(y)
                    if x != 0 || y != 0 { self.send(self.buttons, x, y, 0) }
                }
            }
            for (button, mask) in [(profile.leftButton, 1), (profile.rightButton, 2), (profile.middleButton, 4)] {
                button?.pressedChangedHandler = { [weak self, weak input] _, _, down in
                    MainActor.assumeIsolated {
                        guard let self, let input, self.active(token) else { return }
                        if down { input.buttons |= mask } else { input.buttons &= ~mask }
                        self.send(self.buttons, 0, 0, 0)
                    }
                }
            }
            profile.scroll.yAxis.valueChangedHandler = { [weak self, weak input] _, value in
                MainActor.assumeIsolated {
                    guard let self, let input, self.active(token), value.isFinite else { return }
                    input.wheel += value
                    let wheel = Int(input.wheel); input.wheel -= Float(wheel)
                    if wheel != 0 { self.send(self.buttons, 0, 0, wheel) }
                }
            }
        }
    }

    func clear() {
        let held = buttons != 0
        for input in inputs.values { input.buttons = 0; input.x = 0; input.y = 0; input.wheel = 0 }
        if held { send(0, 0, 0, 0) }
    }

    private func detach() {
        generation += 1
        clear()
        for input in inputs.values {
            input.profile.mouseMovedHandler = nil
            input.profile.leftButton.pressedChangedHandler = nil
            input.profile.rightButton?.pressedChangedHandler = nil
            input.profile.middleButton?.pressedChangedHandler = nil
            input.profile.scroll.yAxis.valueChangedHandler = nil
        }
        inputs.removeAll()
    }

    func close() { enabled = false; detach(); observers.forEach { NotificationCenter.default.removeObserver($0) }; observers.removeAll() }
}
