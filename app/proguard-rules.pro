# Keep the JNI bridge so native symbol names stay stable after minification.
-keep class com.example.playintegrity.nativebridge.NativeBridge {
    native <methods>;
}
