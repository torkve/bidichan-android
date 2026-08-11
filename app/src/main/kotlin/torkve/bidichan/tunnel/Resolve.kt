package torkve.bidichan.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

/**
 * Resolves the server address on the underlying network, before the packet
 * interface is in the way.
 *
 * Only a socket can be marked exempt from the tunnel; the name lookup that
 * would precede a connection cannot. Once the interface is up, resolving a
 * hostname the ordinary way can be steered into the very tunnel we are trying
 * to establish — and until a packet channel is open, nothing is draining our
 * side of it, so the lookup black-holes rather than merely slowing down. The
 * platform's answer is to ask a specific network to do it.
 *
 * The result is handed to the core as an IP literal. The name still travels as
 * the SNI and the certificate is still verified against it, so nothing is lost
 * by resolving early — and the reconnects the core makes on its own reuse the
 * address rather than looking it up again from inside the tunnel.
 */
fun resolveServerAddress(context: Context, addr: String): String {
    val (host, port) = splitHostPort(addr) ?: return addr
    if (isIpLiteral(host)) return addr

    // Every failure here falls back to the address as written, because that is
    // all this function promises: resolving early is an improvement on letting
    // the lookup happen later, never a precondition for connecting. Asking
    // which network is active needs ACCESS_NETWORK_STATE, and a missing
    // permission arrives as an exception — which must not be the thing that
    // stops the tunnel from being built.
    val network = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
    }.onFailure {
        Log.w(TAG, "cannot see the underlying network (${it.message}); using $host as given")
    }.getOrNull() ?: return addr
    val resolved = runCatching { network.getAllByName(host) }.getOrNull()
    val first = resolved?.firstOrNull() ?: run {
        Log.w(TAG, "could not resolve $host on the underlying network; using it as given")
        return addr
    }
    val literal = first.hostAddress ?: return addr
    Log.i(TAG, "resolved $host to $literal on the underlying network")
    return if (literal.contains(":")) "[$literal]:$port" else "$literal:$port"
}

/** Splits "host:port" or "[v6]:port" into its parts. */
fun splitHostPort(addr: String): Pair<String, String>? {
    if (addr.startsWith("[")) {
        val end = addr.indexOf(']')
        if (end < 0) return null
        val host = addr.substring(1, end)
        val rest = addr.substring(end + 1)
        if (!rest.startsWith(":")) return null
        return host to rest.substring(1)
    }
    val idx = addr.lastIndexOf(':')
    if (idx <= 0 || idx == addr.length - 1) return null
    return addr.substring(0, idx) to addr.substring(idx + 1)
}

/**
 * True when the host part is already an address rather than a name.
 *
 * internal rather than private so the unit tests can reach it: this is the
 * branch that decides whether the platform resolver is consulted at all, and
 * testing only with a literal address hides everything behind it.
 */
internal fun isIpLiteral(host: String): Boolean {
    if (host.contains(":")) return true // any IPv6 literal
    val octets = host.split(".")
    return octets.size == 4 && octets.all { part ->
        part.isNotEmpty() && part.all { it.isDigit() } && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

private const val TAG = "bidichan"
