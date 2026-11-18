package com.imsbooks.songbook.data.session

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session storage: the refresh cookie and the last signed-in user, in the app's private
 * SharedPreferences. Android's app sandbox (and file-based encryption on every supported device) keeps
 * them away from other apps; they are excluded from cloud backup and device transfer
 * (data_extraction_rules.xml), and the access token is never persisted (docs/SECURITY.md in the web repo).
 *
 * Earlier versions wrapped the values with an Android Keystore key. On some devices and PC emulators that key
 * does not survive an app restart, so the session could not be read back and members had to log in every
 * time. Values written by those versions are migrated once, when the key is still readable.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("songbook_session", Context.MODE_PRIVATE)

    init {
        migrateFromKeystore(context)
    }

    fun get(name: String): String? = prefs.getString(name, null)

    /** Written synchronously: a rotated refresh token must be on disk before the app can be killed. */
    fun put(name: String, value: String) = prefs.edit(commit = true) { putString(name, value) }

    fun remove(name: String) = prefs.edit(commit = true) { remove(name) }

    // ------------------------------------------------------------------ one-time migration

    private fun migrateFromKeystore(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        prefs.edit(commit = true) {
            for ((name, stored) in legacy.all) {
                if (stored is String && prefs.getString(name, null) == null) decryptLegacy(stored)?.let { putString(name, it) }
            }
        }
        legacy.edit(commit = true) { clear() }
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(LEGACY_KEY_ALIAS)
        } catch (_: Exception) {
            // Nothing left to clean up.
        }
    }

    private fun decryptLegacy(stored: String): String? = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(LEGACY_KEY_ALIAS, null) as? SecretKey ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "Could not migrate a stored value (${e.javaClass.simpleName})")
        null
    }

    private companion object {
        const val TAG = "SecureStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val LEGACY_PREFS = "songbook_secure"
        const val LEGACY_KEY_ALIAS = "songbook_session_key"
    }
}
