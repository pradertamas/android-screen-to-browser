package com.pradertamas.uicapturetest

import java.net.URI

internal class ConnectionRequestShareData(val requestId: String, val recipientCapability: String, val role: MediaRole) {
    override fun toString() = "ConnectionRequestShareData[redacted]"
}
internal object ConnectionRequestLink {
    fun parse(raw: String?): ConnectionRequestIncomingLink? = try {
        val uri = URI(requireNotNull(raw))
        require(uri.scheme == "https" && uri.host == "pairing.pradert.synology.me" &&
            uri.rawAuthority == "pairing.pradert.synology.me" && uri.rawPath == "/connect" &&
            uri.rawQuery == null && uri.rawUserInfo == null && uri.port == -1)
        val match = Regex("v=1&r=([A-Za-z0-9_-]{22})&c=([A-Za-z0-9_-]{22})&i=(send|receive)").matchEntire(uri.rawFragment ?: "")
            ?: error("Invalid connection link")
        val id = match.groupValues[1]; val cap = match.groupValues[2]
        require(ConnectionRequestSecrets.canonical(id, 16) && ConnectionRequestSecrets.canonical(cap, 16))
        ConnectionRequestIncomingLink(id, cap, requireNotNull(ConnectionRequestRoles.parse(match.groupValues[3])))
    } catch (_: Exception) { null }
    fun build(data: ConnectionRequestShareData): String? {
        if (!ConnectionRequestSecrets.canonical(data.requestId, 16) ||
            !ConnectionRequestSecrets.canonical(data.recipientCapability, 16)) return null
        val fragment = "v=1&r=${data.requestId}&c=${data.recipientCapability}&i=${ConnectionRequestRoles.wire(data.role)}"
        return URI("https", "pairing.pradert.synology.me", "/connect", null, fragment).toASCIIString()
    }
}

internal class ConnectionRequestIncomingLink(val requestId: String, val recipientCapability: String, val initiatorRole: MediaRole) {
    override fun toString() = "ConnectionRequestIncomingLink[redacted]"
}
