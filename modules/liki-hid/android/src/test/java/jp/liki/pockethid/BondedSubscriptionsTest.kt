package jp.liki.pockethid

import org.junit.Assert.*
import org.junit.Test

class BondedSubscriptionsTest {
    private val disk = mutableMapOf<String, Set<String>>()
    private fun instance() = BondedSubscriptions(
        { disk[it].orEmpty() }, { address, values -> disk[address] = values }, { disk.remove(it); Unit })

    @Test fun subscriptionsSurviveNewServerAndRemainSpecificToTheBondedClient() {
        instance().update("pc-a", true, setOf("report:1", "report:2"))
        assertEquals(setOf("report:1", "report:2"), instance().restore("pc-a", true))
        assertTrue(instance().restore("pc-b", true).isEmpty())
    }

    @Test fun disablingNotificationsPersistsAcrossRestart() {
        instance().update("pc", true, setOf("report:1"))
        instance().update("pc", true, emptySet())
        assertTrue(instance().restore("pc", true).isEmpty())
    }

    @Test fun unbondedClientCannotInheritOrSaveOldConfiguration() {
        instance().update("pc", true, setOf("report:1"))
        assertTrue(instance().restore("pc", false).isEmpty())
        instance().update("pc", false, setOf("report:2"))
        assertTrue(instance().restore("pc", true).isEmpty())
    }

    @Test fun removingBondDiscardsConfigurationBeforeRePairing() {
        instance().update("pc", true, setOf("report:1"))
        instance().forget("pc")
        assertTrue(instance().restore("pc", true).isEmpty())
    }
}
