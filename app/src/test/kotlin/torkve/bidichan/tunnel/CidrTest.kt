package torkve.bidichan.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/**
 * These run on the JVM: Cidr.kt is arithmetic over java.net types with nothing
 * from the platform in it, which makes the one part of the tunnel bring-up that
 * can be checked without a device worth checking.
 */
class CidrTest {

    /**
     * Compares addresses, not their spelling. `getHostAddress` is free to write
     * an IPv6 address expanded or compressed, and what matters is which address
     * it is — that is what the platform parses back out of the route.
     */
    private fun assertAddress(expected: String, actual: String?) {
        assertNotNull("expected $expected, got null", actual)
        assertEquals(InetAddress.getByName(expected), InetAddress.getByName(actual))
    }

    /**
     * The regression this file was written for.
     *
     * `VpnService.Builder.addRoute` refuses an address with any bit set below
     * its prefix and throws `IllegalArgumentException: Bad address`. The route
     * was being built from the interface address, which for the default IPv6
     * profile is fd00:bd::2/64 — so every connect on a profile that had IPv6
     * and was not full-tunnel killed the app before it reached the network.
     */
    @Test
    fun `masks the host bits an interface address carries`() {
        assertAddress("10.42.0.0", networkBase("10.42.0.2", 24))
        assertAddress("fd00:bd::", networkBase("fd00:bd::2", 64))
    }

    @Test
    fun `masks on boundaries that are not whole bytes`() {
        assertAddress("10.42.0.128", networkBase("10.42.0.130", 25))
        assertAddress("10.42.0.128", networkBase("10.42.0.130", 26))
        assertAddress("10.42.0.0", networkBase("10.42.0.126", 25))
        assertAddress("192.168.1.16", networkBase("192.168.1.23", 28))
        assertAddress("fd00:bd:8000::", networkBase("fd00:bd:ffff::2", 33))
    }

    @Test
    fun `keeps an address that is already a network base`() {
        assertAddress("10.42.0.0", networkBase("10.42.0.0", 24))
        assertAddress("fd00:bd::", networkBase("fd00:bd::", 64))
    }

    @Test
    fun `handles the whole and empty prefixes`() {
        assertAddress("0.0.0.0", networkBase("10.42.0.2", 0))
        assertAddress("10.42.0.2", networkBase("10.42.0.2", 32))
        assertAddress("::", networkBase("fd00:bd::2", 0))
        assertAddress("fd00:bd::2", networkBase("fd00:bd::2", 128))
    }

    /**
     * A profile we cannot route says so, rather than handing back something
     * unmasked for addRoute to reject — which is how this became a crash rather
     * than a route that was quietly missing.
     */
    @Test
    fun `refuses what it cannot work out`() {
        assertNull(networkBase("not-an-address", 24))
        assertNull(networkBase("", 24))
        assertNull(networkBase("10.42.0.2", 33))
        assertNull(networkBase("fd00:bd::2", 129))
        assertNull(networkBase("10.42.0.2", -1))
    }

    @Test
    fun `splits a cidr into its parts`() {
        assertEquals("10.42.0.2" to 24, cidrParts("10.42.0.2/24"))
        assertEquals("fd00:bd::2" to 64, cidrParts("fd00:bd::2/64"))
        assertNull(cidrParts("10.42.0.2"))
        assertNull(cidrParts("10.42.0.2/abc"))
        assertNull(cidrParts("10.42.0.2/33"))
        assertNull(cidrParts("fd00:bd::2/129"))
    }

    /**
     * Both ends of the packet channel need different addresses, or return
     * traffic is delivered locally instead of across the tunnel.
     */
    @Test
    fun `picks a gateway that is not the device`() {
        assertEquals("10.42.0.1/24", gatewayCidr("10.42.0.2/24"))
        assertEquals("10.42.0.2/24", gatewayCidr("10.42.0.1/24"))
        val (gw6, prefix6) = cidrParts(gatewayCidr6("fd00:bd::2/64"))!!
        assertAddress("fd00:bd::1", gw6)
        assertEquals(64, prefix6)
        assertAddress("fd00:bd::2", cidrParts(gatewayCidr6("fd00:bd::1/64"))!!.first)
    }
}
