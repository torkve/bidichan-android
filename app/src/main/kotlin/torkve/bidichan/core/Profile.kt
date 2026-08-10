package torkve.bidichan.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A connection profile. Everything here is non-secret and stored in the app's
 * private files; the pre-shared key lives separately in [Secrets], keyed by
 * [pskAccount].
 *
 * Every field has a default and the decoder ignores unknown keys, so a profile
 * written by an older build still loads after an update instead of being
 * dropped — adding a field must never cost the user their saved profiles.
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New profile",
    /** host:port of the server, e.g. "gate.example.com:443". */
    val serverAddress: String = "",
    /** SNI and Host header. */
    val hostname: String = "",
    /** WebSocket path; empty derives it from the pre-shared key. */
    val path: String = "",
    /** True when the server sits behind a TLS-terminating reverse proxy. */
    val noTlsBinding: Boolean = true,
    /** "ios" | "safari" | "chrome" — which ClientHello to present. */
    val fingerprint: String = "chrome",
    /** Optional PEM bundle to pin; empty uses the system trust store. */
    val caCertPem: String = "",

    // Packet interface settings.
    val enableTun: Boolean = true,
    /** This device's address inside the tunnel. */
    val tunCidr: String = "10.42.0.2/24",
    /** This device's IPv6 inside the tunnel; empty disables v6. */
    val tunCidr6: String = "fd00:bd::2/64",
    val tunMtu: Int = 1400,
    /** Route everything, rather than just the tunnel's own subnet. */
    val fullTunnel: Boolean = false,
    /** Soft heap cap for the embedded core, in MB. */
    val memoryLimitMb: Int = 40,
    /**
     * How long the network may be gone before the session is given up. Inside
     * this window the tunnel reconnects by itself and open channels — and the
     * connections running through them — carry on where they left off.
     */
    val resumeGraceSeconds: Int = 90,

    /** Channels opened automatically once this profile connects. */
    @SerialName("channels")
    val channels: List<ChannelConfig> = emptyList(),
) {
    val pskAccount: String get() = "psk-$id"

    /**
     * The profile with its numeric fields brought back inside usable ranges.
     *
     * The editor takes these as free text, so nothing stops a typo from saving
     * a reconnect window of 7200 seconds — a profile that connects, but that
     * the shared link format then refuses, leaving it unshareable for a reason
     * the editor never mentioned. These ranges are the ones the iOS steppers
     * offer, and they sit inside what a link accepts.
     */
    fun withUsableNumbers(): Profile = copy(
        tunMtu = tunMtu.coerceIn(MTU_RANGE),
        memoryLimitMb = memoryLimitMb.coerceIn(MEMORY_MB_RANGE),
        resumeGraceSeconds = resumeGraceSeconds.coerceIn(RESUME_GRACE_RANGE),
    )

    companion object {
        val MTU_RANGE = 1000..1500
        val MEMORY_MB_RANGE = 20..80
        val RESUME_GRACE_RANGE = 15..600
    }
}

/**
 * A channel to open automatically on connect. Mirrors what the "Add channel"
 * sheet offers: kind, bind interface, port and (for forwards) a target.
 */
@Serializable
data class ChannelConfig(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val kind: Kind = Kind.HTTP,
    /** false binds loopback only. */
    val allInterfaces: Boolean = false,
    val port: Int = 3128,
    /** "host:port"; forwards only. */
    val target: String = "127.0.0.1:80",
    /**
     * Proxies only: publish it to the system so other apps go through it. The
     * platform accepts an HTTP proxy on the packet interface from API 29; below
     * that, and for SOCKS5, apps have to be pointed at it themselves.
     */
    val routeSystem: Boolean = true,
) {
    @Serializable
    enum class Kind {
        @SerialName("socks5")
        SOCKS5,

        @SerialName("http")
        HTTP,

        @SerialName("forwardLocal")
        FORWARD_LOCAL,

        @SerialName("forwardRemote")
        FORWARD_REMOTE;

        val title: String
            get() = when (this) {
                SOCKS5 -> "SOCKS5 proxy"
                HTTP -> "HTTP proxy"
                FORWARD_LOCAL -> "Port forward (-L)"
                FORWARD_REMOTE -> "Port forward (-R)"
            }

        val isProxy: Boolean get() = this == SOCKS5 || this == HTTP

        /** Which side hosts the listener; only a remote forward listens on the peer. */
        val side: String get() = if (this == FORWARD_REMOTE) "remote" else "local"

        val proxyKind: String get() = if (this == HTTP) "http" else "socks5"

        val defaultPort: Int
            get() = when (this) {
                SOCKS5 -> 1080
                HTTP -> 3128
                FORWARD_LOCAL, FORWARD_REMOTE -> 8080
            }

        /**
         * The name this kind travels under in a shared link and in the control
         * protocol. It matches what the iOS client uses, so a link written by
         * either is understood by the other.
         */
        val wireName: String
            get() = when (this) {
                SOCKS5 -> "socks5"
                HTTP -> "http"
                FORWARD_LOCAL -> "forwardLocal"
                FORWARD_REMOTE -> "forwardRemote"
            }

        companion object {
            fun fromWire(name: String): Kind =
                entries.firstOrNull { it.wireName == name } ?: HTTP
        }
    }

    val host: String get() = if (allInterfaces) "0.0.0.0" else "127.0.0.1"
    val listenAddr: String get() = "$host:$port"

    val displayName: String
        get() = when {
            label.isNotEmpty() -> label
            kind.isProxy -> "${kind.proxyKind} :$port"
            else -> "${kind.title} :$port"
        }
}
