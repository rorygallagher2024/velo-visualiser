package com.lowlatency.visualizer.hue

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted persistence for Hue pairing secrets (username + clientkey/PSK) and
 * the user's chosen Entertainment Area. The clientkey is a DTLS pre-shared key,
 * so it is treated as a secret and kept in [EncryptedSharedPreferences].
 *
 * The selected-area id is non-secret but stored here too for cohesion.
 */
class HueCredentialStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "hue_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveCredentials(creds: HueCredentials) {
        prefs.edit()
            .putString(KEY_IP, creds.bridgeIp)
            .putString(KEY_USERNAME, creds.username)
            .putString(KEY_CLIENTKEY, creds.clientKey)
            .apply()
    }

    fun loadCredentials(): HueCredentials? {
        val ip = prefs.getString(KEY_IP, null) ?: return null
        val user = prefs.getString(KEY_USERNAME, null) ?: return null
        val key = prefs.getString(KEY_CLIENTKEY, null) ?: return null
        return HueCredentials(ip, user, key)
    }

    var selectedAreaId: String?
        get() = prefs.getString(KEY_AREA_ID, null)
        set(value) { prefs.edit().putString(KEY_AREA_ID, value).apply() }

    /** Light-sync master toggle, persisted so it can auto-resume. */
    var syncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply() }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_IP = "bridge_ip"
        private const val KEY_USERNAME = "username"
        private const val KEY_CLIENTKEY = "clientkey"
        private const val KEY_AREA_ID = "area_id"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
    }
}
