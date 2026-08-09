package org.isdm.companion.platform

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class StoredCredentials(val email: String, val password: String)

class SecureCredentialStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): StoredCredentials? {
        val email = preferences.getString(KEY_EMAIL, null) ?: return null
        val password = preferences.getString(KEY_PASSWORD, null) ?: return null
        return StoredCredentials(email, password)
    }

    fun save(email: String, password: String) {
        preferences.edit()
            .putString(KEY_EMAIL, email.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "secure_lms_credentials"
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
    }
}
