package com.jarvis.assistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/** API key + user-approved memory, encrypted at rest with the Android Keystore. */
class SecureStore(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "jarvis_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", "claude-sonnet-5-5") ?: "claude-sonnet-5-5"
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    fun memory(): List<String> {
        val a = JSONArray(prefs.getString("memory", "[]"))
        return List(a.length()) { a.getString(it) }
    }

    fun addMemory(fact: String) {
        val l = memory().toMutableList()
        if (fact !in l) l.add(fact)
        prefs.edit().putString("memory", JSONArray(l.takeLast(50)).toString()).apply()
    }

    fun clearMemory() = prefs.edit().remove("memory").apply()
}
