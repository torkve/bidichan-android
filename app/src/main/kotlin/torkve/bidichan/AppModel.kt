package torkve.bidichan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import torkve.bidichan.core.AppLog
import torkve.bidichan.core.ChannelConfig
import torkve.bidichan.core.ChannelSnapshot
import torkve.bidichan.core.Control
import torkve.bidichan.core.ControlDecode
import torkve.bidichan.core.Profile
import torkve.bidichan.core.ProfileLinking
import torkve.bidichan.core.ProfileStore
import torkve.bidichan.core.Secrets
import torkve.bidichan.tunnel.GoShell
import torkve.bidichan.tunnel.TunnelService

/**
 * App-wide state: owns the profile store and mirrors what the tunnel service is
 * doing. The service is the authority on the connection; this only reflects it,
 * and reaches it directly because both live in the same process.
 */
class AppModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    val secrets = Secrets(app)

    private val _profiles = MutableStateFlow(store.profiles)
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _channels = MutableStateFlow<List<ChannelSnapshot>>(emptyList())
    val channels: StateFlow<List<ChannelSnapshot>> = _channels.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _status.value = TunnelService.state
                TunnelService.lastError?.let {
                    _error.value = it
                    TunnelService.lastError = null
                }
                if (isLive) refreshChannels() else _channels.value = emptyList()
                delay(2000)
            }
        }
    }

    /** True while the tunnel is usable — connected, or briefly reconnecting. */
    val isLive: Boolean
        get() = TunnelService.isRunning &&
            (_status.value == "Connected" || _status.value == "Reconnecting…")

    // MARK: - Profiles

    fun upsert(profile: Profile) = onIo {
        store.upsert(profile)
        _profiles.value = store.profiles
    }

    fun delete(profile: Profile) = onIo {
        store.delete(profile, secrets)
        _profiles.value = store.profiles
    }

    fun psk(profile: Profile): String = secrets[profile.pskAccount].orEmpty()

    /** Saves a profile that arrived as a link, along with its key if it had one. */
    fun importProfile(incoming: ProfileLinking.Incoming) = onIo {
        store.upsert(incoming.profile)
        incoming.psk?.takeIf { it.isNotEmpty() }?.let {
            secrets[incoming.profile.pskAccount] = it
        }
        _profiles.value = store.profiles
    }

    fun setPsk(profile: Profile, hex: String) {
        secrets[profile.pskAccount] = hex.trim()
    }

    fun connecting(profileId: String) {
        _activeProfileId.value = profileId
    }

    fun clearError() {
        _error.value = null
    }

    // MARK: - Channels

    private suspend fun refreshChannels() {
        val snapshot = withContext(Dispatchers.IO) {
            runCatching {
                ControlDecode.status(TunnelService.control(Control.status()))
                    .peers.orEmpty()
                    .flatMap { it.channels.orEmpty() }
            }.getOrNull()
        }
        // A transient failure while the tunnel is reconnecting is not worth
        // blanking the list the user is looking at.
        if (snapshot != null) _channels.value = snapshot
    }

    /** Opens the channel a [ChannelConfig] describes, mirroring the profile defaults. */
    fun openChannel(config: ChannelConfig) = onIo {
        val label = config.label.ifEmpty { null }
        val request = if (config.kind.isProxy) {
            val args = Control.ProxyArgs(
                listenSide = "local",
                listenAddr = config.listenAddr,
                label = label,
            )
            if (config.kind == ChannelConfig.Kind.HTTP) Control.openHttp(args)
            else Control.openSocks5(args)
        } else {
            Control.openForward(
                Control.ForwardArgs(
                    listenSide = config.kind.side,
                    listenAddr = config.listenAddr,
                    targetAddr = config.target,
                    label = label,
                )
            )
        }
        control(request)
    }

    fun closeChannel(id: Long) = onIo { control(Control.close(id)) }

    private suspend fun control(request: String) {
        runCatching { ControlDecode.ok(TunnelService.control(request)) }
            .onFailure { _error.value = it.message ?: "the request failed" }
        refreshChannels()
    }

    // MARK: - Shell

    private val _shellOutput = MutableStateFlow("")
    val shellOutput: StateFlow<String> = _shellOutput.asStateFlow()

    private var shell: GoShell? = null

    fun openShell() = onIo {
        closeShell()
        _shellOutput.value = ""
        val s = runCatching { TunnelService.openShell("xterm-256color", 24, 80) }
            .getOrElse {
                _error.value = it.message ?: "could not open a shell"
                return@onIo
            }
        shell = s
        // read() blocks until output arrives and throws when the shell ends.
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val chunk = runCatching { s.read() }.getOrNull() ?: break
                _shellOutput.value += String(chunk)
            }
            _shellOutput.value += "\n[session ended]\n"
        }
    }

    fun sendShell(text: String) = onIo {
        runCatching { shell?.write((text + "\n").toByteArray()) }
            .onFailure { _error.value = it.message }
    }

    fun closeShell() {
        shell?.close()
        shell = null
    }

    // MARK: - Logs

    fun logText(): String = AppLog.read()

    fun clearLog() = AppLog.clear()

    override fun onCleared() {
        closeShell()
        super.onCleared()
    }

    private fun onIo(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(Dispatchers.IO) { block() } }
    }
}
