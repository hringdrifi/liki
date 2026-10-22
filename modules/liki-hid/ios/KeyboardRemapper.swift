import Foundation

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
    static func translate(modifiers: Int, keys: [Int], rules: [Int: [Int]]) -> (modifiers: Int, keys: [Int], consumer: Int) {
        let source = keys.map { 0x70000 | $0 } + (0..<8).filter { modifiers & (1 << $0) != 0 }.map { 0x700e0 + $0 }
        var output = Set<Int>(), order: [Int] = []
        for usage in source { for target in rules[usage] ?? [usage] { if output.insert(target).inserted { order.append(target) } } }
        let normal = order.filter { $0 >> 16 == 7 && (4...0xdd).contains($0 & 0xffff) }.map { $0 & 0xffff }
        let mods = order.filter { $0 >> 16 == 7 && (0xe0...0xe7).contains($0 & 0xffff) }.reduce(0) { $0 | (1 << (($1 & 0xffff) - 0xe0)) }
        return (mods, normal.count > 6 ? Array(repeating: 1, count: 6) : normal, order.last(where: { $0 >> 16 == 12 }).map { $0 & 0xffff } ?? 0)
    }
}
