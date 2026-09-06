/*
 * YunX Desktop - AGPL-3.0. Desktop CredentialCipher: AES-GCM with a key file
 * stored next to the app data (no AndroidKeyStore on the JVM).
 */
package com.yunx.app.data.security

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialCipher {
    fun encrypt(plaintext: String, purpose: String): String
    fun decrypt(stored: String, purpose: String): String
    fun isEncrypted(stored: String): Boolean
}

/** AES-256-GCM envelope encryption; key persisted in [keyFile] (created on first use). */
class FileCredentialCipher(keyFile: File) : CredentialCipher {

    private val key: SecretKey = loadOrCreateKey(keyFile)

    override fun encrypt(plaintext: String, purpose: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(
            PREFIX,
            Base64.getEncoder().encodeToString(cipher.iv),
            Base64.getEncoder().encodeToString(ciphertext)
        ).joinToString(":")
    }

    override fun decrypt(stored: String, purpose: String): String {
        if (!isEncrypted(stored)) return stored
        val parts = stored.split(':', limit = 4)
        require(parts.size == 4 && parts[0] == "yunx" && parts[1] == "v1") {
            "Unsupported encrypted credential format"
        }
        val iv = Base64.getDecoder().decode(parts[2])
        val ciphertext = Base64.getDecoder().decode(parts[3])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    override fun isEncrypted(stored: String): Boolean =
        stored.startsWith("yunx:v1:")

    private fun loadOrCreateKey(file: File): SecretKey {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            val bytes = file.readBytes()
            return javax.crypto.spec.SecretKeySpec(bytes, "AES")
        }
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        val k = generator.generateKey()
        file.writeBytes(k.encoded)
        return k
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "yunx"
        private const val TAG = "credential"
    }
}
