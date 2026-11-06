package com.driogon.fzucheckin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.driogon.fzucheckin.core.Credentials
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
    fun load(): Credentials?
    fun save(credentials: Credentials)
    fun clear()
}

class EncryptedCredentialStore(context: Context, namespace: String = "account") : CredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences("encrypted_$namespace", Context.MODE_PRIVATE)
    private val alias = "fzu_checkin_${namespace}_v1"
    private val aad = "com.driogon.fzucheckin.account.v1".toByteArray(Charsets.UTF_8)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    override fun load(): Credentials? {
        val encoded = prefs.getString("blob", null) ?: return null
        val packed = Base64.getDecoder().decode(encoded)
        require(packed.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        val clear = cipher.doFinal(packed.copyOfRange(12, packed.size))
        try {
            val obj = JsonParser.parseString(clear.toString(Charsets.UTF_8)).asJsonObject
            return Credentials(obj.get("username").asString, obj.get("password").asString)
        } finally { clear.fill(0) }
    }

    override fun save(credentials: Credentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val obj = JsonObject().apply { addProperty("username", credentials.username); addProperty("password", credentials.password) }
        val clear = obj.toString().toByteArray(Charsets.UTF_8)
        try {
            val packed = cipher.iv + cipher.doFinal(clear)
            check(prefs.edit().putString("blob", Base64.getEncoder().encodeToString(packed)).commit())
        } finally { clear.fill(0) }
    }

    override fun clear() {
        check(prefs.edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
}
