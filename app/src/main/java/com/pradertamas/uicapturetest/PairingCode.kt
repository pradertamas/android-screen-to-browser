package com.pradertamas.uicapturetest

import java.security.SecureRandom
import java.util.Locale
import java.net.URI
import java.net.URLDecoder

enum class PairingIntent(val wire: String) { CREATE("create"), JOIN("join") }

object PairingCode {
    const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val RECEIVER_URL = "https://pradertamas.github.io/realtime-web-test/webrtc-receiver.html"
    private val random = SecureRandom()
    private val recent = linkedSetOf<String>()
    fun normalize(value: String): String? = value.trim().takeIf { it.matches(Regex("[A-Za-z2-9]{6}")) }?.uppercase(Locale.ROOT)?.takeIf {
        it.length == 6 && it.all { character -> character in ALPHABET }
    }
    @Synchronized fun generate(): String {
        var code: String
        do { code = buildString { repeat(6) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } } }
        while (code in recent)
        recent.add(code)
        if (recent.size > 256) recent.remove(recent.first())
        return code
    }
    fun receiverUrl(code: String): String {
        require(normalize(code) == code)
        return "$RECEIVER_URL?session=$code"
    }
    fun parseQr(payload: String): String? {
        normalize(payload)?.let { return it }
        return try {
            val uri = URI(payload.trim())
            if (!uri.scheme.equals("https", true) || !uri.host.equals("pradertamas.github.io", true) || uri.port !in listOf(-1, 443) ||
                uri.userInfo != null || uri.fragment != null || uri.rawPath != "/realtime-web-test/webrtc-receiver.html") return null
            val parts = uri.rawQuery?.split("&") ?: return null
            if (parts.size != 1) return null
            val entry = parts.single().split("=", limit = 2)
            if (entry.size != 2 || URLDecoder.decode(entry[0], "UTF-8") != "session") return null
            normalize(URLDecoder.decode(entry[1], "UTF-8"))
        } catch (_: Exception) { null }
    }
}

/** Retry only a rejected sender upgrade, before SDP or capture restart. Four attempts total. */
class PairingRegistration(initialCode: String, val intent: PairingIntent = PairingIntent.CREATE, private val generate: () -> String = PairingCode::generate) {
    var code = initialCode
        private set
    var attempts = 1
        private set
    private val attempted = mutableSetOf(initialCode)
    init { require(PairingCode.normalize(initialCode) == initialCode) }
    fun retry(httpStatus: Int?): String? {
        if (intent != PairingIntent.CREATE || httpStatus != 409 || attempts >= 4) return null
        repeat(8) {
            val next = generate()
            require(PairingCode.normalize(next) == next)
            if (attempted.add(next)) { code = next; attempts++; return next }
        }
        return null
    }
}
