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
