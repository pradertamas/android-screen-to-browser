package com.pradertamas.uicapturetest

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

fun pairingQr(code: String): Bitmap {
    // Created once per pairing code, never on the video/capture path. Four-module quiet zone.
    val matrix = QRCodeWriter().encode(PairingCode.receiverUrl(code), BarcodeFormat.QR_CODE,
        600, 600, mapOf(EncodeHintType.MARGIN to 4))
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}
