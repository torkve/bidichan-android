package torkve.bidichan.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores pre-shared keys under a key held in the platform keystore, so they are
 * encrypted at rest and never sit in the profile file alongside everything else.
 */
class Secrets(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "bidichan-secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    operator fun get(account: String): String? = prefs.getString(account, null)

    operator fun set(account: String, value: String) {
        prefs.edit().putString(account, value).apply()
    }

    fun remove(account: String) {
        prefs.edit().remove(account).apply()
    }
}
