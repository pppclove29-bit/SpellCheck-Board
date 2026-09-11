package com.typeright.keyboard.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OIDC nonce for Google Sign-In → Supabase: Google receives the SHA-256 hex digest of the raw nonce (it ends up in the
 * ID token's `nonce` claim), Supabase receives the raw nonce and verifies that its hash matches the claim.
 */
object Nonce {
    fun generateRaw(random: SecureRandom = SecureRandom(), bytes: Int = 32): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.toHex()
    }

    fun sha256Hex(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
