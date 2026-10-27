package jp.liki.pockethid

import org.junit.Assert.*
import org.junit.Test

class HidInputStateTest {
    private fun report(modifiers: Int, vararg keys: Int) = byteArrayOf(modifiers.toByte(), 0) +
        ByteArray(6) { keys.getOrElse(it) { 0 }.toByte() }

    @Test fun virtualTapPreservesPhysicalModifiersAndHeldKeys() {
        val state = HidInputState()
        state.physicalKeyboard = report(1, 5)
        state.virtualModifiers = 2; state.virtualCode = 4
        assertArrayEquals(report(3, 5, 4), state.keyboard())
        state.clearVirtual()
        assertArrayEquals(report(1, 5), state.keyboard())
    }

    @Test fun physicalReleaseDoesNotReleaseVirtualTap() {
        val state = HidInputState()
        state.physicalKeyboard = report(1, 4)
        state.virtualCode = 5
        state.physicalKeyboard = report(0)
        assertArrayEquals(report(0, 5), state.keyboard())
        state.clearVirtual()
        assertArrayEquals(report(0), state.keyboard())
    }

    @Test fun sameKeyIsDeduplicatedAndRolloverRecoversOnRelease() {
        val state = HidInputState()
        state.physicalKeyboard = report(0, 4, 5, 6, 7, 8, 9)
        state.virtualCode = 4
        assertArrayEquals(report(0, 4, 5, 6, 7, 8, 9), state.keyboard())
        state.virtualCode = 10
        assertArrayEquals(report(0, 1, 1, 1, 1, 1, 1), state.keyboard())
        state.clearVirtual()
        assertArrayEquals(report(0, 4, 5, 6, 7, 8, 9), state.keyboard())
        state.physicalKeyboard = report(0, 1, 1, 1, 1, 1, 1)
        assertArrayEquals(report(0, 1, 1, 1, 1, 1, 1), state.keyboard())
    }

    @Test fun mediaPulseRestoresThePhysicalUsageAndResetClearsBothSources() {
        val state = HidInputState()
        state.physicalConsumer = 0xe9; state.virtualConsumer = 0xe2
        assertEquals(0xe2, state.consumer())
        state.clearVirtual()
        assertEquals(0xe9, state.consumer())
        state.physicalKeyboard = report(2, 4); state.virtualCode = 5; state.physicalButtons = 1
        state.clear()
        assertArrayEquals(report(0), state.keyboard())
        assertEquals(0, state.consumer()); assertEquals(0, state.physicalButtons)
    }
}
