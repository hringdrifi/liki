package jp.liki.pockethid

import org.junit.Assert.*
import org.junit.Test

class ReloadSessionTest {
    private class Connection { var connected = true; var held = true; var detachCount = 0 }

    @Test fun reloadReusesConnectionButReleasesPressedInputs() {
        val session = ReloadSession<Connection>(); val old = Any()
        val first = session.acquire(old) { Connection() }
        session.release(old) { it.held = false; it.detachCount++ }
        val restored = session.acquire(Any()) { error("Reload must not create a new connection") }
        assertSame(first, restored); assertTrue(restored.connected); assertFalse(restored.held)
        assertEquals(1, restored.detachCount)
    }

    @Test fun lateOldRuntimeCleanupCannotDetachNewRuntime() {
        val session = ReloadSession<Connection>(); val old = Any(); val next = Any()
        val first = session.acquire(old) { Connection() }
        assertSame(first, session.acquire(next) { Connection() })
        assertFalse(session.owns(old)); assertTrue(session.owns(next))
        session.release(old, discard = true) { it.connected = false }
        assertTrue(first.connected)
        assertSame(first, session.acquire(next) { Connection() })
    }

    @Test fun finishingTheActivityDiscardsTheConnection() {
        val session = ReloadSession<Connection>(); val old = Any()
        val first = session.acquire(old) { Connection() }
        session.release(old, discard = true) { it.connected = false }
        assertFalse(first.connected)
        assertNotSame(first, session.acquire(Any()) { Connection() })
    }

    @Test fun repeatedCleanupRunsOnce() {
        val session = ReloadSession<Connection>(); val owner = Any()
        val first = session.acquire(owner) { Connection() }
        repeat(2) { session.release(owner) { it.detachCount++ } }
        assertEquals(1, first.detachCount)
    }
}
