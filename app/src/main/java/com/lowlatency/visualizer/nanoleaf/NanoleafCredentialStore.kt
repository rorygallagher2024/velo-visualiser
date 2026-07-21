package com.lowlatency.visualizer.nanoleaf

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class NanoleafCredentials(
    val ip: String,
    val authToken: String,
    val port: Int = 16021,
    /**
     * Stable per-device identity from mDNS, which is what a pairing is really
     * *about*. An IP is a lease, not an identity: keyed on address alone there is
     * no way to tell "my panel got a new IP from DHCP" from "that is a different
     * panel entirely", and the second reading led the app to re-pair against
     * whichever device happened to answer first.
     *
     * Empty for pairings made before this was recorded; adopted on the first
     * successful token check.
     */
    val deviceId: String = "",
    /** Human-readable mDNS service name, e.g. "Nanoleaf Light Panels 53:3B:F0". */
    val name: String = ""
) {
    /** Store key: the identity when known, the address as a stand-in for legacy entries. */
    val key: String get() = deviceId.ifEmpty { "addr:$ip:$port" }
}

/**
 * Encrypted storage for every paired Nanoleaf device. One JSON array under a
 * single key; pre-multi-device installs stored one device in flat keys, which
 * [migrateLegacy] folds into a single-entry list on first read.
 */
class NanoleafCredentialStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "nanoleaf_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** All paired devices, oldest pairing first. */
    fun loadAll(): List<NanoleafCredentials> {
        migrateLegacy()
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(devices: List<NanoleafCredentials>) {
        val arr = JSONArray()
        devices.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    /** Insert or replace by [NanoleafCredentials.key]. */
    fun upsert(creds: NanoleafCredentials) {
        saveAll(loadAll().filterNot { it.key == creds.key } + creds)
    }

    fun remove(key: String) {
        saveAll(loadAll().filterNot { it.key == key })
    }

    var syncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply() }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun fromJson(o: JSONObject): NanoleafCredentials? {
        val ip = o.optString("ip")
        val token = o.optString("token")
        if (ip.isEmpty() || token.isEmpty()) return null
        return NanoleafCredentials(
            ip = ip,
            authToken = token,
            port = o.optInt("port", DEFAULT_PORT),
            deviceId = o.optString("id"),
            name = o.optString("name"),
        )
    }

    private fun toJson(d: NanoleafCredentials) = JSONObject().apply {
        put("ip", d.ip)
        put("token", d.authToken)
        put("port", d.port)
        put("id", d.deviceId)
        put("name", d.name)
    }

    /** Fold the old single-device flat keys into the list, once. */
    private fun migrateLegacy() {
        val ip = prefs.getString(KEY_IP, null) ?: return
        val token = prefs.getString(KEY_AUTH_TOKEN, null)
        if (token != null && prefs.getString(KEY_DEVICES, null) == null) {
            val port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
            val id = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
            saveAll(listOf(NanoleafCredentials(ip, token, port, id)))
        }
        prefs.edit()
            .remove(KEY_IP).remove(KEY_AUTH_TOKEN).remove(KEY_PORT).remove(KEY_DEVICE_ID)
            .apply()
    }

    companion object {
        private const val DEFAULT_PORT = 16021
        private const val KEY_DEVICES = "nano_devices"
        private const val KEY_SYNC_ENABLED = "nano_sync_enabled"
        // Legacy single-device keys, read once by migrateLegacy() then removed.
        private const val KEY_IP = "nano_ip"
        private const val KEY_AUTH_TOKEN = "nano_token"
        private const val KEY_PORT = "nano_port"
        private const val KEY_DEVICE_ID = "nano_device_id"
    }
}
