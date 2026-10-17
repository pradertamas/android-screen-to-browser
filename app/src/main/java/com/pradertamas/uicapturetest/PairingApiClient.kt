package com.pradertamas.uicapturetest

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** HTTPS REST only. No redirects, interceptors, query credentials or diagnostic response bodies. */
class PairingApiClient(private val config: PairingBackendConfig = PairingBackendConfig.build()) : AppPairingApi {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(12, TimeUnit.SECONDS).build()
    private val base = config.apiBaseUrl.trimEnd('/')
    private fun request(path: String, identity: DeviceIdentity? = null, input: JSONObject = JSONObject(), method: String = "POST"): JSONObject {
        check(config.configured) { "Pairing backend is not configured." }
        val builder = Request.Builder().url(base + path)
        if (identity != null) builder.header("Authorization", "Bearer ${identity.credential}")
        builder.header("Cache-Control", "no-store")
        if (method == "GET") builder.get() else builder.post(input.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            // Inspect self-pair is a deliberate, nonsecret 409 result.
            check(response.isSuccessful || response.code == 409)
            val source = checkNotNull(response.body).source()
            require(!source.request(16_385))
            return JSONObject(source.readUtf8())
        }
    }
    override fun register(): DeviceIdentity = request("/v1/devices/register").let { DeviceIdentity(it.getString("deviceId"), it.getString("credential")) }
    override fun create(identity: DeviceIdentity) = request("/v1/pairing-invites", identity).getString("inviteUrl")
    override fun inspect(identity: DeviceIdentity, token: String) = request("/v1/pairing-invites/inspect", identity, JSONObject().put("token", token)).getString("status")
    override fun accept(identity: DeviceIdentity, token: String) = request("/v1/pairing-invites/accept", identity, JSONObject().put("token", token)).getString("status")
    fun listPairings(identity: DeviceIdentity) = request("/v1/pairings", identity, method = "GET").getJSONArray("pairings")
    fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
