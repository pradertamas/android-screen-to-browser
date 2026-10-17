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
    private val shareErrorMutable = MutableStateFlow("")
    val shareError = shareErrorMutable.asStateFlow()
    private val shareDelivery = ConnectionShareDelivery(config, { shareMutable.value = it }, { shareErrorMutable.value = it })
    private val devicesMutable = MutableStateFlow<List<PairedDevice>>(emptyList())
    val devices = devicesMutable.asStateFlow()
    private val devicesMessageMutable = MutableStateFlow("")
    val devicesMessage = devicesMessageMutable.asStateFlow()
    private var loadingDevices = false
    private var closed = false
    fun refreshDevices() {
        if (closed || loadingDevices || !config.configured) return
        // Reading the list must not register a new installation/identity.
        val identity = DeviceIdentityStorage.get(getApplication()).existing() ?: return
        loadingDevices = true
        worker.execute {
            val result = runCatching {
                val rows = api.listPairings(identity)
                val devices = (0 until rows.length()).mapNotNull { index ->
                    val row = rows.optJSONObject(index) ?: return@mapNotNull null
                    PairedDevice.fromPublicId(row.optString("otherDeviceId"))
                }
                check(devices.size == rows.length()) // Unknown schema is not an empty device list.
                devices.distinct()
            }
            main.post {
                if (!closed) {
                    loadingDevices = false
                    result.fold({ devicesMutable.value = it; devicesMessageMutable.value = "" },
                        { devicesMutable.value = emptyList(); devicesMessageMutable.value = "Saved connections are temporarily unavailable." })
                }
            }
        }
    }
    private var incoming: String? = null
    private val controller = AppPairingController(DeviceIdentityStorage.get(application), api,
        { action -> worker.execute(action) }, { action -> main.post { action() } }, { mutable.value = it }, shareDelivery::offer, config)
    fun create(allowed: Boolean) {
        if (!allowed || !AppMediaRoles.pairingAllowed()) return
        if (!shareDelivery.retry()) controller.create(true)
    }
    fun incoming(uri: String) { shareDelivery.clear(); incoming = uri }
    fun processIncoming(allowed: Boolean) { val uri = incoming ?: return; incoming = null; controller.incoming(uri, allowed) }
    fun accept(allowed: Boolean) { controller.accept(allowed && AppMediaRoles.pairingAllowed()) }
    fun cancel() { shareDelivery.clear(); controller.cancel() }
    fun retry(allowed: Boolean) {
        if (!allowed || !AppMediaRoles.pairingAllowed()) return
        if (!shareDelivery.retry()) controller.retry(true)
    }
    fun shared(expected: String): String? = shareDelivery.take(expected)
    fun shareFailed(link: String) { shareDelivery.launchFailed(link) }
    override fun onCleared() { closed = true; shareDelivery.clear(); controller.close(); api.close(); worker.shutdownNow(); main.removeCallbacksAndMessages(null) }
}
