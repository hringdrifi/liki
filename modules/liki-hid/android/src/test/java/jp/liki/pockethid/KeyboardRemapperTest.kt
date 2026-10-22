package jp.liki.pockethid

import org.junit.Assert.*
import org.junit.Test

class KeyboardRemapperTest {
    @Test fun remapsDoNotChainAndOriginalModifiersRemainHeld() {
        val rules = mapOf(0x70004 to listOf(0x70005), 0x70005 to listOf(0x70006))
        val mapped = KeyboardRemapper.translate(listOf(4, 0xe0), 0, rules)
        assertArrayEquals(byteArrayOf(1, 0, 5, 0, 0, 0, 0, 0), mapped.keyboard)
        assertArrayEquals(ByteArray(8), KeyboardRemapper.translate(emptyList(), 0, rules).keyboard)
    }
    @Test fun duplicateDestinationsRemainHeldUntilAllSourcesRelease() {
        val rules = mapOf(0x70004 to listOf(0x70005), 0x70006 to listOf(0x70005))
        assertArrayEquals(KeyboardRemapper.translate(listOf(4, 6), 0, rules).keyboard,
            KeyboardRemapper.translate(listOf(6), 0, rules).keyboard)
        assertArrayEquals(ByteArray(8), KeyboardRemapper.translate(emptyList(), 0, rules).keyboard)
    }
    @Test fun modifierChordsDisabledKeysAndConsumerReports() {
        val rules = mapOf(0x70004 to listOf(0x700e1, 0x70006), 0x700e0 to emptyList(), 0x70005 to listOf(0xc00e9))
        val mapped = KeyboardRemapper.translate(listOf(4, 5, 0xe0), 0, rules)
        assertArrayEquals(byteArrayOf(2, 0, 6, 0, 0, 0, 0, 0), mapped.keyboard)
        assertEquals(0xe9, mapped.consumer)
        assertEquals(0, KeyboardRemapper.translate(emptyList(), 0, rules).consumer)
        assertEquals(0xe2, KeyboardRemapper.translate(emptyList(), 0xe2, rules).consumer)
    }
    @Test fun rolloverIsComputedAfterTranslation() {
        val mapped = KeyboardRemapper.translate((4..10).toList(), 0, emptyMap())
        assertArrayEquals(byteArrayOf(0, 0, 1, 1, 1, 1, 1, 1), mapped.keyboard)
        val reduced = KeyboardRemapper.translate((4..10).toList(), 0, mapOf(0x70004 to emptyList()))
        assertArrayEquals(byteArrayOf(0, 0, 5, 6, 7, 8, 9, 10), reduced.keyboard)
    }
}
