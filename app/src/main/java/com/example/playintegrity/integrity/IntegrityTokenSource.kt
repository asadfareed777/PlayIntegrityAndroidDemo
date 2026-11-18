package com.example.playintegrity.integrity

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Abstraction over the Play Integrity Standard API so the flow can be unit-tested on the JVM. */
interface IntegrityTokenSource {
    val isPrepared: Boolean

    /** Warms up the token provider. Throws [IntegrityTokenException] on failure. */
    suspend fun prepare()

    /** Requests a standard integrity token bound to [requestHash]. Throws [IntegrityTokenException]. */
    suspend fun requestToken(requestHash: String): String
}

/**
 * Play Integrity **Standard** API implementation.
 *
 * - `prepareIntegrityToken` is called once at launch (it can take a few seconds) and the
 *   resulting provider is reused for every request.
 * - If the provider becomes invalid (INTEGRITY_TOKEN_PROVIDER_INVALID) the caller
 *   re-runs [prepare] and retries.
 */
class PlayIntegrityTokenSource(
    context: Context,
    private val cloudProjectNumber: Long,
) : IntegrityTokenSource {

    private val manager: StandardIntegrityManager =
        IntegrityManagerFactory.createStandard(context.applicationContext)
    private val mutex = Mutex()

    @Volatile
    private var provider: StandardIntegrityTokenProvider? = null

    override val isPrepared: Boolean get() = provider != null

    override suspend fun prepare() {
        mutex.withLock { prepareLocked() }
    }

    private suspend fun prepareLocked() {
        provider = null
        try {
            provider = manager.prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build()
            ).await()
        } catch (e: StandardIntegrityException) {
            throw IntegrityTokenException(e.errorCode, IntegrityErrorClassifier.describe(e.errorCode), e)
        } catch (e: Exception) {
            throw IntegrityTokenException(
                StandardIntegrityErrorCode.INTERNAL_ERROR,
                e.message ?: "Provider preparation failed",
                e,
            )
        }
    }

    override suspend fun requestToken(requestHash: String): String {
        val current = provider ?: throw IntegrityTokenException(
            StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID,
            "Provider not prepared",
        )
        return try {
            current.request(
                StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build()
            ).await().token()
        } catch (e: StandardIntegrityException) {
            throw IntegrityTokenException(e.errorCode, IntegrityErrorClassifier.describe(e.errorCode), e)
        } catch (e: Exception) {
            throw IntegrityTokenException(
                StandardIntegrityErrorCode.INTERNAL_ERROR,
                e.message ?: "Token request failed",
                e,
            )
        }
    }
}
