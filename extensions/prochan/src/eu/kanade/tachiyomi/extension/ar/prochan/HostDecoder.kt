package eu.kanade.tachiyomi.extension.ar.prochan

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The host app ships one of two native image decoders. Extensions can't link
 * against them, so they are called reflectively. Different Mihon/Tachiyomi
 * versions expose different overloads, so probe all known signatures.
 */
internal object HostDecoder {

    @Volatile
    var lastError: String = ""
        private set

    fun decode(bytes: ByteArray): Bitmap? {
        val errors = mutableListOf<String>()
        decodeMihon(bytes, errors)?.let { return normalize(it) }
        decodeTachiyomi(bytes, errors)?.let { return normalize(it) }
        lastError = errors.joinToString("; ")
        return null
    }

    private fun decodeMihon(bytes: ByteArray, errors: MutableList<String>): Bitmap? {
        val cls = findClass("ca.mpreg.imagedecoder.ImageDecoder")
        if (cls == null) {
            errors += "ca.mpreg: class missing"
            return null
        }

        var decoder: Any? = null
        for (signature in listOf(
            arrayOf<Class<*>>(InputStream::class.java),
            arrayOf<Class<*>>(InputStream::class.java, java.lang.Boolean.TYPE),
        )) {
            try {
                val args = if (signature.size == 1) {
                    arrayOf<Any?>(ByteArrayInputStream(bytes))
                } else {
                    arrayOf<Any?>(ByteArrayInputStream(bytes), true)
                }
                decoder = cls.getMethod("open", *signature).invoke(null, *args)
                if (decoder != null) break
            } catch (e: Throwable) {
                errors += "ca.mpreg.open: ${e.javaClass.simpleName}"
            }
        }
        if (decoder == null) return null

        try {
            try {
                val bitmap = cls.getMethod("decodeToBitmap")
                    .invoke(decoder) as? Bitmap
                if (bitmap != null) return bitmap
            } catch (e: Throwable) {
                errors += "ca.mpreg.decodeToBitmap: ${e.javaClass.simpleName}"
            }
            return decodeFrame(cls, decoder, errors)
        } finally {
            runCatching { (decoder as? AutoCloseable)?.close() }
        }
    }

    private fun decodeFrame(decoderClass: Class<*>, decoder: Any, errors: MutableList<String>): Bitmap? {
        val frame = try {
            decoderClass.getMethod("decodeNext").invoke(decoder)
        } catch (e: Throwable) {
            errors += "ca.mpreg.decodeNext: ${e.javaClass.simpleName}"
            null
        } ?: return null

        try {
            val frameClass = frame.javaClass
            val width = frameClass.getMethod("getWidth").invoke(frame) as Int
            val height = frameClass.getMethod("getHeight").invoke(frame) as Int
            val image = frameClass.getMethod("getImage").invoke(frame) as? java.nio.ByteBuffer
                ?: return null
            val hdr = runCatching {
                decoderClass.getMethod("isHdr").invoke(decoder) as? Boolean
            }.getOrNull() ?: false
            val config = if (hdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Bitmap.Config.RGBA_F16
            } else {
                Bitmap.Config.ARGB_8888
            }
            return Bitmap.createBitmap(width, height, config).apply {
                copyPixelsFromBuffer(image)
            }
        } catch (e: Throwable) {
            errors += "ca.mpreg.frame: ${e.javaClass.simpleName}"
            return null
        } finally {
            runCatching { (frame as? AutoCloseable)?.close() }
        }
    }

    private fun decodeTachiyomi(bytes: ByteArray, errors: MutableList<String>): Bitmap? {
        val cls = findClass("tachiyomi.decoder.ImageDecoder")
        if (cls == null) {
            errors += "tachiyomi: class missing"
            return null
        }

        val targets = mutableListOf<Pair<Any?, Class<*>>>()

        // Mihon's decoder library exposes newInstance() on the Kotlin companion
        // object. The field is `Companion`; some forks expose it as `INSTANCE`.
        // The outer class only has synthetic accessors, so probing it alone
        // always fails with "no matching signature".
        var companion: Any? = null
        var lastFailure = ""
        for (fieldName in listOf("Companion", "INSTANCE")) {
            val result = runCatching { cls.getField(fieldName).get(null) }
            companion = result.getOrNull()
            if (companion != null) break
            lastFailure = result.exceptionOrNull()?.let {
                ((it.cause ?: it).message ?: it.javaClass.simpleName)
            }.orEmpty()
        }
        val companionClass = companion?.javaClass
            ?: findClass("tachiyomi.decoder.ImageDecoder\$Companion")
        if (companion == null && companionClass != null) {
            companion = runCatching {
                companionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.getOrNull()
        }
        if (companion != null && companionClass != null) {
            targets += companion to companionClass
        }

        // Kept for forks that expose newInstance() as a static method.
        targets += null to cls

        var decoder: Any? = null
        for ((receiver, target) in targets) {
            for (signature in listOf(
                arrayOf<Class<*>>(InputStream::class.java, java.lang.Boolean.TYPE, ByteArray::class.java),
                arrayOf<Class<*>>(InputStream::class.java, java.lang.Boolean.TYPE),
                arrayOf<Class<*>>(InputStream::class.java),
            )) {
                try {
                    val args = when (signature.size) {
                        3 -> arrayOf<Any?>(ByteArrayInputStream(bytes), false, null)
                        2 -> arrayOf<Any?>(ByteArrayInputStream(bytes), false)
                        else -> arrayOf<Any?>(ByteArrayInputStream(bytes))
                    }
                    decoder = target.getMethod("newInstance", *signature).invoke(receiver, *args)
                    if (decoder != null) break
                } catch (e: Throwable) {
                    lastFailure = (e.cause ?: e).message ?: e.javaClass.simpleName
                    // Try the next signature.
                }
            }
            if (decoder != null) break
        }

        if (decoder == null) {
            errors += "tachiyomi.newInstance: ${lastFailure.take(160).ifBlank { "no matching signature" }}"
            return null
        }

        try {
            val width = runCatching { cls.getMethod("getWidth").invoke(decoder) as Int }.getOrNull()
            val height = runCatching { cls.getMethod("getHeight").invoke(decoder) as Int }.getOrNull()
            for (signature in listOf(
                arrayOf<Class<*>>(Rect::class.java, Integer.TYPE),
                arrayOf<Class<*>>(Rect::class.java),
            )) {
                try {
                    val args = if (signature.size == 2) {
                        arrayOf<Any?>(Rect(0, 0, width ?: 0, height ?: 0), 1)
                    } else {
                        arrayOf<Any?>(Rect(0, 0, width ?: 0, height ?: 0))
                    }
                    val bitmap = cls.getMethod("decode", *signature).invoke(decoder, *args) as? Bitmap
                    if (bitmap != null) return bitmap
                } catch (e: Throwable) {
                    val cause = (e.cause ?: e).message.orEmpty().take(80)
                    errors += "tachiyomi.decode: ${e.javaClass.simpleName}: $cause"
                }
            }
            return null
        } finally {
            runCatching { cls.getMethod("recycle").invoke(decoder) }
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
        val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        val hdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.RGBA_F16
        if (hardware || hdr) {
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
