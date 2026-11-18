package com.example.playintegrity.integrity

import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode

/** Thrown by [IntegrityTokenSource] so callers never depend on Play classes directly. */
class IntegrityTokenException(
    val errorCode: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

enum class IntegrityErrorKind {
    /** Worth retrying with exponential backoff. */
    TRANSIENT,

    /** The warmed-up provider expired or became invalid: prepare a new one, then retry. */
    PROVIDER_INVALID,

    /** Retrying won't help (missing Play Store, bad cloud project number, ...). Block sensitive features. */
    FATAL,
}

object IntegrityErrorClassifier {

    fun classify(errorCode: Int): IntegrityErrorKind = when (errorCode) {
        StandardIntegrityErrorCode.NETWORK_ERROR,
        StandardIntegrityErrorCode.TOO_MANY_REQUESTS,
        StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
        StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
        StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
        StandardIntegrityErrorCode.INTERNAL_ERROR,
        -> IntegrityErrorKind.TRANSIENT

        StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID,
        -> IntegrityErrorKind.PROVIDER_INVALID

        else -> IntegrityErrorKind.FATAL
    }

    /** Human-readable hint shown to the user / logged for support. */
    fun describe(errorCode: Int): String = when (errorCode) {
        StandardIntegrityErrorCode.API_NOT_AVAILABLE -> "Play Integrity API not available. Update the Play Store."
        StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND -> "Google Play Store not found on this device."
        StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND -> "Google Play services not found on this device."
        StandardIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED -> "Google Play Store is outdated."
        StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> "Google Play services is outdated."
        StandardIntegrityErrorCode.APP_NOT_INSTALLED -> "Calling app is not installed."
        StandardIntegrityErrorCode.APP_UID_MISMATCH -> "Calling app UID mismatch."
        StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID -> "Invalid Cloud project number (check build config)."
        StandardIntegrityErrorCode.REQUEST_HASH_TOO_LONG -> "requestHash is too long."
        StandardIntegrityErrorCode.NETWORK_ERROR -> "Network error while contacting Google Play."
        StandardIntegrityErrorCode.TOO_MANY_REQUESTS -> "Too many integrity requests; backing off."
        StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE -> "Google server temporarily unavailable."
        StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR -> "Transient client error."
        StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE -> "Could not bind to the Play Store service."
        StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID -> "Integrity token provider expired."
        StandardIntegrityErrorCode.INTERNAL_ERROR -> "Unknown internal Play error."
        else -> "Play Integrity error $errorCode."
    }
}
