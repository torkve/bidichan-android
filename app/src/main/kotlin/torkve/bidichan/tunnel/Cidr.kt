package torkve.bidichan.tunnel

import java.net.Inet6Address
import java.net.InetAddress

/** Splits "10.42.0.2/24" into its address and prefix length, or null. */
fun cidrParts(cidr: String): Pair<String, Int>? {
    val parts = cidr.split("/")
    if (parts.size != 2) return null
    val prefix = parts[1].toIntOrNull() ?: return null
    val addr = parts[0]
    val max = if (addr.contains(":")) 128 else 32
    if (prefix < 0 || prefix > max) return null
    return addr to prefix
}

/**
 * The network base of an address and prefix, with every bit below the prefix
 * cleared: 10.42.0.2/24 -> 10.42.0.0, fd00:bd::2/64 -> fd00:bd::. Null when the
 * address cannot be parsed or the prefix does not fit it.
 *
 * This has to work for both families, and it is not cosmetic:
 * `VpnService.Builder.addRoute` rejects an address carrying any bit below its
 * prefix — `IllegalArgumentException: Bad address` — so handing it an interface
 * address like fd00:bd::2/64 takes the whole app down. Masking bytes rather
 * than an Int is what makes one implementation cover both.
 */
fun networkBase(address: String, prefix: Int): String? {
    // Numeric input only: it always arrives from cidrParts, so this never
    // performs a lookup. Anything unparseable is a profile we cannot route.
    val bytes = runCatching { InetAddress.getByName(address) }.getOrNull()?.address ?: return null
    if (prefix < 0 || prefix > bytes.size * 8) return null
    for (i in bytes.indices) {
        val keep = (prefix - i * 8).coerceIn(0, 8)
        val mask = if (keep == 0) 0 else (0xFF shl (8 - keep)) and 0xFF
        bytes[i] = (bytes[i].toInt() and mask).toByte()
    }
    return runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull()
}

/**
 * An address in the same IPv4 subnet as the device's, but a different host, for
 * the peer's end of the packet channel. Both ends sharing one address would
 * deliver return traffic locally instead of across the tunnel. Picks the first
 * host, or the second when the device already holds the first.
 */
fun gatewayCidr(deviceCidr: String): String {
    val (addr, prefix) = cidrParts(deviceCidr) ?: return deviceCidr
    val octets = addr.split(".").mapNotNull { it.toIntOrNull() }
    if (octets.size != 4) return deviceCidr
    val bits = octets.fold(0L) { acc, o -> (acc shl 8) or (o.toLong() and 0xff) }
    val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val network = bits and mask
    var gw = network or 1L
    if (gw == bits) gw = network or 2L
    return "${(gw shr 24) and 0xff}.${(gw shr 16) and 0xff}.${(gw shr 8) and 0xff}.${gw and 0xff}/$prefix"
}

/** The IPv6 analogue: same subnet, low byte 1 (or 2 if the device holds 1). */
fun gatewayCidr6(deviceCidr: String): String {
    val (addr, prefix) = cidrParts(deviceCidr) ?: return deviceCidr
    val parsed = runCatching { InetAddress.getByName(addr) }.getOrNull() as? Inet6Address
        ?: return deviceCidr
    val bytes = parsed.address
    bytes[15] = if (bytes[15].toInt() == 1) 2 else 1
    val out = runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull()
        ?: return deviceCidr
    return "$out/$prefix"
}
