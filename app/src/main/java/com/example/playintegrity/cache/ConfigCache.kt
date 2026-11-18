package com.example.playintegrity.cache

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.playintegrity.data.SecureConfig
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ConfigCache {
    fun read(): SecureConfig?
    fun write(config: SecureConfig)
    fun clear()
}

/**
 * AES-256-GCM encrypted cache. The key lives in the Android Keystore and never leaves it,
 * so a copied SharedPreferences file is useless on another device.
 *
 * Stored value: base64(iv) + ":" + base64(ciphertext). Any decrypt/parse failure wipes the entry.
 */
class EncryptedConfigCache(context: Context) : ConfigCache {

    private val prefs = context.applicationContext
        .getSharedPreferences("secure_config_cache", Context.MODE_PRIVATE)

    override fun read(): SecureConfig? {
        val stored = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val (ivB64, ctB64) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
            SecureConfig.fromJson(JSONObject(String(plain, Charsets.UTF_8)))
        } catch (e: Exception) {
            clear() // tampered, key invalidated, or format changed
            null
        }
    }

    override fun write(config: SecureConfig) {
        if (!config.cacheable) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(config.toJson().toString().toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(KEY_BLOB, blob).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "secure_config_cache_key_v1"
        const val KEY_BLOB = "blob_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AAD = "secure-config-v1".toByteArray()
    }
}
