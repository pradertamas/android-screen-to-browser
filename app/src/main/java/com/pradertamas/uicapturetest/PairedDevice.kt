package com.pradertamas.uicapturetest

data class PairedDevice(val publicId: String) {
    companion object {
        fun fromPublicId(value: String): PairedDevice? = value.takeIf { AppPairingLink.valid(it, 16) }?.let(::PairedDevice)
    }
}
