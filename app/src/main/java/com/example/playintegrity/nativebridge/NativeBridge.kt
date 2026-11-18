package com.example.playintegrity.nativebridge

/** Receives the verified configuration values. Abstracted so the flow can be unit-tested. */
interface NativeConfigSink {
    fun push(values: Map<String, String>)
    fun clear()
    fun count(): Int
}

/**
 * JNI bridge to `libsecureconfig.so` (app/src/main/cpp/secure_config.cpp).
 * Values pushed here live only in native memory and are wiped on [clear].
 */
object NativeBridge : NativeConfigSink {

    init {
        System.loadLibrary("secureconfig")
    }

    /** Only these keys are forwarded to native code; everything else stays in Kotlin memory. */
    val NATIVE_KEYS = setOf("nativeApiKey", "nativeEndpoint", "nativeFeatureSalt")

    override fun push(values: Map<String, String>) {
        nativeClear()
        values.filterKeys { it in NATIVE_KEYS }.forEach { (k, v) -> nativeSetValue(k, v) }
    }

    override fun clear() = nativeClear()

    override fun count(): Int = nativeCount()

    fun has(key: String): Boolean = nativeHasValue(key)

    private external fun nativeSetValue(key: String, value: String)
    private external fun nativeHasValue(key: String): Boolean
    private external fun nativeCount(): Int
    private external fun nativeClear()
}
