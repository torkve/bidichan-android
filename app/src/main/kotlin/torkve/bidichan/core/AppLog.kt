package torkve.bidichan.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A bounded log the app can show, so a connection problem can be diagnosed on
 * the device rather than over adb. Everything written here also goes to the
 * system log.
 *
 * It is kept on disk as well as in memory, because the most important thing it
 * has to survive is the app not surviving. A process the system kills writes no
 * parting line: an in-memory log simply vanishes with it, and the report that
 * comes back is "it disconnected and there was nothing in the log" — which
 * reads like nothing happened, when in fact the strongest possible thing did.
 * Across a restart the file shows the connection running, then stopping mid
 * sentence, then [PROCESS_START] with no shutdown between the two. That gap is
 * the evidence.
 *
 * The service and the UI live in the same process, so one buffer serves both —
 * unlike iOS, where the two are separate processes and the log has to travel
 * through shared storage.
 */
object AppLog {
    private const val TAG = "bidichan"
    private const val MAX_LINES = 2000

    /** Kept small: this is a tail for diagnosis, not an archive. */
    private const val MAX_FILE_BYTES = 256L * 1024

    const val PROCESS_START = "—— app process started ——"

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    var generation: Int = 0
        private set

    /**
     * Points the log at storage and notes that a process began. Called from
     * Application.onCreate, so it runs before anything that might log — and so
     * the marker lands even when the process was started by the system
     * restarting the service rather than by the user opening the app.
     */
    fun attach(context: Context) {
        val f = File(context.filesDir, "app.log")
        synchronized(lines) {
            file = f
            runCatching { if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach(lines::addLast) }
            generation++
        }
        log(PROCESS_START)
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val line = "${stamp.format(Date())}  $message"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            generation++
            file?.let { f ->
                runCatching {
                    // Trimmed before the write rather than after, so the file
                    // never exceeds the cap even if the process dies mid-run.
                    if (f.length() > MAX_FILE_BYTES) {
                        val kept = f.readLines().takeLast(MAX_LINES / 2)
                        f.writeText(kept.joinToString("\n", postfix = "\n"))
                    }
                    f.appendText(line + "\n")
                }
            }
        }
    }

    fun read(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            runCatching { file?.writeText("") }
            generation++
        }
    }
}
