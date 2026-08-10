package torkve.bidichan.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileOutputStream

/**
 * Serves the scannable code to whichever app the user shares it with, straight
 * out of memory.
 *
 * An image can only cross an app boundary as a `content://` URI — a data: URI
 * of the kind HTML uses is not something `ContentResolver` can open, so no
 * receiver could read one. But a content URI does not have to be backed by a
 * file: this hands the bytes over a pipe when the receiver actually reads,
 * which matters because the code encodes the link, and a link may carry the
 * pre-shared key. Nothing about sharing a profile should leave a credential on
 * disk, however briefly.
 *
 * The bytes live only in this process and go when it does. `exported="false"`
 * with `grantUriPermissions` means the only way to reach them is the temporary
 * grant that rides on the share intent.
 */
class SharedCodeProvider : ContentProvider() {

    companion object {
        private const val PATH = "code.png"
        private const val MIME = "image/png"

        /** Named for what it is, not for the profile: this shows up in the
         *  other app's UI, and the profile's name is nobody else's business. */
        private const val DISPLAY_NAME = "profile-code.png"

        /**
         * The code currently on offer, if any.
         *
         * One at a time, replaced by each share: there is never a reason to
         * have two in flight, and holding the last one only for as long as it
         * takes something to read it is the point. Volatile because the pipe is
         * written from a background thread.
         */
        @Volatile
        private var payload: ByteArray? = null

        /** Publishes `png` and returns the URI to hand out. */
        fun offer(context: Context, png: ByteArray): Uri {
            // Copied, because the array is read later from another thread and
            // must not change under it.
            payload = png.copyOf()
            return Uri.parse("content://${context.packageName}.codes/$PATH")
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME

    /**
     * Answers what an app asks before it reads. Gmail and Telegram both query
     * [OpenableColumns] first and misbehave when the answer is missing, so this
     * is not optional politeness.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val data = payload ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(DISPLAY_NAME)
                OpenableColumns.SIZE -> row.add(data.size.toLong())
                // A column we do not know: answer null rather than leaving the
                // row short, which would not line up with the projection.
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val data = payload ?: return null
        return openPipeHelper(uri, MIME, null, data) { output, _, _, _, bytes ->
            // This runs on a thread the framework owns, so nothing may escape
            // it: a receiver that closes early gives a broken pipe, and an
            // exception here would take the app down rather than the share.
            runCatching {
                FileOutputStream(output.fileDescriptor).use { it.write(bytes) }
            }
        }
    }

    // Read-only: the only thing this serves is what offer() published.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
