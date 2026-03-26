package com.parth.mobileclaw.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for API keys using EncryptedSharedPreferences.
 * Replaces iOS KeychainHelper.
 */
class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = try {
        createPrefs(context, masterKey)
    } catch (e: Exception) {
        // If keystore keys are wiped or file is corrupted during backup/restore,
        // clear the corrupted prefs file and recreate it.
        context.getSharedPreferences("mobileclaw_secure_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        val file = java.io.File(context.filesDir.parentFile, "shared_prefs/mobileclaw_secure_prefs.xml")
        if (file.exists()) file.delete()
        createPrefs(context, masterKey)
    }

    private fun createPrefs(context: Context, key: MasterKey) = EncryptedSharedPreferences.create(
        context,
        "mobileclaw_secure_prefs",
        key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    enum class Key(val key: String) {
        ANTHROPIC_API_KEY("anthropic_api_key"),
        OPENAI_API_KEY("openai_api_key"),
        OPENROUTER_API_KEY("openrouter_api_key"),
        DEEPSEEK_API_KEY("deepseek_api_key"),
        ALIYUN_API_KEY("aliyun_api_key"),
        ZHIPU_API_KEY("zhipu_api_key"),
        SILICONFLOW_API_KEY("siliconflow_api_key"),
        GROQ_API_KEY("groq_api_key"),
        TOGETHER_API_KEY("together_api_key"),
        GOOGLE_API_KEY("google_api_key"),
        CUSTOM_API_KEY("custom_api_key")
    }

    fun save(key: Key, value: String) {
        prefs.edit().putString(key.key, value).apply()
    }

    fun read(key: Key): String? = prefs.getString(key.key, null)

    fun delete(key: Key) {
        prefs.edit().remove(key.key).apply()
    }

    fun hasKey(key: Key): Boolean = prefs.contains(key.key)
}
