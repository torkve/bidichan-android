package torkve.bidichan.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a profile link as a scannable code, for handing a profile to a device
 * that is in the room rather than on the other end of a chat.
 *
 * Each platform uses its own encoder — this one here, the system's own on iOS.
 * That is a deliberate departure from the rule that the link *format* lives in
 * the shared core: a QR code is a public standard, so two encoders cannot drift
 * into producing something the other cannot read, which is the only reason the
 * format itself is shared.
 */
object QrCode {

    /**
     * The most a QR code can hold in byte mode: version 40 at the lowest
     * error-correction level. Both clients use that level and check against
     * this same figure, so a profile that shows a code on one shows one on the
     * other.
     */
    const val CAPACITY = 2953

    /** A code for [text], or null if it will not fit or the encoder refuses it. */
    fun encode(text: String, size: Int = 640): ImageBitmap? {
        if (text.toByteArray(Charsets.UTF_8).size > CAPACITY) return null
        val hints = mapOf(
            // "L" spends the fewest modules on recovery, which is what makes
            // room for a profile carrying a certificate. A code shown on a
            // screen and scanned from a few centimetres away is not the case
            // error correction is there for.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            // The quiet zone is part of the standard, and baking it into the
            // image rather than leaving it to a view matters more than it
            // looks: this same bitmap is what gets shared, so it has to be
            // scannable standing alone in someone else's chat app. Four
            // modules is what the standard asks for, and what the iOS client
            // uses, so a code looks the same whichever device made it.
            EncodeHintType.MARGIN to 4,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        }.getOrNull() ?: return null

        // Always dark-on-light, whatever the theme: scanners expect that
        // contrast, and an inverted code is one many will not read.
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }
}
