package torkve.bidichan.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why the previous process ended.
 *
 * The system keeps this even though the process that would have written it is
 * gone, which makes it the one authority on a tunnel that stopped with nothing
 * in the log. It answers the question a user cannot: was the app killed for
 * memory, frozen, stopped by the user, force-stopped by a vendor's battery
 * manager, or did it crash. Guessing between those costs a round-trip to
 * whoever is holding the phone; this does not.
 *
 * Available from API 30. Below that the system simply does not record it.
 */
object ExitReason {

    fun logPrevious(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 3)
        }.getOrNull().orEmpty()
        if (exits.isEmpty()) return
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (e in exits.take(2)) {
            AppLog.log(
                "previous process ended: ${describe(e.reason)}" +
                    " (importance ${importance(e.importance)}, ${e.pss / 1024} MB)" +
                    " at ${when_.format(Date(e.timestamp))}" +
                    (e.description?.let { " — $it" } ?: ""),
            )
        }
    }

    /**
     * Worded for whoever reads the log on the phone. LOW_MEMORY and
     * USER_REQUESTED look the same from the outside — the tunnel stopped — and
     * mean entirely different things about whose fault it is.
     */
    private fun describe(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed to reclaim memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for using too much"
        ApplicationExitInfo.REASON_ANR -> "killed after not responding"
        ApplicationExitInfo.REASON_CRASH -> "crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed in native code"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depended on died"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited on its own"
        ApplicationExitInfo.REASON_FREEZER -> "frozen by the system"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "the app was updated"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "the app's install state changed"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_OTHER -> "killed by the system (other)"
        else -> "unknown ($reason)"
    }

    /** What the system thought the app was worth at the moment it took it. */
    private fun importance(value: Int): String = when {
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
            "foreground service"
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        else -> "importance $value"
    }
}
