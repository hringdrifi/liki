package jp.liki.pockethid

/** Relative mouse state per physical device. Fractional motion is retained between events. */
internal class RelayMouseState {
    data class Report(val buttons: Int, val x: Int = 0, val y: Int = 0, val wheel: Int = 0)
    private data class Device(var buttons: Int = 0, var x: Float = 0f, var y: Float = 0f, var wheel: Float = 0f)
    private val devices = mutableMapOf<Int, Device>()
    private fun buttons() = devices.values.fold(0) { mask, device -> mask or device.buttons }

    fun update(id: Int, buttons: Int, x: Float = 0f, y: Float = 0f, wheel: Float = 0f): List<Report> {
        val previous = buttons()
        val device = devices.getOrPut(id) { Device() }
        device.buttons = buttons and 7
        if (x.isFinite()) device.x += x
        if (y.isFinite()) device.y += y
        if (wheel.isFinite()) device.wheel += wheel
        var dx = device.x.toInt(); var dy = device.y.toInt(); var dw = device.wheel.toInt()
        device.x -= dx; device.y -= dy; device.wheel -= dw
        val mask = buttons()
        val reports = mutableListOf<Report>()
        if (previous != mask && dx == 0 && dy == 0 && dw == 0) reports.add(Report(mask))
        while (dx != 0 || dy != 0 || dw != 0) {
            val sx = dx.coerceIn(-127, 127); val sy = dy.coerceIn(-127, 127); val sw = dw.coerceIn(-127, 127)
            reports.add(Report(mask, sx, sy, sw)); dx -= sx; dy -= sy; dw -= sw
        }
        return reports
    }

    fun remove(id: Int): Report? {
        val previous = buttons(); devices.remove(id)
        return buttons().takeIf { it != previous }?.let { Report(it) }
    }

    fun clear(): Report? {
        val pressed = buttons() != 0; devices.clear()
        return if (pressed) Report(0) else null
    }
}
