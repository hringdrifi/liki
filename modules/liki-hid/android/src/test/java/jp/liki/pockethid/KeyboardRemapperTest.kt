package jp.liki.pockethid

import org.junit.Assert.*
import org.junit.Test

class KeyboardRemapperTest {
    @Test fun layoutConversionPreservesSymbolsInBothDirections() {
        // US key, US Shift, JIS key, JIS Shift. Includes every differing symbol.
        val symbols = listOf(
            intArrayOf(0x1f, 1, 0x2f, 0), intArrayOf(0x23, 1, 0x2e, 0),
            intArrayOf(0x24, 1, 0x23, 1), intArrayOf(0x25, 1, 0x34, 1),
            intArrayOf(0x26, 1, 0x25, 1), intArrayOf(0x27, 1, 0x26, 1),
            intArrayOf(0x2d, 1, 0x87, 1), intArrayOf(0x2e, 0, 0x2d, 1),
            intArrayOf(0x2e, 1, 0x33, 1), intArrayOf(0x2f, 0, 0x30, 0),
            intArrayOf(0x2f, 1, 0x30, 1), intArrayOf(0x30, 0, 0x32, 0),
            intArrayOf(0x30, 1, 0x32, 1), intArrayOf(0x31, 0, 0x87, 0),
            intArrayOf(0x31, 1, 0x89, 1), intArrayOf(0x33, 1, 0x34, 0),
            intArrayOf(0x34, 0, 0x24, 1), intArrayOf(0x34, 1, 0x1f, 1),
            intArrayOf(0x35, 0, 0x2f, 1), intArrayOf(0x35, 1, 0x2e, 1))
        for ((us, usShift, jis, jisShift) in symbols) {
            assertEquals(jisShift * 2 to listOf(jis), KeyboardLayoutConverter.convert(usShift * 2, listOf(us), "us-jis"))
            assertEquals(usShift * 2 to listOf(us), KeyboardLayoutConverter.convert(jisShift * 2, listOf(jis), "jis-us"))
        }
    }
    @Test fun remapRunsBeforeLayoutConversionAndKeepsOtherModifiersAndConsumer() {
        val mapped = KeyboardRemapper.translate(listOf(4, 0xe0), 0xe9,
            mapOf(0x70004 to listOf(0x700e5, 0x7001f)), "us-jis")
        assertArrayEquals(byteArrayOf(1, 0, 0x2f, 0, 0, 0, 0, 0), mapped.keyboard)
        assertEquals(0xe9, mapped.consumer)
        assertArrayEquals(ByteArray(8), KeyboardRemapper.translate(emptyList(), 0, emptyMap(), "us-jis").keyboard)
        assertEquals(0x21 to listOf(0x23), KeyboardLayoutConverter.convert(0x21, listOf(0x24), "us-jis"))
        assertEquals(3 to listOf(0x2d), KeyboardLayoutConverter.convert(1, listOf(0x2e), "us-jis"))
    }
    @Test fun conversionIsIndependentOfRemapsAndOffByDefault() {
        assertEquals(2 to listOf(0x1f), KeyboardLayoutConverter.convert(2, listOf(0x1f), "none"))
        assertArrayEquals(byteArrayOf(0, 0, 0x2f, 0, 0, 0, 0, 0),
            KeyboardRemapper.translate(listOf(0xe1, 0x1f), 0, emptyMap(), "us-jis").keyboard)
        assertEquals(0 to listOf(0x30), KeyboardLayoutConverter.convert(0, listOf(0x31), "jis-us"))
        assertEquals(0 to listOf(0x31), KeyboardLayoutConverter.convert(0, listOf(0x87, 0x89), "jis-us"))
        assertEquals(2 to listOf(4, 0x28, 0x8a), KeyboardLayoutConverter.convert(2, listOf(4, 0x28, 0x8a), "jis-us"))
    }
    @Test fun incompatibleShiftStatesReportRolloverAndRecoverAfterRelease() {
        assertEquals(2 to List(6) { 1 }, KeyboardLayoutConverter.convert(2, listOf(0x1f, 4), "us-jis"))
        assertEquals(0 to listOf(0x2f), KeyboardLayoutConverter.convert(2, listOf(0x1f), "us-jis"))
        assertEquals(2 to emptyList<Int>(), KeyboardLayoutConverter.convert(2, emptyList(), "us-jis"))
    }
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
