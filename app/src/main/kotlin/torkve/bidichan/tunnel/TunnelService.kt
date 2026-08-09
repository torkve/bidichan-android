package torkve.bidichan.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import torkve.bidichan.MainActivity
import torkve.bidichan.R
import torkve.bidichan.core.ChannelConfig
import torkve.bidichan.core.Control
import torkve.bidichan.core.ControlDecode
import torkve.bidichan.core.Profile
import torkve.bidichan.core.ProfileStore
import torkve.bidichan.core.Secrets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the embedded core and owns the system packet interface.
 *
 * The tunnel is meant to outlive the network it started on. A flap does not
 * stop it: the core resumes the same session over a fresh connection, so the
 * channels and the connections inside them carry on where they left off, and
 * all this service does is say so. Only when a session is lost for good does it
 * rebuild — reopening the packet channel and replaying the channels that were
 * asked for.
 */
class TunnelService : VpnService() {

    private val io = Executors.newCachedThreadPool()
    private var bridge: GoBridge? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var profile: Profile? = null
    private val running = AtomicBoolean(false)
    private var restoring = false

    /** Channel opens issued so far, so a rebuilt session can be restored. */
    private data class OpenChannel(val id: Long, val requestJson: String)

    private val openChannels = mutableListOf<OpenChannel>()
    private val channelsLock = Any()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId == null) {
            Log.w(TAG, "start without a profile; ignoring")
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "already running")
            return START_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Connecting…"))
        io.execute { connect(profileId) }
        return START_STICKY
    }

    // MARK: - Bring-up

    private fun connect(profileId: String) {
        val store = ProfileStore(this)
        val secrets = Secrets(this)
        val p = store.byId(profileId) ?: run {
            fail("no such profile")
            return
        }
        profile = p
        val psk = secrets[p.pskAccount]
        if (psk.isNullOrEmpty()) {
            fail("this profile has no pre-shared key set")
            return
        }

        val fd = if (p.enableTun) establishInterface(p) else 0
        if (p.enableTun && fd == 0) {
            fail("the system did not grant a packet interface")
            return
        }

        val b = GoBridge()
        bridge = b
        b.observeLink(::onLinkState) { reestablished -> onSessionUp(reestablished) }

        try {
            b.start(
                addr = p.serverAddress,
                hostname = p.hostname,
                pskHex = psk,
                path = p.path,
                noTlsBinding = p.noTlsBinding,
                caCertPem = p.caCertPem,
                fingerprint = p.fingerprint,
                memoryLimitMb = p.memoryLimitMb,
                resumeGraceSeconds = p.resumeGraceSeconds,
                tunFd = fd,
                protect = { socketFd -> protect(socketFd) },
            )
        } catch (e: Exception) {
            fail("could not connect: ${e.message}")
            return
        }

        Log.i(TAG, "peer is up")
        if (p.enableTun) {
            runCatching { openTunChannel(b, p) }
                .onFailure { Log.e(TAG, "packet channel: ${it.message}") }
        }
        openDefaultChannels(b, p)
        updateNotification("Connected")
        watchNetwork()
        io.execute { awaitEnd(b) }
    }

    /**
     * Builds the system packet interface from the profile and hands its
     * descriptor to the core. detachFd transfers ownership: from here on the
     * core closes it, and this service must not.
     */
    private fun establishInterface(p: Profile): Int {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(p.tunMtu)

        cidrParts(p.tunCidr)?.let { (addr, prefix) -> builder.addAddress(addr, prefix) }
        if (p.tunCidr6.isNotEmpty()) {
            cidrParts(p.tunCidr6)?.let { (addr, prefix) -> builder.addAddress(addr, prefix) }
        }

        if (p.fullTunnel) {
            builder.addRoute("0.0.0.0", 0)
            if (p.tunCidr6.isNotEmpty()) builder.addRoute("::", 0)
            // Resolvers reachable through the tunnel rather than the local network.
            builder.addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
            if (p.tunCidr6.isNotEmpty()) {
                builder.addDnsServer("2606:4700:4700::1111").addDnsServer("2001:4860:4860::8888")
            }
        } else {
            // Only the tunnel's own subnet goes through it.
            cidrParts(p.tunCidr)?.let { (addr, prefix) ->
                builder.addRoute(networkBase(addr, prefix), prefix)
            }
            if (p.tunCidr6.isNotEmpty()) {
                cidrParts(p.tunCidr6)?.let { (addr, prefix) -> builder.addRoute(addr, prefix) }
            }
        }

        // Never route ourselves through ourselves.
        runCatching { builder.addDisallowedApplication(packageName) }

        val pfd = builder.establish() ?: return 0
        tunnel = pfd
        return pfd.detachFd()
    }

    private fun openTunChannel(b: GoBridge, p: Profile) {
        // Give the peer a different address in the same subnet than this
        // device, or both ends share one address and replies never come back.
        val gw = gatewayCidr(p.tunCidr)
        val gw6 = if (p.tunCidr6.isEmpty()) null else gatewayCidr6(p.tunCidr6)
        val request = Control.openTun(Control.TunArgs(cidr = gw, cidr6 = gw6, mtu = p.tunMtu))
        ControlDecode.open(b.control(request))
        Log.i(TAG, "packet channel is open")
    }

    private fun openDefaultChannels(b: GoBridge, p: Profile) {
        for (c in p.channels) {
            val request = when {
                c.kind.isProxy -> {
                    val args = Control.ProxyArgs(
                        listenSide = "local",
                        listenAddr = c.listenAddr,
                        label = c.label.ifEmpty { null },
                    )
                    if (c.kind == ChannelConfig.Kind.HTTP) Control.openHttp(args)
                    else Control.openSocks5(args)
                }
                else -> Control.openForward(
                    Control.ForwardArgs(
                        listenSide = c.kind.side,
                        listenAddr = c.listenAddr,
                        targetAddr = c.target,
                        label = c.label.ifEmpty { null },
                    )
                )
            }
            runCatching { recordChannel(request, b.control(request)) }
                .onFailure { Log.w(TAG, "channel ${c.displayName}: ${it.message}") }
        }
    }

    // MARK: - Reconnection

    /**
     * Watches the network. The system knows the path changed long before a
     * socket on the old one times out, so telling the core lets it drop the
     * dead socket and redial at once.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var known: Network? = null
            override fun onAvailable(network: Network) {
                val previous = known
                known = network
                if (previous != null && previous != network) {
                    Log.i(TAG, "network path changed; redialing now")
                    bridge?.networkChanged()
                }
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { networkCallback = cb }
    }

    private fun onLinkState(state: GoBridge.LinkState) {
        when (state) {
            GoBridge.LinkState.UP -> {
                Log.i(TAG, "link up")
                // While a lost session is being rebuilt the link comes up
                // before the channels are back; stay in "reconnecting" until
                // they are, so we never claim to be connected but empty.
                if (!restoring) updateNotification("Connected")
            }
            GoBridge.LinkState.DOWN -> {
                Log.i(TAG, "link down — channels are stalled while the core redials")
                updateNotification("Reconnecting…")
            }
            GoBridge.LinkState.FAILED -> {
                Log.i(TAG, "session lost — rebuilding it")
                restoring = true
                updateNotification("Reconnecting…")
            }
        }
    }

    private fun onSessionUp(reestablished: Boolean) {
        if (!reestablished) return
        val b = bridge ?: return
        val p = profile ?: return
        io.execute {
            Log.i(TAG, "session reestablished: restoring channels")
            restoreTunChannel(b, p)
            replayChannels(b)
            restoring = false
            updateNotification("Connected")
        }
    }

    /**
     * Reopens the packet channel on a new session. The old channel closed the
     * descriptor it was given, so a fresh interface is established first.
     */
    private fun restoreTunChannel(b: GoBridge, p: Profile) {
        if (!p.enableTun) return
        runCatching { tunnel?.close() }
        val fd = establishInterface(p)
        if (fd == 0) {
            Log.e(TAG, "could not re-establish the packet interface")
            return
        }
        b.setTunFd(fd)
        runCatching { openTunChannel(b, p) }
            .onFailure { Log.e(TAG, "packet channel: reopen failed: ${it.message}") }
    }

    /** Re-issues the channel opens, re-keyed to the new session's ids. */
    private fun replayChannels(b: GoBridge) {
        val previous = synchronized(channelsLock) {
            val copy = openChannels.toList()
            openChannels.clear()
            copy
        }
        for (channel in previous) {
            runCatching { recordChannel(channel.requestJson, b.control(channel.requestJson)) }
                .onFailure { Log.w(TAG, "channel reopen failed: ${it.message}") }
        }
    }

    /**
     * Remembers a successful channel open, and forgets one that was closed, so
     * a rebuilt session can be brought back to the same set of channels.
     */
    fun recordChannel(request: String, response: String) {
        val obj = runCatching { Json.parseToJsonElement(request).jsonObject }.getOrNull() ?: return
        when (obj["action"]?.jsonPrimitive?.content) {
            "open_forward", "open_http", "open_socks5" -> {
                val id = runCatching { ControlDecode.open(response) }.getOrNull() ?: return
                synchronized(channelsLock) { openChannels.add(OpenChannel(id, request)) }
            }
            "close_channel" -> {
                val args = obj["args"] as? JsonObject ?: return
                val id = args["channel_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                synchronized(channelsLock) { openChannels.removeAll { it.id == id } }
            }
        }
    }

    /**
     * Watches the core. It outlives individual sessions — it returns only once
     * the client has stopped for good — so reaching here means we are finished.
     */
    private fun awaitEnd(b: GoBridge) {
        val reason = b.waitUntilDone()
        Log.i(TAG, "core stopped: ${reason ?: "clean shutdown"}")
        shutdown()
    }

    // MARK: - Teardown

    private fun fail(message: String) {
        Log.e(TAG, "start failed: $message")
        lastError = message
        shutdown()
    }

    private fun shutdown() {
        if (!running.compareAndSet(true, false)) return
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        bridge?.stop()
        bridge = null
        runCatching { tunnel?.close() }
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "the packet interface was revoked")
        shutdown()
    }

    // MARK: - Notification

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tunnel_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.tunnel_channel_description) }
            manager?.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        state = text
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification(text))
        }
    }

    companion object {
        private const val TAG = "bidichan"
        private const val CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "torkve.bidichan.STOP"
        const val EXTRA_PROFILE_ID = "profileId"

        /** Coarse state for the UI; the service is the authority. */
        @Volatile
        var state: String = "Disconnected"
            private set

        @Volatile
        var lastError: String? = null

        fun start(context: Context, profileId: String) {
            val intent = Intent(context, TunnelService::class.java)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TunnelService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
