package com.localcast.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Black-on-white QR with a [margin]-module quiet zone. */
fun encodeQr(text: String, sizePx: Int = 600, margin: Int = 4): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to margin,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            pixels[row + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    val bmp = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}

/** Encodes off the main thread; recomputed only when [text] changes. */
@Composable
fun rememberQrBitmap(text: String, margin: Int = 4): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, key1 = text, key2 = margin) {
        value = withContext(Dispatchers.Default) { runCatching { encodeQr(text, margin = margin) }.getOrNull() }
    }
