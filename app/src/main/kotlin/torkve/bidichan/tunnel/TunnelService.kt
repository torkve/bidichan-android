package torkve.bidichan.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.net.ProxyInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import torkve.bidichan.MainActivity
import torkve.bidichan.R
import torkve.bidichan.core.AppLog
import torkve.bidichan.core.ChannelConfig
import torkve.bidichan.core.Control
import torkve.bidichan.core.ControlDecode
import torkve.bidichan.core.Profile
import torkve.bidichan.core.ProfileStore
import torkve.bidichan.core.Secrets
import java.util.concurrent.Executors

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
    private var profile: Profile? = null
    private val status = TunnelStatus { state = it }
    private var restoring = false

    /** Channel opens issued so far, so a rebuilt session can be restored. */
    private data class OpenChannel(val id: Long, val requestJson: String)

    private val openChannels = mutableListOf<OpenChannel>()
    private val channelsLock = Any()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Deliberate: forget the profile so a later restart of this
                // service does not bring back a tunnel the user switched off.
                forgetWanted()
                shutdown()
                return START_NOT_STICKY
            }
        }
        // The system hands back a null intent when it restarts a service whose
        // process it killed, and the profile went with it. Remembering which
        // one was wanted is what lets the tunnel come back by itself rather
        // than staying down until someone opens the app and notices.
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID) ?: wantedProfileId()
        if (profileId == null) {
            AppLog.log("start without a profile; ignoring")
            return START_NOT_STICKY
        }
        if (intent?.getStringExtra(EXTRA_PROFILE_ID) == null) {
            AppLog.log("restarted by the system after the process went away; reconnecting")
        }
        rememberWanted(profileId)
        if (!status.start()) {
            AppLog.log("already running")
            return START_REDELIVER_INTENT
        }
        startForeground(NOTIFICATION_ID, notification(status.value))
        io.execute {
            // Bring-up runs on a worker, where an exception nobody catches
            // takes the whole app down rather than failing the connection.
            // Whatever went wrong, the user is better served by a profile that
            // will not connect and says so than by the app disappearing.
            try {
                connect(profileId)
            } catch (t: Throwable) {
                fail("could not connect: ${t.message ?: t::class.java.simpleName}")
            }
        }
        // REDELIVER rather than STICKY: a sticky restart arrives with a null
        // intent, so the profile is lost and the service used to give up on
        // the spot. Redelivery brings the original intent back, extras and all.
        return START_REDELIVER_INTENT
    }

    /**
     * The profile the user last asked for, kept where it outlives the process.
     *
     * Plain preferences, not the encrypted store: an identifier is not a
     * secret, and this has to be readable the instant the service restarts.
     */
    private fun wanted() = getSharedPreferences("tunnel-state", Context.MODE_PRIVATE)

    private fun rememberWanted(profileId: String) {
        runCatching { wanted().edit().putString(KEY_WANTED_PROFILE, profileId).apply() }
    }

    private fun forgetWanted() {
        runCatching { wanted().edit().remove(KEY_WANTED_PROFILE).apply() }
    }

    private fun wantedProfileId(): String? =
        runCatching { wanted().getString(KEY_WANTED_PROFILE, null) }.getOrNull()

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

        // Resolve before the packet interface exists: once it is up, a name
        // lookup can be routed into the tunnel we have not finished building.
        val addr = resolveServerAddress(this, p.serverAddress)

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
                addr = addr,
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

        // Disconnect may have arrived while start() was blocked. Do not open
        // channels or publish Connected after shutdown has already won.
        if (!status.isRunning) {
            b.stop()
            return
        }

        AppLog.log("peer is up")
        // Before the channels, so nothing below races the device suspending.
        holdCpuAwake()
        // Recorded on every connect so a report of "it went offline in my
        // pocket" carries its own answer: without the exemption, Doze ignores
        // the lock above and the tunnel will not outlast the screen.
        if (!BatteryExemption.isExempt(this)) {
            AppLog.log("battery optimisation is on for this app — sleep will end the tunnel")
        }
        if (p.enableTun) {
            runCatching { openTunChannel(b, p) }
                .onFailure { AppLog.log("packet channel: ${it.message}") }
        }
        openDefaultChannels(b, p)
        updateNotification("Connected")
        watchNetwork()
        io.execute { awaitEnd(b) }
    }

    /**
     * Builds the system packet interface from the profile and hands its
     * descriptor to the core.
     *
     * detachFd transfers ownership, so nothing is kept here: from this point
     * the core owns the descriptor and closes it with the channel that used
     * it. Establishing again replaces the previous interface, so there is
     * nothing for this service to tear down either.
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
            // Only the tunnel's own subnet goes through it. Both families are
            // masked to their network base first: addRoute refuses an address
            // carrying any bit below its prefix, so an interface address like
            // fd00:bd::2/64 cannot be handed to it as it stands. A subnet we
            // cannot make sense of is left unrouted and logged, rather than
            // being allowed to take the connection down with it.
            for (cidr in listOf(p.tunCidr, p.tunCidr6)) {
                if (cidr.isEmpty()) continue
                val parts = cidrParts(cidr)
                val base = parts?.let { (addr, prefix) -> networkBase(addr, prefix) }
                if (parts == null || base == null) {
                    AppLog.log("cannot work out the subnet of $cidr; leaving it unrouted")
                    continue
                }
                builder.addRoute(base, parts.second)
            }
        }

        // A proxy channel the profile wants published: the platform can point
        // apps at an HTTP proxy reachable over the tunnel. SOCKS5 has no such
        // hook, and neither does anything below API 29 — there apps have to be
        // pointed at the listener themselves.
        val published = p.channels.firstOrNull { it.kind.isProxy && it.routeSystem }
        if (published != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (published.kind == ChannelConfig.Kind.HTTP) {
                runCatching {
                    builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", published.port))
                    AppLog.log("publishing the http proxy on port ${published.port} to the system")
                }.onFailure { AppLog.log("could not publish the proxy: ${it.message}") }
            } else {
                AppLog.log("a socks5 proxy cannot be published system-wide; point apps at it")
            }
        }

        // Never route ourselves through ourselves.
        runCatching { builder.addDisallowedApplication(packageName) }

        val pfd = builder.establish() ?: return 0
        return pfd.detachFd()
    }

    private fun openTunChannel(b: GoBridge, p: Profile) {
        // Give the peer a different address in the same subnet than this
        // device, or both ends share one address and replies never come back.
        val gw = gatewayCidr(p.tunCidr)
        val gw6 = if (p.tunCidr6.isEmpty()) null else gatewayCidr6(p.tunCidr6)
        val request = Control.openTun(Control.TunArgs(cidr = gw, cidr6 = gw6, mtu = p.tunMtu))
        ControlDecode.open(b.control(request))
        AppLog.log("packet channel is open")
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
                .onFailure { AppLog.log("channel ${c.displayName}: ${it.message}") }
        }
    }

    // MARK: - Reconnection

    /**
     * Watches the network. The system knows the path changed long before a
     * socket on the old one times out, so telling the core lets it drop the
     * dead socket and redial at once.
     */
    /**
     * Keeps the CPU running while the tunnel is up.
     *
     * The core keeps the session alive with a timer, and a suspended CPU does
     * not run timers: the keepalive stops, the peer hears nothing, and it drops
     * the session once the grace window passes. The device then wakes to a
     * tunnel that has to be built from scratch — which is what "it goes offline
     * in my pocket" looks like from the outside.
     *
     * A partial lock leaves the screen alone; it only prevents deep sleep, and
     * it is held exactly as long as the tunnel is.
     */
    private fun holdCpuAwake() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        runCatching {
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bidichan:tunnel")
            // Not reference counted: acquired once here, released once in
            // shutdown, and release must not depend on matching counts.
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
            AppLog.log("holding the cpu awake for the tunnel")
        }.onFailure { AppLog.log("could not hold the cpu awake: ${it.message}") }
    }

    private fun releaseCpu() {
        wakeLock?.let { wl -> runCatching { if (wl.isHeld) wl.release() } }
        wakeLock = null
    }

    /**
     * Tells the platform which network the tunnel is riding on.
     *
     * Without this the system treats the tunnel as standing on nothing: it
     * cannot follow the VPN across a network change, and it accounts for its
     * traffic against no transport. Passing null hands the decision back to the
     * platform's default network.
     */
    private fun publishUnderlyingNetwork(network: Network?) {
        runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
            .onFailure { AppLog.log("could not publish the underlying network: ${it.message}") }
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var known: Network? = null

            /**
             * Set when the network we were on went away, so its return is told
             * apart from the callback that arrives merely because we just
             * registered — the latter must not redial a link that is fine.
             */
            private var wentAway = false

            override fun onAvailable(network: Network) {
                val previous = known
                known = network
                publishUnderlyingNetwork(network)
                val returned = wentAway
                wentAway = false
                // Redial when the path was swapped underneath us, and when the
                // one we were on came back after a gap: the same network can
                // return, and either way the socket from before it went is
                // dead. Not on the first callback, which says only that we are
                // now listening.
                if ((previous != null && previous != network) || returned) {
                    AppLog.log("network path changed; redialing now")
                    bridge?.networkChanged()
                }
            }

            override fun onLost(network: Network) {
                if (network != known) return
                known = null
                wentAway = true
                AppLog.log("the network underneath went away")
            }
        }
        // The default network, rather than any network matching a request: what
        // the tunnel rides on is whatever the system would route through, and
        // that is the thing worth following.
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess {
                networkCallback = cb
                publishUnderlyingNetwork(cm.activeNetwork)
            }
    }

    private fun onLinkState(state: GoBridge.LinkState) {
        if (!status.isRunning) return
        when (state) {
            GoBridge.LinkState.UP -> {
                AppLog.log("link up")
                // While a lost session is being rebuilt the link comes up
                // before the channels are back; stay in "reconnecting" until
                // they are, so we never claim to be connected but empty.
                if (!restoring) updateNotification("Connected")
            }
            GoBridge.LinkState.DOWN -> {
                AppLog.log("link down — channels are stalled while the core redials")
                updateNotification("Reconnecting…")
            }
            GoBridge.LinkState.FAILED -> {
                AppLog.log("session lost — rebuilding it")
                restoring = true
                updateNotification("Reconnecting…")
            }
        }
    }

    private fun onSessionUp(reestablished: Boolean) {
        if (!reestablished || !status.isRunning) return
        val b = bridge ?: return
        val p = profile ?: return
        io.execute {
            if (!status.isRunning) return@execute
            AppLog.log("session reestablished: restoring channels")
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
        val fd = establishInterface(p)
        if (fd == 0) {
            AppLog.log("could not re-establish the packet interface")
            return
        }
        b.setTunFd(fd)
        runCatching { openTunChannel(b, p) }
            .onFailure { AppLog.log("packet channel: reopen failed: ${it.message}") }
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
                .onFailure { AppLog.log("channel reopen failed: ${it.message}") }
        }
    }

    /**
     * Remembers a successful channel open, and forgets one that was closed, so
     * a rebuilt session can be brought back to the same set of channels.
     */
    private fun recordChannel(request: String, response: String) {
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
        AppLog.log("core stopped: ${reason ?: "clean shutdown"}")
        shutdown()
    }

    // MARK: - Teardown

    private fun fail(message: String) {
        // A deliberate Disconnect closes a blocking start and may make it
        // return an error. Shutdown has already won in that case, so do not
        // turn the user's action into a spurious connection failure.
        if (!status.isRunning) return
        AppLog.log("start failed: $message")
        lastError = message
        shutdown()
    }

    private fun shutdown() {
        // Publish Disconnected first and close the state gate. Link callbacks
        // already queued by the Go core can no longer overwrite it.
        val wasRunning = status.stop()
        // Ahead of the early return, and idempotent: a lock outliving the
        // tunnel would keep the device from sleeping with nothing to show for
        // it, which is worse than the problem it was taken for.
        releaseCpu()
        if (!wasRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        // Stopping the core closes the descriptor it owns, and the system tears
        // the interface down with this service.
        bridge?.stop()
        bridge = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        instance = null
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onRevoke() {
        AppLog.log("the packet interface was revoked")
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
        if (!status.update(text)) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification(text))
        }
    }

    /**
     * Forwards a control request to the core. The UI runs in this same process,
     * so it reaches the running tunnel directly rather than through IPC — but
     * the call blocks on the core, so it must not run on the main thread.
     */
    private fun controlNow(json: String): String {
        val b = bridge ?: throw IllegalStateException("the tunnel is not running")
        val response = b.control(json)
        recordChannel(json, response)
        return response
    }

    private fun openShellNow(term: String, rows: Int, cols: Int): GoShell {
        val b = bridge ?: throw IllegalStateException("the tunnel is not running")
        return b.openShell(term, rows, cols)
    }

    companion object {
        private const val TAG = "bidichan"
        private const val CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "torkve.bidichan.STOP"
        const val EXTRA_PROFILE_ID = "profileId"

        /** The profile to bring back if the system restarts us on its own. */
        private const val KEY_WANTED_PROFILE = "wantedProfile"

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

        /** The running service, or null. Same process, so this is a plain reference. */
        @Volatile
        private var instance: TunnelService? = null

        val isRunning: Boolean get() = instance?.status?.isRunning == true

        /** Blocks on the core; call from a background thread. */
        fun control(json: String): String =
            instance?.controlNow(json) ?: throw IllegalStateException("the tunnel is not running")

        /** Blocks; call from a background thread. */
        fun openShell(term: String, rows: Int, cols: Int): GoShell =
            instance?.openShellNow(term, rows, cols)
                ?: throw IllegalStateException("the tunnel is not running")
    }
}
