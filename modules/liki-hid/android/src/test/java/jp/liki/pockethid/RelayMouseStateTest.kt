package jp.liki.pockethid

import org.junit.Assert.*
import org.junit.Test

class RelayMouseStateTest {
    @Test fun largeDeltasAreSplitWithoutLosingDistanceOrButtons() {
        val reports = RelayMouseState().update(1, 3, 400f, -260f, 130f)
        assertEquals(400, reports.sumOf { it.x }); assertEquals(-260, reports.sumOf { it.y })
        assertEquals(130, reports.sumOf { it.wheel })
        assertTrue(reports.all { it.buttons == 3 && it.x in -127..127 && it.y in -127..127 && it.wheel in -127..127 })
    }

    @Test fun fractionalMotionAndWheelAreRetained() {
        val state = RelayMouseState()
        assertTrue(state.update(1, 0, .5f, -.5f, .5f).isEmpty())
        assertEquals(listOf(RelayMouseState.Report(0, 1, -1, 1)), state.update(1, 0, .5f, -.5f, .5f))
    }

    @Test fun removingOneMouseDoesNotReleaseAnotherMouseButton() {
        val state = RelayMouseState()
        state.update(1, 1); state.update(2, 3)
        assertNull(state.remove(1))
        assertEquals(RelayMouseState.Report(0), state.remove(2))
    }

    @Test fun clearReleasesButtonsAndDiscardsFractionalMotion() {
        val state = RelayMouseState()
        state.update(1, 1, .75f)
        assertEquals(RelayMouseState.Report(0), state.clear())
        assertTrue(state.update(1, 0, .5f).isEmpty())
        assertTrue(state.update(1, 0x18).isEmpty()) // unsupported buttons do not leak
    }
}
