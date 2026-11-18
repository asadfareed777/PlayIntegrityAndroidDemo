package com.example.playintegrity.integrity

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with "full jitter".
 * attempt 0 -> [0, base], attempt 1 -> [0, 2*base], ... capped at [maxDelayMs].
 */
class ExponentialBackoff(
    val maxAttempts: Int = 4,
    private val baseDelayMs: Long = 500,
    private val maxDelayMs: Long = 8_000,
    private val random: Random = Random.Default,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /** Upper bound of the delay before retry number [attempt] (0-based). */
    fun ceilingFor(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        return min(maxDelayMs, baseDelayMs shl shift)
    }

    fun delayFor(attempt: Int): Long = random.nextLong(0, ceilingFor(attempt) + 1)

    fun hasAttemptsLeft(attemptsMade: Int): Boolean = attemptsMade < maxAttempts
}
