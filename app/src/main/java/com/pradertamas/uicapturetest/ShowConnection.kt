package com.pradertamas.uicapturetest

/** Keep in-flight own CREATE registrations too; expose codes only after relay acceptance. */
object ShowConnection {
    fun senderOwn(home: PairingHome) = home.intent == PairingIntent.CREATE &&
        home.phase in setOf(PairingPhase.CREATING, PairingPhase.WAITING) && PairingCode.normalize(home.code) == home.code
    fun receiverOwn(home: ReceiverHome) = home.intent == PairingIntent.CREATE &&
        home.phase == ReceiverPhase.PAIRING && PairingCode.normalize(home.code) == home.code
    fun senderReady(home: PairingHome) = senderOwn(home) && home.phase == PairingPhase.WAITING
    fun receiverReady(home: ReceiverHome) = receiverOwn(home) && home.registered
    fun ensureSender(home: PairingHome, create: () -> Unit) { if (!senderOwn(home)) create() }
    fun ensureReceiver(home: ReceiverHome, create: () -> Unit) { if (!receiverOwn(home)) create() }
}
