package torkve.bidichan.tunnel

/**
 * Serialises the service lifecycle with link callbacks arriving from Go.
 *
 * A callback may already be in flight when the user disconnects. Once [stop]
 * wins, [update] refuses every late state so the UI cannot jump back to
 * "Connected" after the VPN has gone away.
 */
internal class TunnelStatus(
    private val publish: (String) -> Unit = {},
) {
    private val lock = Any()

    @Volatile
    var value: String = DISCONNECTED
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    init {
        publish(value)
    }

    fun start(): Boolean = synchronized(lock) {
        if (isRunning) return@synchronized false
        isRunning = true
        set(CONNECTING)
        true
    }

    /** Updates a live tunnel, returning false once shutdown has begun. */
    fun update(next: String): Boolean = synchronized(lock) {
        if (!isRunning) return@synchronized false
        set(next)
        true
    }

    /** Marks the tunnel stopped before resource teardown begins. */
    fun stop(): Boolean = synchronized(lock) {
        val wasRunning = isRunning
        isRunning = false
        set(DISCONNECTED)
        wasRunning
    }

    private fun set(next: String) {
        value = next
        publish(next)
    }

    companion object {
        const val DISCONNECTED = "Disconnected"
        const val CONNECTING = "Connecting…"
    }
}
