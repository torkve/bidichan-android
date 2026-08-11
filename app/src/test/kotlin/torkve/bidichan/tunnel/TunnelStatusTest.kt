package torkve.bidichan.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStatusTest {
    @Test
    fun lifecyclePublishesEveryVisibleState() {
        val published = mutableListOf<String>()
        val status = TunnelStatus(published::add)

        assertEquals(listOf("Disconnected"), published)
        assertTrue(status.start())
        assertTrue(status.update("Connected"))
        assertTrue(status.stop())

        assertEquals(
            listOf("Disconnected", "Connecting…", "Connected", "Disconnected"),
            published,
        )
        assertFalse(status.isRunning)
    }

    @Test
    fun lateCallbackCannotRestoreConnectedAfterStop() {
        val status = TunnelStatus()
        assertTrue(status.start())
        assertTrue(status.update("Connected"))

        assertTrue(status.stop())
        assertFalse(status.update("Connected"))

        assertEquals("Disconnected", status.value)
    }

    @Test
    fun aStoppedServiceCanStartAgainFromConnecting() {
        val status = TunnelStatus()
        assertTrue(status.start())
        assertFalse(status.start())
        assertTrue(status.stop())

        assertTrue(status.start())
        assertEquals("Connecting…", status.value)
        assertTrue(status.isRunning)
    }
}
