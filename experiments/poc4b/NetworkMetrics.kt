package com.pradertamas.uicapturetest

enum class ConnectionMode(val label: String, val policy: String) {
    AUTO("AUTO / P2P preferred", "all"),
    FORCE_TURN("FORCE TURN relay", "relay");
}

object NetworkMetrics {
    // Decimal GB. RTP counters do not include all protocol/provider billing overhead.
    fun gbPerHour(bytes: Double?, seconds: Double): Double? {
        if (bytes == null || !bytes.isFinite() || bytes < 0 || !seconds.isFinite() || seconds <= 0) return null
        return (bytes / seconds * 3600 / 1_000_000_000).takeIf { it.isFinite() }
    }
    fun costPerHour(gb: Double?, usdPerGb: Double, relayConfirmed: Boolean): Double? {
        if (!relayConfirmed || gb == null || !gb.isFinite() || gb < 0 || !usdPerGb.isFinite() || usdPerGb < 0) return null
        return (gb * usdPerGb).takeIf { it.isFinite() }
    }
    fun relayPair(local: String?, remote: String?, state: String?): Boolean =
        state == "succeeded" && local == "relay" && remote == "relay"

    fun addressFamily(address: Any?): String = when {
        address !is String || address.isEmpty() -> "N/A"
        address.contains(":") -> "IPv6"
        address.matches(Regex("[0-9]+(\\.[0-9]+){3}")) -> "IPv4"
        else -> "N/A / obfuscated"
    }
}
