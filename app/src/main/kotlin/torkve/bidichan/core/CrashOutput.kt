package torkve.bidichan.core

import android.content.Context
import torkve.bidichan.go.mobile.Mobile
import java.io.File

/**
 * What the core printed as it died.
 *
 * A crash inside the Go core leaves nothing behind on Android: the runtime does
 * not set the abort message, so the system's tombstone names the signal and the
 * thread and no more, and the text — the panic, and the goroutine stacks under
 * it — goes to standard error, which an ordinary app cannot read back out of
 * the system log. The core therefore writes it where we can: a file in the
 * app's own storage, read and emptied at the next start.
 *
 * Emptied rather than kept, so the same crash is not reported for the rest of
 * the app's life; the copy that matters is already in the log by then.
 */
object CrashOutput {

    private const val FILE = "core-crash.log"

    /**
     * Reports anything the last run left behind, then takes the redirection for
     * this one. In that order: the file has to be read before the core starts
     * appending to it again.
     */
    fun collectAndArm(context: Context) {
        val file = File(context.filesDir, FILE)
        report(file)
        runCatching { Mobile.redirectCrashOutput(file.absolutePath) }
            .onFailure { AppLog.log("could not capture the core's crash output: ${it.message}") }
    }

    private fun report(file: File) {
        val text = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        if (text.isBlank()) return
        AppLog.log("the core left this behind when it died:")
        // Bounded: a traceback with every goroutine runs long, and the first
        // lines carry the reason. What is dropped is said rather than silently
        // lost, so nobody wonders whether they are reading all of it.
        val lines = text.lines().filter { it.isNotBlank() }
        lines.take(60).forEach { AppLog.log("  $it") }
        if (lines.size > 60) AppLog.log("  (… ${lines.size - 60} more lines)")
        runCatching { file.writeText("") }
    }
}
