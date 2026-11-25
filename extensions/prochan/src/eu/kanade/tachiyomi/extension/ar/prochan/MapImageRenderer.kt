package eu.kanade.tachiyomi.extension.ar.prochan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

internal object MapImageRenderer {

    fun render(api: ProChanApi, map: JSONObject): ByteArray {
        val dim = map.optJSONArray("dim") ?: throw Exception("Protected page is missing its size")
        val width = max(1, dim.optInt(0))
        val height = max(1, dim.optInt(1))
        val pieces = map.optJSONArray("pieces") ?: throw Exception("Protected page is missing its pieces")
        if (pieces.length() == 0) throw Exception("Protected page has no pieces")

        val order = map.optJSONArray("order")
        val rects = map.optJSONArray("rects")
            ?: computeRects(width, height, map.optString("mode"), pieces.length())

        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        try {
            for (i in 0 until rects.length()) {
                val rectObj = rects.optJSONObject(i) ?: continue
                val pieceIndex = if (order != null && i < order.length()) order.optInt(i, i) else i
                if (pieceIndex < 0 || pieceIndex >= pieces.length()) continue

                val pieceUrl = pieces.optString(pieceIndex)
                if (pieceUrl.isBlank()) continue

                val piece = decode(api.fetchPiece(pieceUrl))
                    ?: throw Exception("Could not decode the AVIF page image (Android 12+ is required)")
                try {
                    val dest = Rect(
                        rectObj.optInt("left", 0),
                        rectObj.optInt("top", 0),
                        rectObj.optInt("left", 0) + rectObj.optInt("width", width),
                        rectObj.optInt("top", 0) + rectObj.optInt("height", height),
                    )
                    canvas.drawBitmap(piece, null, dest, paint)
                } finally {
                    piece.recycle()
                }
            }

            val output = ByteArrayOutputStream()
            page.compress(Bitmap.CompressFormat.JPEG, 92, output)
            return output.toByteArray()
        } finally {
            page.recycle()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return Api28Decoder.decode(bytes)
            } catch (_: Throwable) {
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun computeRects(width: Int, height: Int, mode: String, count: Int): JSONArray {
        val rects = JSONArray()
        if (mode.startsWith("grid_")) {
            val parts = mode.removePrefix("grid_").split("x")
            val cols = max(1, parts.getOrNull(0)?.toIntOrNull() ?: 1)
            val rows = max(1, parts.getOrNull(1)?.toIntOrNull() ?: 1)
            val cellWidth = width / cols
            val cellHeight = height / rows
            val extraWidth = width - cellWidth * cols
            val extraHeight = height - cellHeight * rows
            val widths = IntArray(cols) { cellWidth + if (it < extraWidth) 1 else 0 }
            val heights = IntArray(rows) { cellHeight + if (it < extraHeight) 1 else 0 }
            val lefts = IntArray(cols)
            val tops = IntArray(rows)
            for (i in 1 until cols) lefts[i] = lefts[i - 1] + widths[i - 1]
            for (i in 1 until rows) tops[i] = tops[i - 1] + heights[i - 1]

            for (index in 0 until count) {
                val col = index % cols
                val row = index / cols
                rects.put(
                    JSONObject()
                        .put("left", lefts[col])
                        .put("top", tops[row])
                        .put("width", widths[col])
                        .put("height", heights[row]),
                )
            }
            return rects
        }

        if (mode.startsWith("vertical")) {
            val columns = max(1, mode.substringAfter('_', "1").toIntOrNull() ?: count)
            val columnWidth = width / columns
            val extra = width - columnWidth * columns
            var left = 0
            for (index in 0 until min(columns, count)) {
                val w = columnWidth + if (index < extra) 1 else 0
                rects.put(
                    JSONObject()
                        .put("left", left)
                        .put("top", 0)
                        .put("width", w)
                        .put("height", height),
                )
                left += w
            }
            return rects
        }

        for (index in 0 until count) {
            rects.put(
                JSONObject()
                    .put("left", 0)
                    .put("top", 0)
                    .put("width", width)
                    .put("height", height),
            )
        }
        return rects
    }
}
