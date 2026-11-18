// Holds integrity-gated configuration in native memory only.
// Values are never written to disk from here and are wiped on clear().
#include <jni.h>
#include <string>
#include <map>
#include <mutex>
#include <android/log.h>

#define TAG "SecureConfigNative"

namespace {
std::mutex gLock;
std::map<std::string, std::string> gValues;

std::string toStd(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void secureWipe(std::string &s) {
    volatile char *p = s.empty() ? nullptr : &s[0];
    for (size_t i = 0; i < s.size(); ++i) p[i] = 0;
    s.clear();
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_example_playintegrity_nativebridge_NativeBridge_nativeSetValue(
        JNIEnv *env, jobject, jstring key, jstring value) {
    std::lock_guard<std::mutex> guard(gLock);
    std::string k = toStd(env, key);
    auto it = gValues.find(k);
    if (it != gValues.end()) secureWipe(it->second);
    gValues[k] = toStd(env, value);
    // Log only the key, never the value.
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "stored key=%s", k.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_playintegrity_nativebridge_NativeBridge_nativeHasValue(
        JNIEnv *env, jobject, jstring key) {
    std::lock_guard<std::mutex> guard(gLock);
    return gValues.count(toStd(env, key)) > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_playintegrity_nativebridge_NativeBridge_nativeCount(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(gLock);
    return static_cast<jint>(gValues.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_playintegrity_nativebridge_NativeBridge_nativeClear(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(gLock);
    for (auto &kv : gValues) secureWipe(kv.second);
    gValues.clear();
}
