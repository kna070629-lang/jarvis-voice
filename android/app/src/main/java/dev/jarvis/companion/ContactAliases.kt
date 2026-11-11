package dev.jarvis.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * The contact aliases.
 *
 * An alias is a name the user invents for a person, such as "home",
 * "father" or "best friend". The aliases are held in the app's private
 * storage and encrypted with a key that lives in the Android Keystore, so
 * they are not readable by anything outside this app.
 */
object ContactAliases {
    private const val PREFS = "jarvis_contact_aliases"
    private const val KEY_ALIAS = "jarvis_contact_aliases_v1"
    private const val CIPHER = "AES/GCM/NoPadding"

    /** The alias to the contact identifier, or to the contact name. */
    fun load(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString("alias_iv", null) ?: return emptyMap()
        val data = prefs.getString("alias_data", null) ?: return emptyMap()
        val plain = try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyMap()
        }
        return try {
            val json = JSONObject(plain)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.optString(key)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Save the aliases. */
    fun save(context: Context, aliases: Map<String, String>) {
        val json = JSONObject()
        for ((key, value) in aliases) {
            if (key.isNotBlank() && value.isNotBlank()) json.put(key.trim(), value.trim())
        }
        val plain = json.toString()
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("alias_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("alias_data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .apply()
        } catch (_: Exception) {
            // The aliases are a convenience, so a failure here must not stop
            // the assistant from working.
        }
    }

    /** Point an alias at a contact. */
    fun set(context: Context, alias: String, contact: String) {
        val aliases = load(context).toMutableMap()
        aliases[alias.trim()] = contact.trim()
        save(context, aliases)
    }

    /** Remove an alias. */
    fun remove(context: Context, alias: String): Boolean {
        val aliases = load(context).toMutableMap()
        val removed = aliases.remove(alias.trim()) != null
        if (removed) save(context, aliases)
        return removed
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
