package eu.kanade.tachiyomi.extension.ar.prochan

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.nio.ByteBuffer

/**
 * Kept in its own class so older Android versions never verify ImageDecoder.
 */
internal object Api28Decoder {
    fun decode(bytes: ByteArray, software: Boolean): Bitmap {
        // Some Samsung/OEM decoders fail on a heap ByteBuffer source. A file
        // source takes a different path and avoids that bug.
        val bitmap = try {
            val file = File.createTempFile("prochan-avif-", ".avif")
            try {
                file.writeBytes(bytes)
                decodeSource(ImageDecoder.createSource(file), software)
            } finally {
                file.delete()
            }
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
