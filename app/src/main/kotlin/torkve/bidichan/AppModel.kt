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
import torkve.bidichan.core.Profile
import torkve.bidichan.core.ProfileStore
import torkve.bidichan.core.Secrets
import torkve.bidichan.tunnel.TunnelService

/**
 * App-wide state: owns the profile store and mirrors what the tunnel service is
 * doing. The service is the authority on the connection; this only reflects it.
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

    /** The profile the service was last asked to run. */
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
                delay(1000)
            }
        }
    }

    /** True while the tunnel is usable — connected, or briefly reconnecting. */
    val isLive: Boolean get() = _status.value == "Connected" || _status.value == "Reconnecting…"

    fun reload() = withStore { _profiles.value = store.profiles }

    fun upsert(profile: Profile) = withStore {
        store.upsert(profile)
        _profiles.value = store.profiles
    }

    fun delete(profile: Profile) = withStore {
        store.delete(profile, secrets)
        _profiles.value = store.profiles
    }

    fun psk(profile: Profile): String = secrets[profile.pskAccount].orEmpty()

    fun setPsk(profile: Profile, hex: String) {
        secrets[profile.pskAccount] = hex.trim()
    }

    fun connected(profileId: String) {
        _activeProfileId.value = profileId
    }

    fun disconnected() {
        _activeProfileId.value = null
    }

    fun clearError() {
        _error.value = null
    }

    private fun withStore(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(Dispatchers.IO) { block() } }
    }
}
