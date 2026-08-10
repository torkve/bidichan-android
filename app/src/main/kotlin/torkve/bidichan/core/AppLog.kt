package torkve.bidichan.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A bounded in-memory log the app can show, so a connection problem can be
 * diagnosed on the device rather than over adb. Everything written here also
 * goes to the system log.
 *
 * The service and the UI live in the same process, so one buffer serves both —
 * unlike iOS, where the two are separate processes and the log has to travel
 * through shared storage.
 */
object AppLog {
    private const val TAG = "bidichan"
    private const val MAX_LINES = 2000

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var generation: Int = 0
        private set

    fun log(message: String) {
        Log.i(TAG, message)
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast("${stamp.format(Date())}  $message")
            generation++
        }
    }

    fun read(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            generation++
        }
    }
}
