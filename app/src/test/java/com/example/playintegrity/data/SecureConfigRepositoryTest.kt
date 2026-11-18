package com.example.playintegrity.data

import com.example.playintegrity.cache.ConfigCache
import com.example.playintegrity.integrity.ConfigRequest
import com.example.playintegrity.integrity.ExponentialBackoff
import com.example.playintegrity.integrity.IntegrityTokenException
import com.example.playintegrity.integrity.IntegrityTokenSource
import com.example.playintegrity.integrity.RequestHasher
import com.example.playintegrity.nativebridge.NativeConfigSink
import com.example.playintegrity.network.ConfigApi
import com.example.playintegrity.network.ConfigApiResult
import com.example.playintegrity.network.ConnectivityMonitor
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Exercises every client-side branch of the flowchart with fakes (no device needed). */
class SecureConfigRepositoryTest {

    private var now = 1_000_000L
    private val config = SecureConfig(mapOf("nativeApiKey" to "k"), issuedAtMillis = now, ttlSeconds = 60, cacheable = true)

    private val tokens = FakeTokenSource()
    private val api = FakeApi()
    private val cache = FakeCache()
    private val net = FakeConnectivity()
    private val native = FakeNative()

    private fun repo() = SecureConfigRepository(
        tokenSource = tokens, api = api, cache = cache, connectivity = net, nativeSink = native,
        packageName = "com.example.playintegrity", appVersionCode = 1,
        backoff = ExponentialBackoff(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 20),
        clock = { now },
    )

    @Test
    fun providerInitFails_returnsInitError() = runTest {
        tokens.prepareError = IntegrityTokenException(StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND, "no gms")
        val out = repo().load()
        assertTrue(out is LoadOutcome.InitError)
        assertEquals(0, api.calls)
    }

    @Test
    fun validCache_isUsedWithoutNetworkOrToken() = runTest {
        cache.stored = config
        val out = repo().load()
        assertEquals(LoadOutcome.Ready.Source.CACHE, (out as LoadOutcome.Ready).source)
        assertEquals(0, tokens.requests)
        assertEquals(1, native.pushes)
    }

    @Test
    fun expiredCache_fetchesFresh() = runTest {
        cache.stored = config
        now += 61_000
        api.result = ConfigApiResult.Success(config.copy(issuedAtMillis = now))
        val out = repo().load()
        assertEquals(LoadOutcome.Ready.Source.NETWORK, (out as LoadOutcome.Ready).source)
    }

    @Test
    fun offline_returnsNetworkUnavailable() = runTest {
        net.online = false
        assertEquals(LoadOutcome.NetworkUnavailable, repo().load())
        assertEquals(0, tokens.requests)
    }

    @Test
    fun tokenIsBoundToTheExactRequestSent() = runTest {
        api.result = ConfigApiResult.Success(config)
        repo().load()
        assertEquals(RequestHasher.requestHash(api.lastRequest!!), tokens.lastHash)
    }

    @Test
    fun transientError_retriesWithBackoffThenSucceeds() = runTest {
        tokens.failures += IntegrityTokenException(StandardIntegrityErrorCode.NETWORK_ERROR, "net")
        tokens.failures += IntegrityTokenException(StandardIntegrityErrorCode.TOO_MANY_REQUESTS, "slow down")
        api.result = ConfigApiResult.Success(config)
        val out = repo().load()
        assertTrue(out is LoadOutcome.Ready)
        assertEquals(3, tokens.requests)
    }

    @Test
    fun transientError_retryLimitReached_returnsTemporaryError() = runTest {
        repeat(5) { tokens.failures += IntegrityTokenException(StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE, "down") }
        val out = repo().load()
        assertTrue(out is LoadOutcome.TemporaryError)
        assertEquals(3, tokens.requests)
        assertEquals(0, api.calls)
    }

    @Test
    fun nonTransientError_returnsIntegrityErrorImmediately() = runTest {
        tokens.failures += IntegrityTokenException(StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID, "bad")
        val out = repo().load()
        assertTrue(out is LoadOutcome.IntegrityError)
        assertEquals(1, tokens.requests)
    }

    @Test
    fun providerInvalid_rePreparesAndRetries() = runTest {
        tokens.failures += IntegrityTokenException(StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID, "expired")
        api.result = ConfigApiResult.Success(config)
        assertTrue(repo().load() is LoadOutcome.Ready)
        assertEquals(2, tokens.prepares)
    }

    @Test
    fun serverRejects403_blocksAndWipesEverything() = runTest {
        cache.stored = config.copy(issuedAtMillis = 0) // expired, forces network
        api.result = ConfigApiResult.Rejected("DEVICE_UNTRUSTED", "nope")
        val r = repo()
        val out = r.load()
        assertEquals("DEVICE_UNTRUSTED", (out as LoadOutcome.Blocked).serverCode)
        assertNull(cache.stored)
        assertNull(r.current)
        assertEquals(1, native.clears)
    }

    @Test
    fun server429_returnsTemporaryErrorWithRetryAfter() = runTest {
        api.result = ConfigApiResult.Temporary(429, "RATE_LIMITED", 30)
        val out = repo().load() as LoadOutcome.TemporaryError
        assertEquals(30L, out.retryAfterSeconds)
    }

    @Test
    fun server400_isNotRetried() = runTest {
        api.result = ConfigApiResult.BadRequest("BAD_REQUEST", "x")
        assertTrue(repo().load() is LoadOutcome.IntegrityError)
        assertEquals(1, api.calls)
    }

    @Test
    fun success_processesInMemory_pushesNative_andCachesWhenAllowed() = runTest {
        api.result = ConfigApiResult.Success(config)
        val r = repo()
        r.load()
        assertEquals(config, r.current)
        assertEquals(1, native.pushes)
        assertEquals(config, cache.stored)
    }

    @Test
    fun success_notCacheable_clearsCache() = runTest {
        cache.stored = config.copy(issuedAtMillis = 0)
        api.result = ConfigApiResult.Success(config.copy(cacheable = false))
        repo().load(forceRefresh = true)
        assertNull(cache.stored)
    }

    @Test
    fun networkDropDuringPost_mapsToNetworkUnavailable() = runTest {
        api.onCall = { net.online = false }
        api.result = ConfigApiResult.NetworkFailure(IOException("reset"))
        assertEquals(LoadOutcome.NetworkUnavailable, repo().load())
    }

    // ---------------- fakes ----------------

    private class FakeTokenSource : IntegrityTokenSource {
        var prepareError: IntegrityTokenException? = null
        val failures = ArrayDeque<IntegrityTokenException>()
        var prepares = 0
        var requests = 0
        var lastHash: String? = null
        override var isPrepared = false

        override suspend fun prepare() {
            prepares++
            prepareError?.let { throw it }
            isPrepared = true
        }

        override suspend fun requestToken(requestHash: String): String {
            requests++
            lastHash = requestHash
            failures.removeFirstOrNull()?.let { throw it }
            return "token-$requests"
        }
    }

    private class FakeApi : ConfigApi {
        var result: ConfigApiResult = ConfigApiResult.Temporary(503, "UNSET", null)
        var calls = 0
        var lastRequest: ConfigRequest? = null
        var onCall: () -> Unit = {}
        override suspend fun fetchConfig(request: ConfigRequest, integrityToken: String): ConfigApiResult {
            calls++
            lastRequest = request
            onCall()
            return result
        }
    }

    private class FakeCache : ConfigCache {
        var stored: SecureConfig? = null
        override fun read() = stored
        override fun write(config: SecureConfig) { stored = config }
        override fun clear() { stored = null }
    }

    private class FakeConnectivity : ConnectivityMonitor {
        var online = true
        override fun isOnline() = online
        override suspend fun awaitOnline() {}
    }

    private class FakeNative : NativeConfigSink {
        var pushes = 0
        var clears = 0
        override fun push(values: Map<String, String>) { pushes++ }
        override fun clear() { clears++ }
        override fun count() = 0
    }
}
