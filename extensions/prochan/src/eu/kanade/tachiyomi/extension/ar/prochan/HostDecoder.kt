package eu.kanade.tachiyomi.extension.ar.prochan

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The host app ships one of two native image decoders. Extensions can't link
 * against them, so they are called reflectively. This mirrors the decoder
 * cascade used by Keiyoushi's extensions.
 */
internal object HostDecoder {

    fun decode(bytes: ByteArray): Bitmap? {
        decodeMihon(bytes)?.let { return normalize(it) }
        decodeTachiyomi(bytes)?.let { return normalize(it) }
        return null
    }

    private fun decodeMihon(bytes: ByteArray): Bitmap? {
        return try {
            val cls = findClass("ca.mpreg.imagedecoder.ImageDecoder") ?: return null
            val decoder = cls.getMethod("open", InputStream::class.java)
                .invoke(null, ByteArrayInputStream(bytes)) ?: return null
            try {
                try {
                    val bitmap = cls.getMethod("decodeToBitmap")
                        .invoke(decoder) as? Bitmap
                    if (bitmap != null) return bitmap
                } catch (_: Throwable) {
                }
                decodeFrame(cls, decoder)
            } finally {
                runCatching { (decoder as? AutoCloseable)?.close() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeFrame(decoderClass: Class<*>, decoder: Any): Bitmap? {
        val frame = decoderClass.getMethod("decodeNext").invoke(decoder) ?: return null
        try {
            val frameClass = frame.javaClass
            val width = frameClass.getMethod("getWidth").invoke(frame) as Int
            val height = frameClass.getMethod("getHeight").invoke(frame) as Int
            val image = frameClass.getMethod("getImage").invoke(frame) as? java.nio.ByteBuffer
                ?: return null
            val hdr = decoderClass.getMethod("isHdr").invoke(decoder) as? Boolean ?: false
            val config = if (hdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Bitmap.Config.RGBA_F16
            } else {
                Bitmap.Config.ARGB_8888
            }
            return Bitmap.createBitmap(width, height, config).apply {
                copyPixelsFromBuffer(image)
            }
        } finally {
            runCatching { (frame as? AutoCloseable)?.close() }
        }
    }

    private fun decodeTachiyomi(bytes: ByteArray): Bitmap? {
        return try {
            val cls = findClass("tachiyomi.decoder.ImageDecoder") ?: return null
            val decoder = cls.getMethod("newInstance", InputStream::class.java)
                .invoke(null, ByteArrayInputStream(bytes)) ?: return null
            try {
                val width = cls.getMethod("getWidth").invoke(decoder) as Int
                val height = cls.getMethod("getHeight").invoke(decoder) as Int
                cls.getMethod("decode", Rect::class.java)
                    .invoke(decoder, Rect(0, 0, width, height)) as? Bitmap
            } finally {
                runCatching { cls.getMethod("recycle").invoke(decoder) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findClass(name: String): Class<*>? {
        val loaders = listOfNotNull(
            HostDecoder::class.java.classLoader,
            Thread.currentThread().contextClassLoader,
        )
        for (loader in loaders) {
            try {
                return Class.forName(name, false, loader)
            } catch (_: Throwable) {
            }
        }
        return try {
            Class.forName(name)
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalize(bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.RGBA_F16) {
            val converted = runCatching {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
            if (converted != null) {
                bitmap.recycle()
                return converted
            }
        }
        return bitmap
    }
}
