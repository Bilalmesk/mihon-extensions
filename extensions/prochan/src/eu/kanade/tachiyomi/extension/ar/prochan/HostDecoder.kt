package eu.kanade.tachiyomi.extension.ar.prochan

import android.graphics.Bitmap
import java.io.ByteArrayInputStream

/**
 * Mihon bundles ca.mpreg:imagedecoder, which decodes AVIF/HEIF reliably even on
 * devices whose platform decoder fails. It is not part of the extension API, so
 * it is called reflectively.
 */
internal object HostDecoder {
    fun decode(bytes: ByteArray): Bitmap? {
        return try {
            val decoderClass = Class.forName("ca.mpreg.imagedecoder.ImageDecoder")
            val open = decoderClass.getMethod("open", java.io.InputStream::class.java)
            val decoder = open.invoke(null, ByteArrayInputStream(bytes)) ?: return null
            try {
                val decodeToBitmap = decoderClass.getMethod("decodeToBitmap")
                decodeToBitmap.invoke(decoder) as? Bitmap
            } finally {
                (decoder as? AutoCloseable)?.close()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
