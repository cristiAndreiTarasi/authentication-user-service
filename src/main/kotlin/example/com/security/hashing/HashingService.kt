package example.com.security.hashing

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class SaltedHash(
    val hash: String,
    val salt: String,
)

interface IHashingService {
    // Generates a salted hash for a given value, with an optional custom salt length
    fun generateSaltedHash(value: String, saltLength: Int = 32): SaltedHash

    // Verifies if the given value matches the provided salted hash
    fun verify(value: String, saltedHash: SaltedHash): Boolean
}

//SHA256HashingService is an implementation of the HashingService interface using the SHA-256 hashing algorithm
class HashingService : IHashingService {
    // Generates a salted hash for a given value, with an optional custom salt length
    override fun generateSaltedHash(value: String, saltLength: Int): SaltedHash {
        val salt = generateSalt(saltLength)
        val hash = hashPassword(value,salt)

        return SaltedHash(
            hash = hash,
            salt = salt
        )
    }

    // Verifies if the given value matches the provided salted hash
    override fun verify(value: String, saltedHash: SaltedHash): Boolean {
        val hashedValue = hashPassword(value, saltedHash.salt)
        return hashedValue == saltedHash.hash
    }

    // Generates a random salt of specified length
    private fun generateSalt(length: Int): String {
        val salt = ByteArray(length)
        SecureRandom.getInstance(Algorithm.SHA1PRNG.value).nextBytes(salt)
        return Base64.getEncoder().encodeToString(salt)
    }
}

// Hashes a given password using the provided salt
fun hashPassword(password: String, salt: String): String {
    val iterations = 65536
    val keyLength = 256
    val saltBytes = Base64.getDecoder().decode(salt)
    val keySpec = PBEKeySpec(password.toCharArray(), saltBytes, iterations, keyLength)
    val secretKeyFactory = SecretKeyFactory.getInstance(Algorithm.PBKDF2WithHmacSHA1.value)
    val hashedPasswordBytes = secretKeyFactory.generateSecret(keySpec).encoded
    return Base64.getEncoder().encodeToString(hashedPasswordBytes)
}

enum class Algorithm(val value: String) {
    SHA1PRNG("SHA1PRNG"),
    PBKDF2WithHmacSHA1("PBKDF2WithHmacSHA1")
}