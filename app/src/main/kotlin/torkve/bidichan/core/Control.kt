package torkve.bidichan.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Builders for the control requests the embedded core understands. This is the
 * same JSON the command-line client speaks over its control socket —
 * `{"action":"...","args":{...}}` — so the two stay in step by construction.
 */
object Control {
    // encodeDefaults, or a field the caller never sets is simply not sent: the
    // core read the missing "tun_side" as "" and refused to open the packet
    // channel, leaving a tunnel that called itself connected and carried
    // nothing. explicitNulls stays off so the genuinely optional fields —
    // label, name, cidr6 — are still omitted rather than sent as null.
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun request(action: String, args: JsonElement? = null): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("action", action)
                if (args != null) put("args", args)
            },
        )

    fun status(): String = request("status")

    @Serializable
    data class ForwardArgs(
        @SerialName("listen_side") val listenSide: String,
        @SerialName("listen_addr") val listenAddr: String,
        @SerialName("target_addr") val targetAddr: String,
        val label: String? = null,
    )

    fun openForward(a: ForwardArgs): String =
        request("open_forward", json.encodeToJsonElement(a))

    @Serializable
    data class ProxyArgs(
        @SerialName("listen_side") val listenSide: String,
        @SerialName("listen_addr") val listenAddr: String,
        val label: String? = null,
    )

    fun openHttp(a: ProxyArgs): String = request("open_http", json.encodeToJsonElement(a))

    fun openSocks5(a: ProxyArgs): String = request("open_socks5", json.encodeToJsonElement(a))

    @Serializable
    data class TunArgs(
        @SerialName("tun_side") val tunSide: String = "local",
        val cidr: String,
        val cidr6: String? = null,
        val mtu: Int,
        val name: String? = null,
        val label: String? = null,
    )

    fun openTun(a: TunArgs): String = request("open_tun", json.encodeToJsonElement(a))

    @Serializable
    private data class CloseArgs(@SerialName("channel_id") val channelId: Long)

    fun close(channelId: Long): String =
        request("close_channel", json.encodeToJsonElement(CloseArgs(channelId)))
}

/** Decoders for the control responses (`{"data":...}` or `{"error":...}`). */
object ControlDecode {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    class RemoteError(message: String) : Exception(message)

    @Serializable
    private data class Envelope<T>(val error: String? = null, val data: T? = null)

    @Serializable
    private data class OpenData(@SerialName("channel_id") val channelId: Long = 0)

    /** Returns the new channel's id, or throws what the core reported. */
    fun open(response: String): Long {
        val env = json.decodeFromString(Envelope.serializer(OpenData.serializer()), response)
        env.error?.let { throw RemoteError(it) }
        return env.data?.channelId ?: 0
    }

    fun status(response: String): StatusResponse {
        val env = json.decodeFromString(Envelope.serializer(StatusResponse.serializer()), response)
        env.error?.let { throw RemoteError(it) }
        return env.data ?: StatusResponse()
    }

    /** Throws if the response carried an error; ignores the payload otherwise. */
    fun ok(response: String) {
        val env = json.decodeFromString(Envelope.serializer(JsonElement.serializer()), response)
        env.error?.let { throw RemoteError(it) }
    }
}

@Serializable
data class ChannelSnapshot(
    val id: Long = 0,
    val kind: String = "",
    val originator: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    val description: String = "",
    val label: String? = null,
)

@Serializable
data class PeerStatus(
    val id: String = "",
    val remote: String = "",
    val local: String = "",
    @SerialName("started_at") val startedAt: String = "",
    val mode: String = "",
    val channels: List<ChannelSnapshot>? = null,
)

@Serializable
data class StatusResponse(val peers: List<PeerStatus>? = null)
