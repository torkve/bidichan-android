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

/** The network base of an IPv4 address and prefix: 10.42.0.2/24 -> 10.42.0.0. */
fun networkBase(address: String, prefix: Int): String {
    val octets = address.split(".").mapNotNull { it.toIntOrNull() }
    if (octets.size != 4) return address
    val bits = octets.fold(0L) { acc, o -> (acc shl 8) or (o.toLong() and 0xff) }
    val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val base = bits and mask
    return "${(base shr 24) and 0xff}.${(base shr 16) and 0xff}.${(base shr 8) and 0xff}.${base and 0xff}"
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
