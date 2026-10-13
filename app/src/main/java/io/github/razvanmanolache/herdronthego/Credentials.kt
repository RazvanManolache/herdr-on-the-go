package io.github.razvanmanolache.herdronthego

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException

/** Persistent passwords always use the Android keystore; failure never selects plaintext. */
class Credentials(private val ctx: Context) {
    var notice: String? = null
        private set
    private var legacyCleanupFailed = false

    init {
        try {
            val legacy = ctx.getSharedPreferences("herdr_on_the_go_secret_plain", Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                if (!legacy.edit().clear().commit() || !ctx.deleteSharedPreferences("herdr_on_the_go_secret_plain")) {
                    legacyCleanupFailed = true
                    notice = "Legacy unencrypted credentials could not be removed. Clear app storage before saving passwords"
                } else {
                    notice = "Legacy unencrypted passwords were removed. Enter them again to save them securely"
                }
            }
        } catch (_: Exception) {
            legacyCleanupFailed = true
            notice = "Legacy password storage could not be checked. Connect without saving until storage is repaired"
        }
    }

    private val encrypted: SharedPreferences? by lazy {
        if (legacyCleanupFailed) null else try {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(ctx, "herdr_on_the_go_secret", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (_: Exception) {
            notice = "Secure password storage is unavailable. Connect without saving, or restore secure storage"
            null
        }
    }

    val available: Boolean get() = encrypted != null
    private fun key(c: SavedConnection): String = "pw:${c.user}@${c.host}:${c.port}"

    fun get(c: SavedConnection): String? = encrypted?.getString(key(c), null)
    fun save(c: SavedConnection, password: String) {
        val store = encrypted ?: throw IOException("Secure password storage is unavailable")
        check(store.edit().putString(key(c), password).commit()) { "Could not save password securely" }
    }
    fun remove(c: SavedConnection) {
        val store = encrypted ?: throw IOException("Secure storage must be available to erase saved credentials")
        check(store.edit().remove(key(c)).commit()) { "Could not erase saved password" }
    }
}
