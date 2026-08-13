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
            logTrace(e)
        }
    }

    /**
     * The tombstone, for the exits that come with one.
     *
     * "Crashed in native code" names the room the fire was in and nothing more.
     * For a crash or an ANR the system keeps the trace that says where, and for
     * a Go core that means the panic text and the function it died in — the
     * difference between a week of guessing and an afternoon's fix. Nobody can
     * fetch this off a user's phone by hand, so it goes in the log the user can
     * already send.
     */
    private fun logTrace(e: ApplicationExitInfo) {
        val wanted = e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            e.reason == ApplicationExitInfo.REASON_CRASH ||
            e.reason == ApplicationExitInfo.REASON_ANR
        if (!wanted) return
        val bytes = runCatching {
            e.traceInputStream?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            AppLog.log("  (the system kept no trace for it)")
            return
        }
        val found = interestingStrings(bytes)
        if (found.isEmpty()) {
            AppLog.log("  (a trace was kept but nothing in it was readable)")
            return
        }
        found.take(30).forEach { AppLog.log("  $it") }
    }

    /**
     * The readable parts of a tombstone.
     *
     * A native crash does not hand back text: `traceInputStream` gives the
     * tombstone as a protocol buffer, so reading it as a string produces the
     * register dump as mojibake and buries the one line that matters. Rather
     * than carry a schema for a format we only ever want four things out of,
     * this pulls the printable runs and keeps the ones that identify a fault —
     * for a Go core, the panic text and the frames around it.
     *
     * The signal, the thread it fired on, and the abort message are what
     * distinguish a panic in our own code from one in the platform, so those
     * are what the filter is built around.
     */
    private fun interestingStrings(bytes: ByteArray): List<String> {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                current.append(c.toChar())
            } else {
                if (current.length >= 8) runs.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.length >= 8) runs.add(current.toString())

        val telling = Regex(
            "panic|fatal error|goroutine|runtime\\.|SIGSEGV|SIGABRT|SIGBUS|SIGILL|" +
                "signal |abort|bidichan|/internal/|\\.go:|Exception|pool-\\d+-thread",
            RegexOption.IGNORE_CASE,
        )
        // Paths and mapping noise carry the same words without saying anything.
        val noise = Regex("^/(system|apex|vendor|data/app)/|^\\[|\\.(so|apk|oat|vdex|art)\\b")
        return runs.asSequence()
            .map { it.trim() }
            .filter { it.length in 8..300 }
            .filter { telling.containsMatchIn(it) }
            .filterNot { noise.containsMatchIn(it) }
            .distinct()
            .toList()
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
