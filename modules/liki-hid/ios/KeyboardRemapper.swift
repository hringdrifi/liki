import Foundation

// HID positions and Shift pairs: QMK quantum/keymap_extras/keymap_{us,japanese}.h.
enum KeyboardLayoutConverter {
    private struct Key {
        let code: Int, plain: Character, shifted: Character
        init(_ code: Int, _ plain: Character, _ shifted: Character) {
            self.code = code; self.plain = plain; self.shifted = shifted
        }
    }
    private static let us = [
        Key(0x1e, "1", "!"), Key(0x1f, "2", "@"), Key(0x20, "3", "#"), Key(0x21, "4", "$"),
        Key(0x22, "5", "%"), Key(0x23, "6", "^"), Key(0x24, "7", "&"), Key(0x25, "8", "*"),
        Key(0x26, "9", "("), Key(0x27, "0", ")"), Key(0x2d, "-", "_"), Key(0x2e, "=", "+"),
        Key(0x2f, "[", "{"), Key(0x30, "]", "}"), Key(0x31, "\\", "|"), Key(0x33, ";", ":"),
        Key(0x34, "'", "\""), Key(0x35, "`", "~"), Key(0x36, ",", "<"), Key(0x37, ".", ">"), Key(0x38, "/", "?")]
    private static let jis = [
        Key(0x1e, "1", "!"), Key(0x1f, "2", "\""), Key(0x20, "3", "#"), Key(0x21, "4", "$"),
        Key(0x22, "5", "%"), Key(0x23, "6", "&"), Key(0x24, "7", "'"), Key(0x25, "8", "("),
        Key(0x26, "9", ")"), Key(0x27, "0", "\0"), Key(0x2d, "-", "="), Key(0x2e, "^", "~"),
        Key(0x2f, "@", "`"), Key(0x30, "[", "{"), Key(0x32, "]", "}"), Key(0x33, ";", "+"),
        Key(0x34, ":", "*"), Key(0x87, "\\", "_"), Key(0x89, "\\", "|"),
        Key(0x36, ",", "<"), Key(0x37, ".", ">"), Key(0x38, "/", "?")]

    static func convert(modifiers: Int, keys: [Int], layout: String) -> (Int, [Int]) {
        guard layout == "us-jis" || layout == "jis-us" else { return (modifiers, keys) }
        let source = layout == "us-jis" ? us : jis, target = layout == "us-jis" ? jis : us
        let shift = modifiers & 0x22 != 0
        let converted: [(Int, Bool)] = keys.map { code in
            let position = layout == "jis-us" && code == 0x31 ? 0x32 : code
            guard let key = source.first(where: { $0.code == position }) else { return (code, shift) }
            let char = shift ? key.shifted : key.plain
            guard char != "\0", let match = target.first(where: { $0.plain == char || $0.shifted == char }) else { return (code, shift) }
            return (match.code, match.shifted == char)
        }
        // One HID report cannot represent keys requiring different Shift states.
        if Set(converted.map { $0.1 }).count > 1 { return (modifiers, Array(repeating: 1, count: 6)) }
        let outputShift = converted.first?.1 ?? shift
        let mods = outputShift ? modifiers | (shift ? modifiers & 0x22 : 2) : modifiers & ~0x22
        var seen = Set<Int>()
        return (mods, converted.map { $0.0 }.filter { seen.insert($0).inserted })
    }
}

enum KeyboardRemapper {
    static func parse(_ json: String) throws -> [Int: [Int]] {
        guard let data = json.data(using: .utf8), let rules = try JSONSerialization.jsonObject(with: data) as? [[String: Any]], rules.count <= 256 else {
            throw NSError(domain: "Liki", code: 20, userInfo: [NSLocalizedDescriptionKey: "読み替え設定が不正です"])
        }
        func valid(_ usage: Int) -> Bool {
            let page = usage >> 16, code = usage & 0xffff
            return page == 7 && ((4...0xdd).contains(code) || (0xe0...0xe7).contains(code)) || page == 12 && (1...0xffff).contains(code)
        }
        var result: [Int: [Int]] = [:]
        for rule in rules {
            guard let source = rule["source"] as? NSNumber, let targets = rule["target"] as? [NSNumber], targets.count <= 16,
                  source.doubleValue == Double(source.intValue), valid(source.intValue), result[source.intValue] == nil,
                  targets.allSatisfy({ $0.doubleValue == Double($0.intValue) && valid($0.intValue) }) else {
                throw NSError(domain: "Liki", code: 20, userInfo: [NSLocalizedDescriptionKey: "読み替え設定が不正です"])
            }
            result[source.intValue] = targets.map(\.intValue)
        }
        return result
    }
    static func translate(modifiers: Int, keys: [Int], rules: [Int: [Int]], layout: String = "none") -> (modifiers: Int, keys: [Int], consumer: Int) {
        let source = keys.map { 0x70000 | $0 } + (0..<8).filter { modifiers & (1 << $0) != 0 }.map { 0x700e0 + $0 }
        var output = Set<Int>(), order: [Int] = []
        for usage in source { for target in rules[usage] ?? [usage] { if output.insert(target).inserted { order.append(target) } } }
        let normal = order.filter { $0 >> 16 == 7 && (4...0xdd).contains($0 & 0xffff) }.map { $0 & 0xffff }
        let mods = order.filter { $0 >> 16 == 7 && (0xe0...0xe7).contains($0 & 0xffff) }.reduce(0) { $0 | (1 << (($1 & 0xffff) - 0xe0)) }
        let converted = KeyboardLayoutConverter.convert(modifiers: mods, keys: normal, layout: layout)
        return (converted.0, converted.1.count > 6 ? Array(repeating: 1, count: 6) : converted.1, order.last(where: { $0 >> 16 == 12 }).map { $0 & 0xffff } ?? 0)
    }
}
