package com.pradertamas.uicapturetest

import java.net.URI

internal class ConnectionRequestShareData(val requestId: String, val recipientCapability: String, val role: MediaRole) {
    override fun toString() = "ConnectionRequestShareData[redacted]"
}
internal object ConnectionRequestLink {
    fun build(data: ConnectionRequestShareData): String? {
        if (!ConnectionRequestSecrets.canonical(data.requestId, 16) ||
            !ConnectionRequestSecrets.canonical(data.recipientCapability, 16)) return null
        val fragment = "v=1&r=${data.requestId}&c=${data.recipientCapability}&i=${ConnectionRequestRoles.wire(data.role)}"
        return URI("https", "pairing.pradert.synology.me", "/connect", null, fragment).toASCIIString()
    }
}
