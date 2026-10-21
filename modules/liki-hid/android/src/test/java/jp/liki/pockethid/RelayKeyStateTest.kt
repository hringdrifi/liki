package jp.liki.pockethid

import org.junit.Assert.*
import org.junit.Test
import android.view.KeyEvent

class RelayKeyStateTest {
    @Test fun chordRemainsHeldUntilEachKeyIsReleased() {
        val state = RelayKeyState()
        state.key(1, 0xe0, true); state.key(1, 0xe5, true); state.key(1, 4, true); state.key(1, 5, true)
        assertArrayEquals(byteArrayOf(0x21, 0, 4, 5, 0, 0, 0, 0), state.keyboardReport())
        state.key(1, 4, false)
        assertArrayEquals(byteArrayOf(0x21, 0, 5, 0, 0, 0, 0, 0), state.keyboardReport())
        state.clear(); assertArrayEquals(ByteArray(8), state.keyboardReport())
    }

    @Test fun removingOneKeyboardKeepsKeysHeldByAnother() {
        val state = RelayKeyState()
        state.key(1, 4, true); state.key(2, 4, true); state.key(2, 0xe2, true)
        state.key(1, 4, false)
        assertEquals(4, state.keyboardReport()[2].toInt())
        state.remove(2); assertArrayEquals(ByteArray(8), state.keyboardReport())
    }

    @Test fun rolloverRecoversWhenSeventhKeyIsReleased() {
        val state = RelayKeyState()
        (4..10).forEach { state.key(1, it, true) }
        assertArrayEquals(byteArrayOf(0, 0, 1, 1, 1, 1, 1, 1), state.keyboardReport())
        state.key(1, 10, false)
        assertArrayEquals(byteArrayOf(0, 0, 4, 5, 6, 7, 8, 9), state.keyboardReport())
    }

    @Test fun consumerReleaseRestoresOtherHeldConsumer() {
        val state = RelayKeyState()
        state.consumer(1, 10, 0xe9, true); state.consumer(2, 11, 0xea, true)
        assertEquals(0xea, state.consumerUsage())
        state.remove(2); assertEquals(0xe9, state.consumerUsage())
        state.consumer(1, 10, 0xe9, false); assertEquals(0, state.consumerUsage())
    }

    @Test fun androidMappingsUsePhysicalKeyUsagesInsteadOfUnicode() {
        assertEquals(4, ExternalKeyboardRelay.keyboardUsage(KeyEvent.KEYCODE_A))
        assertEquals(0xe5, ExternalKeyboardRelay.keyboardUsage(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(0x58, ExternalKeyboardRelay.keyboardUsage(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(0x89, ExternalKeyboardRelay.keyboardUsage(KeyEvent.KEYCODE_YEN))
        assertEquals(0xcd, ExternalKeyboardRelay.consumerUsage(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertNull(ExternalKeyboardRelay.keyboardUsage(KeyEvent.KEYCODE_POWER))
    }
}
