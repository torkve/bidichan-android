package torkve.bidichan.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import torkve.bidichan.go.mobile.Mobile
import java.util.UUID

/**
 * Turning a profile into a shareable link, and back.
 *
 * The format itself lives in the Go core, which both this client and the iOS
 * one embed — a link written by one has to be readable by the other, and two
 * hand-written implementations would drift the moment either gained a field.
 * This only maps between [Profile] and that shared representation.
 */
object ProfileLinking {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What the app registers with the system, and matches before importing. */
    val prefix: String get() = Mobile.profileLinkPrefix()

    fun isProfileLink(raw: String?): Boolean =
        raw != null && raw.lowercase().startsWith(prefix.lowercase())

    /** A profile decoded from a link, held until the user accepts it. */
    data class Incoming(val profile: Profile, val psk: String?) {
        val carriesKey: Boolean get() = !psk.isNullOrEmpty()
    }

    /**
     * Renders a link for [profile]. [includeKey] decides whether the pre-shared
     * key travels with it: a link carrying the key is a credential, and nothing
     * in the encoding protects it.
     */
    fun link(profile: Profile, includeKey: Boolean, psk: String?): String {
        val link = Mobile.newProfileLink()
        link.name = profile.name
        link.addr = profile.serverAddress
        link.hostname = profile.hostname
        link.path = profile.path
        link.noTlsBinding = profile.noTlsBinding
        link.fingerprint = profile.fingerprint
        link.caCertPem = profile.caCertPem
        link.enableTun = profile.enableTun
        link.tunCidr = profile.tunCidr
        link.tunCidr6 = profile.tunCidr6
        link.tunMtu = profile.tunMtu.toLong()
        link.fullTunnel = profile.fullTunnel
        link.memoryLimitMb = profile.memoryLimitMb.toLong()
        link.resumeGraceSeconds = profile.resumeGraceSeconds.toLong()
        link.channelsJson = encodeChannels(profile.channels)
        if (includeKey && !psk.isNullOrEmpty()) link.pskHex = psk
        return link.encode()
    }

    /**
     * Decodes a link. The core throws with a message written to be shown as-is.
     */
    fun decode(raw: String): Incoming {
        val link = Mobile.parseProfileLink(raw)
        // A fresh identifier: the link carries none by design, so importing the
        // same one twice adds a profile rather than overwriting one.
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = link.name.ifEmpty { "Imported profile" },
            serverAddress = link.addr,
            hostname = link.hostname,
            path = link.path,
            noTlsBinding = link.noTlsBinding,
            fingerprint = link.fingerprint.ifEmpty { "android" },
            caCertPem = link.caCertPem,
            enableTun = link.enableTun,
            tunCidr = link.tunCidr.ifEmpty { "10.42.0.2/24" },
            tunCidr6 = link.tunCidr6,
            tunMtu = link.tunMtu.toInt().takeIf { it > 0 } ?: 1400,
            fullTunnel = link.fullTunnel,
            memoryLimitMb = link.memoryLimitMb.toInt().takeIf { it > 0 } ?: 40,
            resumeGraceSeconds = link.resumeGraceSeconds.toInt().takeIf { it > 0 } ?: 90,
            channels = decodeChannels(link.channelsJson),
        )
        return Incoming(profile, link.pskHex.ifEmpty { null })
    }

    /**
     * The channel as it travels. The core validates these same field names, so
     * this has to match what it expects — and what the iOS client sends.
     */
    @Serializable
    private data class WireChannel(
        val label: String = "",
        val kind: String,
        val allInterfaces: Boolean = false,
        val port: Int,
        val target: String = "",
        val routeSystem: Boolean = false,
    )

    /** Identifiers are left out: they are local to a device. */
    private fun encodeChannels(channels: List<ChannelConfig>): String {
        if (channels.isEmpty()) return ""
        val wire = channels.map {
            WireChannel(
                label = it.label,
                kind = it.kind.wireName,
                allInterfaces = it.allInterfaces,
                port = it.port,
                target = it.target,
                routeSystem = it.routeSystem,
            )
        }
        return json.encodeToString(wire)
    }

    private fun decodeChannels(raw: String): List<ChannelConfig> {
        if (raw.isEmpty()) return emptyList()
        val wire = runCatching { json.decodeFromString<List<WireChannel>>(raw) }.getOrNull()
            ?: return emptyList()
        return wire.map { w ->
            ChannelConfig(
                label = w.label,
                kind = ChannelConfig.Kind.fromWire(w.kind),
                allInterfaces = w.allInterfaces,
                port = w.port,
                target = w.target,
                routeSystem = w.routeSystem,
            )
        }
    }
}
