package com.pradertamas.uicapturetest

/** ViewModel-owned one-shot delivery. Bearer links are memory-only and never logged/saved. */
class ConnectionShareDelivery(private val config: PairingBackendConfig,
    private val changed: (String?) -> Unit, private val error: (String) -> Unit) {
    private var pending: String? = null
    private var failed: String? = null
    fun offer(link: String) {
        require(AppPairingLink.parse(link, config) != null)
        failed = null; pending = link; error(""); changed(link)
    }
    fun take(expected: String): String? {
        if (pending != expected) return null
        pending = null; changed(null)
        return expected
    }
    fun launchFailed(link: String) {
        if (AppPairingLink.parse(link, config) == null) return
        failed = link
        error("Connection link could not be shared. Please try again.")
    }
    fun retry(): Boolean {
        val link = failed ?: return false
        offer(link) // Reshare only: no new invite, identity or backend request.
        return true
    }
    fun clear() { pending = null; failed = null; changed(null); error("") }
}
