package torkve.bidichan.tunnel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether the system will let the tunnel keep running once the screen is off.
 *
 * Doze is documented to ignore wake locks and to suspend network access, so the
 * lock the service holds does not survive it on its own: a quarter of an hour
 * after the screen goes off the keepalive stops, the peer drops a session it
 * has heard nothing from, and the tunnel is gone. An app the user has excused
 * from battery optimisation is exempt from both, which is the only way to hold
 * a long-lived connection open across sleep.
 *
 * This is a request, never a requirement. The tunnel works without it while the
 * screen is on, and the decision belongs to whoever owns the battery.
 */
object BatteryExemption {

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * The system dialog that asks. Only the platform may grant this, and only
     * on the user's say-so — there is no way to set it silently, by design.
     */
    fun requestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )

    /** Settings, for the case where the dialog is unavailable or was refused. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
