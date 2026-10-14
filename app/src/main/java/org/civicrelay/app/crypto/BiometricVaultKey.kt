package org.civicrelay.app.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Optional auth-per-use biometric wrapper around the existing 256-bit derived vault key.
 * Android 11+ only. This never saves the passphrase and invalidates on biometric enrollment changes.
 */
class BiometricVaultKey(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "vault-quick-unlock.json"))
    private val alias = "civicrelay.biometric.vault.v1"
    val enrolled: Boolean get() = Build.VERSION.SDK_INT >= 30 && file.baseFile.isFile
    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        check(Build.VERSION.SDK_INT >= 30) { "Requires Android 11 or newer" }
        (keystore().getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Biometric key missing. Unlock with passphrase to set up again." }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true).build())
        return generator.generateKey()
    }
    fun prepareEnroll(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, key(true))
    }
    fun completeEnroll(cipher: Cipher, derivedKey: ByteArray, salt: ByteArray) {
        require(derivedKey.size == 32 && salt.size == 16)
        val sealed = cipher.doFinal(derivedKey)
        val bytes = JSONObject().put("version", 1).put("iv", Crypto.b64(cipher.iv))
            .put("ciphertext", Crypto.b64(sealed)).put("salt", Crypto.b64(salt))
            .toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun prepareUnlock(): Cipher {
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        check(json.getInt("version") == 1)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false),
                GCMParameterSpec(128, Crypto.decode(json.getString("iv"))))
        }
    }
    fun completeUnlock(cipher: Cipher): Pair<ByteArray, ByteArray> {
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        check(json.getInt("version") == 1)
        val key = cipher.doFinal(Crypto.decode(json.getString("ciphertext")))
        return key to Crypto.decode(json.getString("salt"))
    }
    fun disable() {
        file.baseFile.delete()
        try { keystore().deleteEntry(alias) } catch (_: Exception) {}
    }
}
