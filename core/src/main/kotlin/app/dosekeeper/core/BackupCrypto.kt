package app.dosekeeper.core

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Optional password protection for backup files: PBKDF2-SHA256 key, AES-256-GCM. */
object BackupCrypto {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    class Sealed(val salt: ByteArray, val iv: ByteArray, val data: ByteArray)

    class WrongPasswordException : Exception("Wrong password, or the file is damaged")

    fun seal(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): Sealed {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, iv))
        return Sealed(salt, iv, cipher.doFinal(plain))
    }

    fun open(sealed: Sealed, password: CharArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, sealed.salt), GCMParameterSpec(TAG_BITS, sealed.iv))
        return try {
            cipher.doFinal(sealed.data)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
