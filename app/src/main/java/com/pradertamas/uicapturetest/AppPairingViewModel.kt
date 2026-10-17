package com.pradertamas.uicapturetest

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

object DeviceIdentityStorage {
    private var store: DeviceIdentityStore? = null
    @Synchronized fun get(context: Context): DeviceIdentityStore {
        return store ?: run {
            val prefs = context.applicationContext.getSharedPreferences(DeviceIdentityStore.PREFS, Context.MODE_PRIVATE)
            DeviceIdentityStore({
                val id = prefs.getString("deviceId", null); val credential = prefs.getString("credential", null)
                if (id != null && credential != null) DeviceIdentity(id, credential) else null
            }, { value -> prefs.edit().putString("deviceId", value.deviceId).putString("credential", value.credential).commit() }).also { store = it }
        }
    }
}
/** Retained per-screen owner; only explicit pairing actions cause registration/network requests. */
class AppPairingViewModel(application: Application) : AndroidViewModel(application) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    val config = PairingBackendConfig.build()
    private val api = PairingApiClient(config)
    private val mutable = MutableStateFlow(AppPairingUi())
    val state = mutable.asStateFlow()
    private val shareMutable = MutableStateFlow<String?>(null)
    val share = shareMutable.asStateFlow()
    private var incoming: String? = null
    private val controller = AppPairingController(DeviceIdentityStorage.get(application), api,
        { action -> worker.execute(action) }, { action -> main.post { action() } }, { mutable.value = it }, { shareMutable.value = it }, config)
    fun create(allowed: Boolean) { controller.create(allowed && AppMediaRoles.pairingAllowed()) }
    fun incoming(uri: String) { incoming = uri }
    fun processIncoming(allowed: Boolean) { val uri = incoming ?: return; incoming = null; controller.incoming(uri, allowed) }
    fun accept(allowed: Boolean) { controller.accept(allowed && AppMediaRoles.pairingAllowed()) }
    fun cancel() { controller.cancel() }
    fun retry(allowed: Boolean) { controller.retry(allowed && AppMediaRoles.pairingAllowed()) }
    fun shared() { shareMutable.value = null }
    override fun onCleared() { controller.close(); api.close(); worker.shutdownNow(); main.removeCallbacksAndMessages(null) }
}
