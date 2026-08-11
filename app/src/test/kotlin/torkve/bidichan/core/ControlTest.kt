package torkve.bidichan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control requests are the same JSON the command-line client speaks, and
 * the core validates every field. Nothing about that contract is visible at
 * compile time, so it is pinned here — these run on the JVM, since Control.kt
 * has nothing from the platform in it.
 */
class ControlTest {

    /**
     * The regression this was written for.
     *
     * `tun_side` is only ever supplied by its default, and the encoder was
     * configured not to write defaults, so it never reached the core — which
     * read it as "" and refused the channel with `invalid side ""`. The tunnel
     * then reported itself connected while carrying nothing, and for a
     * full-tunnel profile that means every packet routed into a channel that
     * was never opened.
     */
    @Test
    fun `an open_tun request carries the side`() {
        val request = Control.openTun(Control.TunArgs(cidr = "10.42.0.1/24", mtu = 1400))
        assertTrue("no tun_side in $request", request.contains("\"tun_side\":\"local\""))
        assertTrue(request.contains("\"action\":\"open_tun\""))
        assertTrue(request.contains("\"cidr\":\"10.42.0.1/24\""))
        assertTrue(request.contains("\"mtu\":1400"))
    }

    /**
     * The other half of the same setting: optional fields the caller left alone
     * must stay out of the request rather than being sent as null, which the
     * core would read as a value.
     */
    @Test
    fun `omits what the caller did not set`() {
        val request = Control.openTun(Control.TunArgs(cidr = "10.42.0.1/24", mtu = 1400))
        assertFalse("cidr6 should be absent, not null: $request", request.contains("cidr6"))
        assertFalse("label should be absent, not null: $request", request.contains("label"))
        assertFalse("name should be absent, not null: $request", request.contains("\"name\""))
        assertFalse(request.contains("null"))
    }

    @Test
    fun `carries the v6 subnet when there is one`() {
        val request = Control.openTun(
            Control.TunArgs(cidr = "10.42.0.1/24", cidr6 = "fd00:bd::1/64", mtu = 1400),
        )
        assertTrue(request.contains("\"cidr6\":\"fd00:bd::1/64\""))
    }

    @Test
    fun `builds the proxy and forward requests the core expects`() {
        val http = Control.openHttp(
            Control.ProxyArgs(listenSide = "local", listenAddr = "127.0.0.1:3128"),
        )
        assertTrue(http.contains("\"action\":\"open_http\""))
        assertTrue(http.contains("\"listen_side\":\"local\""))
        assertTrue(http.contains("\"listen_addr\":\"127.0.0.1:3128\""))

        val socks = Control.openSocks5(
            Control.ProxyArgs(listenSide = "local", listenAddr = "127.0.0.1:1080"),
        )
        assertTrue(socks.contains("\"action\":\"open_socks5\""))

        val forward = Control.openForward(
            Control.ForwardArgs(
                listenSide = "remote",
                listenAddr = "127.0.0.1:8080",
                targetAddr = "10.0.0.5:80",
            ),
        )
        assertTrue(forward.contains("\"action\":\"open_forward\""))
        assertTrue(forward.contains("\"listen_side\":\"remote\""))
        assertTrue(forward.contains("\"target_addr\":\"10.0.0.5:80\""))
    }

    @Test
    fun `a request without arguments carries none`() {
        assertEquals("""{"action":"status"}""", Control.status())
    }
}
