package com.qimingnan.couple.backend.auth

import com.qimingnan.couple.backend.common.Crypto
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.util.Base64

/**
 * Argon2id via Bouncy Castle (pure Java, no native lib), encoded in PHC string format:
 * $argon2id$v=19$m=19456,t=2,p=1$<salt b64>$<hash b64>  (OWASP minimum recommendation).
 */
class PasswordHasher(
    private val memoryKiB: Int = 19456,
    private val iterations: Int = 2,
    private val parallelism: Int = 1,
) {
    private val b64e = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = Crypto.randomBytes(16)
        val out = derive(password, salt, memoryKiB, iterations, parallelism, 32)
        return "\$argon2id\$v=19\$m=$memoryKiB,t=$iterations,p=$parallelism\$${b64e.encodeToString(salt)}\$${b64e.encodeToString(out)}"
    }

    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').associate { kv -> kv.substringBefore('=') to kv.substringAfter('=').toInt() }
        val salt = b64d.decode(parts[4])
        val expected = b64d.decode(parts[5])
        val actual = derive(password, salt, params.getValue("m"), params.getValue("t"), params.getValue("p"), expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, salt: ByteArray, m: Int, t: Int, p: Int, len: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(m)
            .withIterations(t)
            .withParallelism(p)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(len)
        gen.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }
}
