package jp.liki.pockethid

import org.junit.Assert.*
import org.junit.Test

class StudioKeyboardNameTest {
    @Test fun zmkHidCollectionMatchesTheBluetoothDeviceName() {
        assertTrue(matchesStudioKeyboardName("Bifrost Keyboard", "Bifrost"))
        assertTrue(matchesStudioKeyboardName("Bifrost", "Bifrost"))
        assertTrue(matchesStudioKeyboardName("Board Keyboard", "Board Keyboard"))
    }

    @Test fun unrelatedAndMissingDeviceNamesDoNotMatch() {
        assertFalse(matchesStudioKeyboardName("Bifrost Mouse", "Bifrost"))
        assertFalse(matchesStudioKeyboardName("Bifrost Pro Keyboard", "Bifrost"))
        assertFalse(matchesStudioKeyboardName("Bifrost Keyboard", "Other"))
        assertFalse(matchesStudioKeyboardName(" Keyboard", ""))
        assertFalse(matchesStudioKeyboardName("Bifrost Keyboard", null))
    }
}
