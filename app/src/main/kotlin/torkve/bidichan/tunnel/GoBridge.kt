package torkve.bidichan.tunnel

import android.util.Log
import torkve.bidichan.go.mobile.Client
import torkve.bidichan.go.mobile.LinkObserver
import torkve.bidichan.go.mobile.Logger
import torkve.bidichan.go.mobile.Mobile
import torkve.bidichan.go.mobile.ShellSession
import torkve.bidichan.go.mobile.SocketProtector

/**
 * The one place that touches the gomobile-generated binding. Everything else in
 * the app goes through this, so the generated API surface stays contained.
 */
class GoBridge {
    private val client: Client = Mobile.newClient()

    // The binding holds Go-side references weakly, so anything we implement and
    // hand across has to be owned here or it is collected mid-session.
    private var logger: Logger? = null
    private var observer: LinkObserver? = null
    private var protector: SocketProtector? = null

    /** What the core reports about the connection underneath a running session. */
    enum class LinkState {
        /** Traffic is flowing. */
        UP,

        /**
         * The network is gone and the core is redialing. The session, its
         * channels and the connections inside them are stalled, not closed —
         * nothing needs rebuilding if the link returns within the grace period.
         */
        DOWN,

        /** The session could not be resumed; it is being rebuilt from scratch. */
        FAILED;

        companion object {
            fun parse(raw: String?): LinkState? = when (raw) {
                "up" -> UP
                "down" -> DOWN
                "failed" -> FAILED
                else -> null
            }
        }
    }

    init {
        val sink = Logger { line ->
            line?.trim()?.takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "go: $it") }
        }
        logger = sink
        client.setLogger(sink)
    }

    /**
     * Installs the connection-state sink. Must be called before [start].
     *
     * @param onState every link transition.
     * @param onSession a session came up; the flag is true when it replaces one
     *   that was lost, meaning channels have to be reopened.
     */
    fun observeLink(onState: (LinkState) -> Unit, onSession: (Boolean) -> Unit) {
        val obs = object : LinkObserver {
            override fun onLinkState(state: String?) {
                LinkState.parse(state)?.let(onState)
            }

            override fun onSessionUp(reestablished: Boolean) = onSession(reestablished)
        }
        observer = obs
        client.setLinkObserver(obs)
    }

    /**
     * Starts the peer connection, blocking until it is up or the attempt fails.
     * Must not be called on the main thread.
     *
     * @param tunFd the packet interface's descriptor, already detached so the
     *   core owns it, or 0 when no packet interface is wanted.
     * @param protect marks a socket as exempt from the tunnel, so our own
     *   connection is not routed through ourselves. Called on every dial,
     *   including the redials the core makes when the network moves.
     */
    fun start(
        addr: String,
        hostname: String,
        pskHex: String,
        path: String,
        noTlsBinding: Boolean,
        caCertPem: String,
        fingerprint: String,
        memoryLimitMb: Int,
        resumeGraceSeconds: Int,
        tunFd: Int,
        protect: (Int) -> Boolean,
    ) {
        val cfg = Mobile.newConfig()
        cfg.addr = addr
        cfg.hostname = hostname
        cfg.pskHex = pskHex
        cfg.path = path
        cfg.noTLSBinding = noTlsBinding
        cfg.caCertPEM = caCertPem.toByteArray()
        cfg.fingerprint = fingerprint
        cfg.memoryLimitMB = memoryLimitMb.toLong()
        cfg.resumeGraceSeconds = resumeGraceSeconds.toLong()

        // The binding maps Go's int to a Java long, so the descriptor arrives
        // wider than the platform call takes.
        val p = SocketProtector { fd -> protect(fd.toInt()) }
        protector = p
        client.start(cfg, tunFd.toLong(), p)
    }

    /** Forwards a control request and returns the response, both JSON. */
    fun control(json: String): String = client.control(json)

    /** Opens an interactive shell channel on the peer. */
    fun openShell(term: String, rows: Int, cols: Int): GoShell =
        GoShell(client.openShell(term, rows.toLong(), cols.toLong()))

    /**
     * Points the core at a new packet interface. Needed before reopening the
     * channel on a rebuilt session: a descriptor is surrendered once and closed
     * with the channel that used it.
     */
    fun setTunFd(fd: Int) = client.setTunFD(fd.toLong())

    /**
     * Tells the core the network path changed. The system knows this long
     * before a socket on the old path times out, so this replaces the dead
     * socket at once instead of waiting for a timeout.
     */
    fun networkChanged() = client.networkChanged()

    /** Blocks until the client stops for good; returns the reason, null if clean. */
    fun waitUntilDone(): String? = try {
        client.awaitDone()
        null
    } catch (e: Exception) {
        e.message
    }

    fun stop() {
        runCatching { client.stop() }
    }

    private companion object {
        const val TAG = "bidichan"
    }
}

/** Wraps a gomobile shell session. */
class GoShell(private val session: ShellSession) {
    /** Blocks until output arrives; throws when the shell ends. */
    fun read(): ByteArray = session.read()

    fun write(data: ByteArray) = session.write(data)

    fun resize(rows: Int, cols: Int) = session.resize(rows.toLong(), cols.toLong())

    fun close() {
        runCatching { session.close() }
    }
}
