package com.pradertamas.uicapturetest

import java.security.SecureRandom
import java.util.Base64

object RemoteDevice {
    const val PREFS_FILE = "remote_device"
    private val random = SecureRandom()
    fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
    fun valid(token: String) = token.matches(Regex("[A-Za-z0-9_-]{21}[AQgw]"))
    fun url(token: String): String {
        require(valid(token)); return "${PairingCode.RECEIVER_URL}#device=$token"
    }
    const val SUBJECT = "Screen sharing link"
    const val MIME_TYPE = "text/plain"
    fun shareText(token: String) = "Open this link while my phone is waiting to share its screen:\n\n${url(token)}"
}

/** Dedicated private preferences adapter injected for JVM persistence tests. No expiry. */
class RemoteTokenStore(private val read: () -> String?, private val write: (String) -> Boolean,
    private val generate: () -> String = RemoteDevice::generate) {
    @Synchronized fun get(): String {
        val token = read()
        if (token != null && RemoteDevice.valid(token)) return token
        return regenerate()
    }
    @Synchronized fun regenerate(): String {
        val old = read()
        repeat(32) {
            val next = generate()
            require(RemoteDevice.valid(next))
            if(next != old){
                check(write(next)) { "Remote link could not be saved" }
                return next
            }
        }
        error("Remote link could not be generated")
    }
}
