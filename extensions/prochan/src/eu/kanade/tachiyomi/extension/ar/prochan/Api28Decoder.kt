package eu.kanade.tachiyomi.extension.ar.prochan

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

/**
 * Kept in its own class so older Android versions never verify ImageDecoder.
 */
internal object Api28Decoder {
    fun decode(bytes: ByteArray, software: Boolean): Bitmap {
        // Some Samsung/OEM decoders fail on a heap ByteBuffer source. An
        // InputStream source takes the normal stream path instead.
        val bitmap = try {
            decodeSource(ImageDecoder.createSource(ByteArrayInputStream(bytes)), software)
        } catch (_: Throwable) {
            val direct = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                rewind()
            }
            decodeSource(ImageDecoder.createSource(direct), software)
        }

        if (bitmap.config == Bitmap.Config.HARDWARE) {
            val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            if (copy != null) {
                bitmap.recycle()
                return copy
            }
        }
        return bitmap
    }

    private fun decodeSource(source: ImageDecoder.Source, software: Boolean): Bitmap {
        return if (software) {
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            ImageDecoder.decodeBitmap(source)
        }
    }
}
