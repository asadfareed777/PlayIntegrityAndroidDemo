package com.example.playintegrity.data

import org.json.JSONObject

/**
 * Sensitive configuration returned by the backend only after a passing integrity verdict.
 * Keep it in memory; persist only through [com.example.playintegrity.cache.ConfigCache] (encrypted).
 */
data class SecureConfig(
    val values: Map<String, String>,
    val issuedAtMillis: Long,
    val ttlSeconds: Long,
    val cacheable: Boolean,
) {
    fun expiresAtMillis(): Long = issuedAtMillis + ttlSeconds * 1000

    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis()

    fun toJson(): JSONObject = JSONObject()
        .put("values", JSONObject(values as Map<*, *>))
        .put("issuedAt", issuedAtMillis)
        .put("ttlSeconds", ttlSeconds)
        .put("cacheable", cacheable)

    companion object {
        /** Parses `{ values: {..}, issuedAt, ttlSeconds, cacheable }`. */
        fun fromJson(json: JSONObject): SecureConfig {
            val valuesJson = json.getJSONObject("values")
            val values = buildMap {
                valuesJson.keys().forEach { key -> put(key, valuesJson.get(key).toString()) }
            }
            return SecureConfig(
                values = values,
                issuedAtMillis = json.getLong("issuedAt"),
                ttlSeconds = json.getLong("ttlSeconds"),
                cacheable = json.optBoolean("cacheable", false),
            )
        }
    }
}
