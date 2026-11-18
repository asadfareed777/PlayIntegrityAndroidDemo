package com.example.playintegrity.network

import com.example.playintegrity.data.SecureConfig
import com.example.playintegrity.integrity.ConfigRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of POST /v1/config, mapped to the branches of the integrity flow. */
sealed interface ConfigApiResult {
    data class Success(val config: SecureConfig) : ConfigApiResult

    /** 403: server rejected the verdict. [code] is e.g. DEVICE_UNTRUSTED, APP_NOT_RECOGNIZED. */
    data class Rejected(val code: String, val message: String) : ConfigApiResult

    /** 400: malformed request. A client bug - don't retry. */
    data class BadRequest(val code: String, val message: String) : ConfigApiResult

    /** 408 / 429 / 503 / other 5xx: try again later. */
    data class Temporary(val httpStatus: Int, val code: String, val retryAfterSeconds: Long?) : ConfigApiResult

    /** Socket / DNS / timeout failures. */
    data class NetworkFailure(val cause: IOException) : ConfigApiResult
}

interface ConfigApi {
    suspend fun fetchConfig(request: ConfigRequest, integrityToken: String): ConfigApiResult
}

class OkHttpConfigApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) : ConfigApi {

    private val endpoint = baseUrl.trimEnd('/') + "/v1/config"

    override suspend fun fetchConfig(request: ConfigRequest, integrityToken: String): ConfigApiResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("requestId", request.requestId)
                .put("timestampMillis", request.timestampMillis)
                .put("packageName", request.packageName)
                .put("appVersionCode", request.appVersionCode)
                .put("configScope", request.configScope)
                .put("integrityToken", integrityToken)
                .toString()

            val httpRequest = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody(JSON))
                .header("Accept", "application/json")
                .build()

            try {
                client.newCall(httpRequest).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    val code = json?.optString("code").orEmpty().ifEmpty { "HTTP_${response.code}" }
                    val message = json?.optString("message").orEmpty()
                    when {
                        response.isSuccessful && json != null ->
                            ConfigApiResult.Success(SecureConfig.fromJson(json.getJSONObject("config")))
                        response.code == 403 -> ConfigApiResult.Rejected(code, message)
                        response.code == 400 -> ConfigApiResult.BadRequest(code, message)
                        else -> ConfigApiResult.Temporary(
                            httpStatus = response.code,
                            code = code,
                            retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
                        )
                    }
                }
            } catch (e: IOException) {
                ConfigApiResult.NetworkFailure(e)
            } catch (e: org.json.JSONException) {
                ConfigApiResult.Temporary(502, "MALFORMED_RESPONSE", null)
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            // TODO(prod): add certificate pinning for your backend host via CertificatePinner.
            .build()
    }
}
