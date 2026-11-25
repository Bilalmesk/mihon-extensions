package eu.kanade.tachiyomi.extension.ar.prochan

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.nio.ByteBuffer

/**
 * Kept in its own class so older Android versions never verify ImageDecoder.
 */
internal object Api28Decoder {
    fun decode(bytes: ByteArray): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
}
