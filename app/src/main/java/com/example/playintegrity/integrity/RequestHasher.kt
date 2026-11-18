package com.example.playintegrity.integrity

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The body the app POSTs to the backend. Every field here is bound into the
 * Play Integrity `requestHash`, so the server can recompute the hash from the
 * body it receives and reject any token that was minted for a different request.
 */
data class ConfigRequest(
    val requestId: String,
    val timestampMillis: Long,
    val packageName: String,
    val appVersionCode: Long,
    val configScope: String,
)

/**
 * Builds the canonical request string and its SHA-256 `requestHash`.
 *
 * The canonical format MUST stay byte-for-byte identical to
 * `server/src/integrity/requestHash.js`. Change both together and bump [VERSION].
 *
 * Format (fields joined with '\n'):
 *   v1
 *   POST
 *   /v1/config
 *   <requestId>
 *   <timestampMillis>
 *   <packageName>
 *   <appVersionCode>
 *   <configScope>
 */
object RequestHasher {
    const val VERSION = "v1"
    const val METHOD = "POST"
    const val PATH = "/v1/config"

    private val random = SecureRandom()

    /** 128-bit random request id, hex encoded (32 chars). Used once, then discarded. */
    fun newRequestId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    fun canonicalPayload(request: ConfigRequest): String = listOf(
        VERSION,
        METHOD,
        PATH,
        request.requestId,
        request.timestampMillis.toString(),
        request.packageName,
        request.appVersionCode.toString(),
        request.configScope,
    ).joinToString("\n")

    /** Lower-case hex SHA-256 (64 chars, well under Play's 500-char requestHash limit). */
    fun requestHash(request: ConfigRequest): String = sha256Hex(canonicalPayload(request))

    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return out.toString()
    }
}
