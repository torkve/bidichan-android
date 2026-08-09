package torkve.bidichan

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import torkve.bidichan.tunnel.TunnelService
import torkve.bidichan.ui.AppScreens

class MainActivity : ComponentActivity() {

    /** The profile waiting on the system's consent prompt, if any. */
    private var pendingProfileId: String? = null

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pendingProfileId
        pendingProfileId = null
        if (result.resultCode == Activity.RESULT_OK && id != null) {
            TunnelService.start(this, id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val model: AppModel = viewModel()
                    AppScreens(
                        model = model,
                        onConnect = { profileId -> requestConsentThenStart(profileId) },
                        onDisconnect = { TunnelService.stop(this) },
                    )
                }
            }
        }
    }

    /**
     * The system asks the user to allow this app to handle the device's packets
     * the first time, and returns null once that consent is on record.
     */
    private fun requestConsentThenStart(profileId: String) {
        val intent: Intent? = VpnService.prepare(this)
        if (intent == null) {
            TunnelService.start(this, profileId)
            return
        }
        pendingProfileId = profileId
        consent.launch(intent)
    }
}
