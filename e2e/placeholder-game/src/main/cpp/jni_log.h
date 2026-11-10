// Call recording shared by the generated and hand-written JNI natives of the placeholder game.
//
// Every native the launcher (or the stub Java classes) calls into the "game" is logged as
//   jni <binary class name>.<method>(<args>)
// under the LeviPlaceholder tag, so the E2E runner can assert on the exact call sequence.
#pragma once

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstdio>
#include <string>

#define PH_TAG "LeviPlaceholder"
#define PH_LOGI(...) __android_log_print(ANDROID_LOG_INFO, PH_TAG, __VA_ARGS__)
#define PH_LOGW(...) __android_log_print(ANDROID_LOG_WARN, PH_TAG, __VA_ARGS__)
#define PH_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PH_TAG, __VA_ARGS__)

namespace ph {

// Total number of JNI natives called into this library; reported by the game heartbeat.
inline std::atomic<long long>& jniCallCount() {
    static std::atomic<long long> count{0};
    return count;
}

class JniCall {
public:
    JniCall(JNIEnv* env, const char* cls, const char* method) : env_(env) {
        text_.reserve(128);
        text_ += "jni ";
        text_ += cls;
        text_ += '.';
        text_ += method;
        text_ += '(';
    }

    void str(jstring value) {
        sep();
        if (value == nullptr) {
            text_ += "null";
            return;
        }
        const char* chars = env_->GetStringUTFChars(value, nullptr);
        if (chars == nullptr) {
            env_->ExceptionClear();
            text_ += "<unreadable>";
            return;
        }
        std::string s(chars);
        env_->ReleaseStringUTFChars(value, chars);
        if (s.size() > 120) s = s.substr(0, 117) + "...";
        text_ += '"';
        text_ += s;
        text_ += '"';
    }

    void boolean(jboolean value) {
        sep();
        text_ += value ? "true" : "false";
    }

    void integer(long long value) {
        sep();
        text_ += std::to_string(value);
    }

    void real(double value) {
        sep();
        char buf[32];
        std::snprintf(buf, sizeof(buf), "%g", value);
        text_ += buf;
    }

    void object(jobject value) {
        sep();
        text_ += value == nullptr ? "null" : "<obj>";
    }

    void emit() {
        text_ += ')';
        jniCallCount().fetch_add(1, std::memory_order_relaxed);
        PH_LOGI("%s", text_.c_str());
    }

private:
    void sep() {
        if (!first_) text_ += ", ";
        first_ = false;
    }

    JNIEnv* env_;
    std::string text_;
    bool first_ = true;
};

}  // namespace ph
