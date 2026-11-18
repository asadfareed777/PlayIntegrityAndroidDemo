package com.example.playintegrity.data

import com.example.playintegrity.cache.ConfigCache
import com.example.playintegrity.integrity.ConfigRequest
import com.example.playintegrity.integrity.ExponentialBackoff
import com.example.playintegrity.integrity.IntegrityErrorClassifier
import com.example.playintegrity.integrity.IntegrityErrorKind
import com.example.playintegrity.integrity.IntegrityTokenException
import com.example.playintegrity.integrity.IntegrityTokenSource
import com.example.playintegrity.integrity.RequestHasher
import com.example.playintegrity.nativebridge.NativeConfigSink
import com.example.playintegrity.network.ConfigApi
import com.example.playintegrity.network.ConfigApiResult
import com.example.playintegrity.network.ConnectivityMonitor
import kotlinx.coroutines.delay

/** Every terminal box of the flowchart maps to exactly one outcome. */
sealed interface LoadOutcome {
    /** "Continue Application" — sensitive features enabled. */
    data class Ready(val config: SecureConfig, val source: Source) : LoadOutcome {
        enum class Source { CACHE, NETWORK }
    }

    /** "Show Play Services / Initialization Error" → retry or block. */
    data class InitError(val errorCode: Int, val message: String) : LoadOutcome

    /** "Show Network Error" → retry when online. */
    data object NetworkUnavailable : LoadOutcome

    /** "Show Temporary Error" (retry limit reached) / 408 / 429 / 503 → retry later. */
    data class TemporaryError(val message: String, val retryAfterSeconds: Long? = null) : LoadOutcome

    /** "Show Play Integrity Error" → block sensitive features (non-transient client error or 400). */
    data class IntegrityError(val errorCode: Int?, val message: String) : LoadOutcome

    /** Any 403 from the backend → block sensitive features. */
    data class Blocked(val serverCode: String, val message: String) : LoadOutcome
}

/** Progress callbacks so the UI can show where in the flow we are. */
fun interface FlowListener {
    fun onStep(step: String)
}

/**
 * Implements the "Application Launch → Continue Application" flowchart.
 *
 * Client-side steps:
 *  1. Initialize Standard API provider (prepareIntegrityToken).
 *  2. Use cached config if present and unexpired.
 *  3. Otherwise: check connectivity, generate requestId, build canonical payload,
 *     compute SHA-256 requestHash, request token (backoff on transient errors),
 *     POST to backend, and react to its verdict.
 *  4. On success: process config in memory, push native values, optionally cache encrypted.
 */
class SecureConfigRepository(
    private val tokenSource: IntegrityTokenSource,
    private val api: ConfigApi,
    private val cache: ConfigCache,
    private val connectivity: ConnectivityMonitor,
    private val nativeSink: NativeConfigSink,
    private val packageName: String,
    private val appVersionCode: Long,
    private val configScope: String = "default",
    private val backoff: ExponentialBackoff = ExponentialBackoff(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestIdFactory: () -> String = RequestHasher::newRequestId,
) {
    /** "Process Configuration in Memory": the single in-memory copy used by the app. */
    @Volatile
    var current: SecureConfig? = null
        private set

    suspend fun load(forceRefresh: Boolean = false, listener: FlowListener = FlowListener { }): LoadOutcome {
        // ---- Initialize Play Integrity Standard API / Provider initialized? ----
        if (!tokenSource.isPrepared) {
            listener.onStep("Preparing integrity token provider")
            try {
                tokenSource.prepare()
            } catch (e: IntegrityTokenException) {
                return LoadOutcome.InitError(e.errorCode, e.message ?: "Initialization failed")
            }
        }

        // ---- Valid cached configuration? / Configuration expired? ----
        if (!forceRefresh) {
            val cached = cache.read()
            if (cached != null && !cached.isExpired(clock())) {
                listener.onStep("Using cached configuration")
                return deliver(cached, LoadOutcome.Ready.Source.CACHE, persist = false)
            }
            if (cached != null) listener.onStep("Cached configuration expired")
        }

        // ---- Fetch fresh configuration ----
        return fetchFresh(listener)
    }

    private suspend fun fetchFresh(listener: FlowListener): LoadOutcome {
        var attempt = 0
        while (true) {
            // ---- Internet available? ----
            if (!connectivity.isOnline()) return LoadOutcome.NetworkUnavailable

            // ---- Generate random request ID / canonical payload / SHA-256 requestHash ----
            listener.onStep("Building request (attempt ${attempt + 1})")
            val request = ConfigRequest(
                requestId = requestIdFactory(),
                timestampMillis = clock(),
                packageName = packageName,
                appVersionCode = appVersionCode,
                configScope = configScope,
            )
            val requestHash = RequestHasher.requestHash(request)

            // ---- Request standard integrity token / Token generated? ----
            listener.onStep("Requesting integrity token")
            val token = try {
                tokenSource.requestToken(requestHash)
            } catch (e: IntegrityTokenException) {
                when (IntegrityErrorClassifier.classify(e.errorCode)) {
                    IntegrityErrorKind.FATAL ->
                        return LoadOutcome.IntegrityError(e.errorCode, e.message ?: "Integrity error")

                    IntegrityErrorKind.PROVIDER_INVALID -> {
                        listener.onStep("Provider invalid, re-preparing")
                        try {
                            tokenSource.prepare()
                        } catch (pe: IntegrityTokenException) {
                            return LoadOutcome.InitError(pe.errorCode, pe.message ?: "Re-initialization failed")
                        }
                    }

                    IntegrityErrorKind.TRANSIENT -> Unit
                }
                // ---- Transient → retry with exponential backoff / retry limit reached ----
                attempt++
                if (!backoff.hasAttemptsLeft(attempt)) {
                    return LoadOutcome.TemporaryError("Integrity check temporarily unavailable: ${e.message}")
                }
                val wait = backoff.delayFor(attempt - 1)
                listener.onStep("Transient error (${e.errorCode}); retrying in ${wait}ms")
                delay(wait)
                continue
            }

            // ---- POST sensitive configuration API ----
            listener.onStep("Verifying with backend")
            return when (val result = api.fetchConfig(request, token)) {
                is ConfigApiResult.Success -> deliver(result.config, LoadOutcome.Ready.Source.NETWORK, persist = true)

                is ConfigApiResult.Rejected -> {
                    // Server says this app/device must not get sensitive config: drop anything we held.
                    wipe()
                    LoadOutcome.Blocked(result.code, result.message)
                }

                is ConfigApiResult.BadRequest ->
                    LoadOutcome.IntegrityError(null, "Request rejected (${result.code}): ${result.message}")

                is ConfigApiResult.Temporary ->
                    LoadOutcome.TemporaryError("Server busy (${result.httpStatus} ${result.code})", result.retryAfterSeconds)

                is ConfigApiResult.NetworkFailure ->
                    if (connectivity.isOnline()) {
                        LoadOutcome.TemporaryError("Could not reach server: ${result.cause.message}")
                    } else {
                        LoadOutcome.NetworkUnavailable
                    }
            }
        }
    }

    /** Process in memory → pass to native code → optionally cache (encrypted) → continue. */
    private fun deliver(config: SecureConfig, source: LoadOutcome.Ready.Source, persist: Boolean): LoadOutcome {
        current = config
        nativeSink.push(config.values)
        if (persist) {
            if (config.cacheable) cache.write(config) else cache.clear()
        }
        return LoadOutcome.Ready(config, source)
    }

    /** Remove every copy of the sensitive config (memory, native, disk). */
    fun wipe() {
        current = null
        nativeSink.clear()
        cache.clear()
    }
}
