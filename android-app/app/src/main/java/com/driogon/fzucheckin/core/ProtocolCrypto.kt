package com.driogon.fzucheckin.core

import com.google.gson.GsonBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ProtocolCrypto {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    // Public protocol constants from the existing client, not account credentials.
    private val attnKey = "apexinfoapexinfo".toByteArray(UTF_8)

    fun encryptPassword(password: String, encodedKey: String): String {
        val key = try { Base64.getDecoder().decode(encodedKey) } catch (_: IllegalArgumentException) {
            throw CheckinException(ErrorKind.PROTOCOL, "登录页面密钥格式变化，请更新客户端")
        }
        val algorithm = when (key.size) {
            8 -> "DES"
            16, 24, 32 -> "AES"
            else -> throw CheckinException(ErrorKind.PROTOCOL, "不支持的登录密钥长度")
        }
        return encrypt(password.toByteArray(UTF_8), key, "$algorithm/ECB/PKCS5Padding", algorithm)
    }

    fun encryptPayload(payload: Map<String, Any>): String = encrypt(
        gson.toJson(payload).toByteArray(UTF_8), attnKey, "AES/CBC/PKCS5Padding", "AES", attnKey,
    )

    // First encoding mirrors encodeURIComponent; OkHttp then encodes this as a query value.
    fun encodeURIComponent(value: String): String = URLEncoder.encode(value, UTF_8.name())
        .replace("+", "%20").replace("%21", "!").replace("%27", "'")
        .replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    private fun encrypt(raw: ByteArray, key: ByteArray, transformation: String, algorithm: String, iv: ByteArray? = null): String {
        val cipher = Cipher.getInstance(transformation)
        val secret = SecretKeySpec(key, algorithm)
        if (iv == null) cipher.init(Cipher.ENCRYPT_MODE, secret)
        else cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(cipher.doFinal(raw))
    }
}
