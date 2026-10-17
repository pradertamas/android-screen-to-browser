package com.pradertamas.uicapturetest

/** No URL ever enters diagnostic output. Kept pure for registration-role tests. */
object ReceiverRegistration {
    fun url(code: String, intent: PairingIntent): String {
        require(PairingCode.normalize(code) == code)
        return MediaRole.RECEIVE.registrationUrl(code, intent)
    }
}
