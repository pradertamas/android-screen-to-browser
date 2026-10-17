package com.pradertamas.uicapturetest

import java.net.URI

/** Deployment metadata only. Missing NAS config disables ONLY persistent pairing. */
data class PairingBackendConfig(val apiBaseUrl: String, val appLinkHost: String, val appLinkPath: String) {
    val configured: Boolean get() = try {
        val api = URI(apiBaseUrl)
        api.scheme == "https" && api.host != null && api.rawAuthority == api.host && api.rawPath.orEmpty() in setOf("", "/") &&
            api.rawQuery == null && api.rawFragment == null && !api.host.endsWith(".invalid") &&
            appLinkHost.matches(Regex("[a-z0-9]+([.-][a-z0-9]+)*")) && !appLinkHost.endsWith(".invalid") &&
            appLinkPath.matches(Regex("/[A-Za-z0-9/_-]+"))
    } catch (_: Exception) { false }
    companion object {
        fun build() = PairingBackendConfig(BuildConfig.PAIRING_API_BASE_URL, BuildConfig.PAIRING_APP_LINK_HOST, BuildConfig.PAIRING_APP_LINK_PATH)
    }
}
