package com.example.playintegrity.integrity

import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class IntegrityErrorClassifierTest {

    @Test
    fun transientCodes_areRetried() {
        listOf(
            StandardIntegrityErrorCode.NETWORK_ERROR,
            StandardIntegrityErrorCode.TOO_MANY_REQUESTS,
            StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
            StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
            StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            StandardIntegrityErrorCode.INTERNAL_ERROR,
        ).forEach { assertEquals("code $it", IntegrityErrorKind.TRANSIENT, IntegrityErrorClassifier.classify(it)) }
    }

    @Test
    fun providerInvalid_triggersRePrepare() {
        assertEquals(
            IntegrityErrorKind.PROVIDER_INVALID,
            IntegrityErrorClassifier.classify(StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID),
        )
    }

    @Test
    fun configurationAndEnvironmentErrors_areFatal() {
        listOf(
            StandardIntegrityErrorCode.API_NOT_AVAILABLE,
            StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND,
            StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
            StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
            StandardIntegrityErrorCode.REQUEST_HASH_TOO_LONG,
            StandardIntegrityErrorCode.APP_UID_MISMATCH,
        ).forEach { assertEquals("code $it", IntegrityErrorKind.FATAL, IntegrityErrorClassifier.classify(it)) }
    }

    @Test
    fun backoff_ceilingDoublesAndCaps() {
        val b = ExponentialBackoff(maxAttempts = 4, baseDelayMs = 500, maxDelayMs = 3000)
        assertEquals(500, b.ceilingFor(0))
        assertEquals(1000, b.ceilingFor(1))
        assertEquals(2000, b.ceilingFor(2))
        assertEquals(3000, b.ceilingFor(3))
        assertEquals(3000, b.ceilingFor(30))
    }

    @Test
    fun backoff_delayWithinCeiling_andAttemptLimit() {
        val b = ExponentialBackoff(maxAttempts = 3, random = Random(42))
        repeat(100) { attempt -> assertTrue(b.delayFor(attempt % 5) in 0..b.ceilingFor(attempt % 5)) }
        assertTrue(b.hasAttemptsLeft(2))
        assertFalse(b.hasAttemptsLeft(3))
    }
}
