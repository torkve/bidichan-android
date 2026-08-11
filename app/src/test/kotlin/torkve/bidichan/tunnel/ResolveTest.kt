package torkve.bidichan.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `resolveServerAddress` itself needs a Context and cannot run here, but the
 * decision that routes an address into the platform call — is this already a
 * literal, or a name that has to be looked up — is plain string work and is
 * exactly where a device-only path starts.
 *
 * Worth pinning because a literal server address skips the lookup entirely, so
 * testing only with one hides everything past that branch.
 */
class ResolveTest {

    @Test
    fun `splits host and port`() {
        assertEquals("gate.example.com" to "443", splitHostPort("gate.example.com:443"))
        assertEquals("10.0.2.2" to "8443", splitHostPort("10.0.2.2:8443"))
        assertEquals("fd00:bd::1" to "443", splitHostPort("[fd00:bd::1]:443"))
    }

    @Test
    fun `refuses what is not host and port`() {
        assertNull(splitHostPort("gate.example.com"))
        assertNull(splitHostPort("gate.example.com:"))
        assertNull(splitHostPort(":443"))
        assertNull(splitHostPort("[fd00:bd::1]"))
        assertNull(splitHostPort("[fd00:bd::1"))
    }

    /**
     * A literal returns before the resolver is ever consulted, and a name does
     * not — which is the branch that reaches ConnectivityManager, needs
     * ACCESS_NETWORK_STATE, and was the one nobody had exercised.
     */
    @Test
    fun `tells a literal address from a name`() {
        for (literal in listOf("10.0.2.2", "127.0.0.1", "0.0.0.0", "255.255.255.255",
                               "fd00:bd::1", "::1", "::")) {
            assertTrue("$literal should be a literal", isIpLiteral(literal))
        }
        for (name in listOf("gate.example.com", "localhost", "10.0.2", "10.0.2.2.2",
                            "10.0.2.256", "10.0.2.a", "")) {
            assertFalse("$name should be a name", isIpLiteral(name))
        }
    }
}
