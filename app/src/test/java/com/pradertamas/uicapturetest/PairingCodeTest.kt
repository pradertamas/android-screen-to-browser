package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

class PairingCodeTest {
    @Test fun qrParsingAndJoinIntent() {
        assertEquals("7K4M2Q", PairingCode.parseQr(" 7k4m2q "))
        assertEquals("7K4M2Q", PairingCode.parseQr(PairingCode.receiverUrl("7K4M2Q")))
        for (bad in listOf("https://evil.example/?session=7K4M2Q", "http://pradertamas.github.io/realtime-web-test/webrtc-receiver.html?session=7K4M2Q", PairingCode.receiverUrl("7K4M2Q") + "&session=AAAAAA", "not a code", "https://pradertamas.github.io/other?session=7K4M2Q")) assertNull(PairingCode.parseQr(bad))
        val join = PairingRegistration("7K4M2Q", PairingIntent.JOIN)
        assertNull(join.retry(409)); assertEquals("7K4M2Q", join.code)
        assertEquals("join", join.intent.wire)
        assertEquals("create", PairingRegistration("7K4M2Q").intent.wire)
    }
    @Test fun normalizationAndValidation() {
        assertEquals("7K4M2Q", PairingCode.normalize(" 7k4m2q \n"))
        for (bad in listOf("", "7K4M2", "7K4M2Q2", "7K4M2O", "7K4M2I", "7K4M20", "7K4M21", "7K 4MQ", "７K4M2Q"))
            assertNull(bad, PairingCode.normalize(bad))
        repeat(1000) { assertNotNull(PairingCode.normalize(PairingCode.generate())) }
        assertEquals(32, PairingCode.ALPHABET.length)
    }
    @Test fun boundedCollisionRetriesAndNoRetryForOtherErrors() {
        val codes = listOf("7K4M2Q", "AAAAAA", "BBBBBB", "CCCCCC").iterator()
        val registration = PairingRegistration("7K4M2Q") { codes.next() }
        assertNull(registration.retry(500))
        assertEquals("AAAAAA", registration.retry(409)) // Repeated initial code is skipped.
        assertEquals("BBBBBB", registration.retry(409))
        assertEquals("CCCCCC", registration.retry(409))
        assertNull(registration.retry(409))
        assertEquals(4, registration.attempts)
        assertNull(PairingRegistration("7K4M2Q").retry(null))
        assertNull(PairingRegistration("7K4M2Q") { "7K4M2Q" }.retry(409))
    }
    @Test fun qrPayloadRoundTrip() {
        val url = PairingCode.receiverUrl("7K4M2Q")
        assertEquals("https://pradertamas.github.io/realtime-web-test/webrtc-receiver.html?session=7K4M2Q", url)
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 600, 600)
        val pixels = IntArray(matrix.width * matrix.height) { if (matrix[it % matrix.width, it / matrix.width]) -0x1000000 else -1 }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))))
        assertEquals(url, decoded.text)
    }
}
