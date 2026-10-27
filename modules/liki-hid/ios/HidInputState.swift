// Physical holds and virtual pulses must release independently.
struct HidInputState {
    var physicalModifiers = 0
    var physicalKeys: [Int] = []
    var virtualModifiers = 0
    var virtualCode = 0
    var physicalConsumer = 0
    var virtualConsumer = 0
    var physicalButtons = 0
    var keyboard: (modifiers: Int, codes: [Int]) {
        var keys: [Int] = []
        for code in physicalKeys + [virtualCode] where code != 0 {
            if !keys.contains(code) { keys.append(code) }
        }
        return (physicalModifiers | virtualModifiers,
                physicalKeys.contains(1) || keys.count > 6 ? Array(repeating: 1, count: 6) : keys)
    }
    var consumer: Int { virtualConsumer != 0 ? virtualConsumer : physicalConsumer }
    mutating func clearVirtual() { virtualModifiers = 0; virtualCode = 0; virtualConsumer = 0 }
    mutating func clear() { self = HidInputState() }
}
