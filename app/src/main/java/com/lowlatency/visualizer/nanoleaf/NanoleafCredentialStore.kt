package com.lowlatency.visualizer.nanoleaf

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class NanoleafCredentials(
    val ip: String,
    val authToken: String,
    val port: Int = 16021
)

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

    fun saveCredentials(creds: NanoleafCredentials) {
        prefs.edit()
            .putString(KEY_IP, creds.ip)
            .putString(KEY_AUTH_TOKEN, creds.authToken)
            .putInt(KEY_PORT, creds.port)
            .apply()
    }

    fun loadCredentials(): NanoleafCredentials? {
        val ip = prefs.getString(KEY_IP, null) ?: return null
        val token = prefs.getString(KEY_AUTH_TOKEN, null) ?: return null
        val port = prefs.getInt(KEY_PORT, 16021)
        return NanoleafCredentials(ip, token, port)
    }

    var syncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply() }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_IP = "nano_ip"
        private const val KEY_AUTH_TOKEN = "nano_token"
        private const val KEY_PORT = "nano_port"
        private const val KEY_SYNC_ENABLED = "nano_sync_enabled"
    }
}
