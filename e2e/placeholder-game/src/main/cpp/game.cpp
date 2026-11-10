// Placeholder for libminecraftpe.so: a minimal GameActivity game with the real game's JNI surface.
//
// The launcher loads this library in place of Minecraft's, after which GameActivity resolves
// Java_com_google_androidgamesdk_GameActivity_initializeNativeCode from it (linked in from the
// same games-activity version the launcher ships) and starts android_main below on its own thread.
//
// What it does, all logged under the LeviPlaceholder tag for the E2E runner:
//  - "lib loaded" when the library is loaded, with the version it was built as;
//  - calls back into the launcher's stub MainActivity getters the way the real game does at
//    startup ("probe <name> -> <value>", or "probe FAIL ..." if a method is missing or throws);
//  - renders frames with EGL/GLES2 while it has a window and is resumed ("frame first", "frame N");
//  - logs lifecycle commands ("cmd RESUME"), input it receives and a heartbeat every two seconds;
//  - implements the stateful natives (resize, suspend/stop/destroy/shutdown, key handler, text box,
//    network, headphones) by hand; every other native is generated (generated/jni_minecraftpe.cpp).
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <game-activity/GameActivity.h>
#include <game-activity/native_app_glue/android_native_app_glue.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <mutex>
#include <string>

#include "jni_log.h"

#ifndef PH_GAME_VERSION
#error "PH_GAME_VERSION must be defined by the build"
#endif

namespace {

struct GameState {
    std::atomic<int> width{0};
    std::atomic<int> height{0};
    std::atomic<bool> headphones{false};
    std::atomic<int> suspendCount{0};
    std::atomic<int> stopCount{0};
    std::atomic<bool> destroyed{false};
    std::atomic<bool> shutdown{false};
    std::atomic<int> keyHandlerCalls{0};
    std::mutex textMutex;
    std::string textbox;
};

GameState g_state;

__attribute__((constructor)) void onLibraryLoaded() {
    PH_LOGI("lib loaded libminecraftpe.so version=%s", PH_GAME_VERSION);
}

}  // namespace

// ---------------------------------------------------------------------------------------------
// Hand-written natives (listed in OVERRIDES of tools/gen_jni.py).
// ---------------------------------------------------------------------------------------------
extern "C" {

JNIEXPORT void JNICALL Java_com_mojang_minecraftpe_MainActivity_nativeResize(JNIEnv* env, jobject, jint width, jint height) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "nativeResize");
    log.integer(width);
    log.integer(height);
    log.emit();
    g_state.width = width;
    g_state.height = height;
}

JNIEXPORT void JNICALL Java_com_mojang_minecraftpe_MainActivity_nativeSuspend(JNIEnv* env, jobject) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "nativeSuspend");
    log.emit();
    g_state.suspendCount++;
}

JNIEXPORT void JNICALL Java_com_mojang_minecraftpe_MainActivity_nativeStopThis(JNIEnv* env, jobject) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "nativeStopThis");
    log.emit();
    g_state.stopCount++;
}

JNIEXPORT void JNICALL Java_com_mojang_minecraftpe_MainActivity_nativeOnDestroy(JNIEnv* env, jobject) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "nativeOnDestroy");
    log.emit();
    g_state.destroyed = true;
}

JNIEXPORT void JNICALL Java_com_mojang_minecraftpe_MainActivity_nativeShutdown(JNIEnv* env, jobject) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "nativeShutdown");
    log.emit();
    g_state.shutdown = true;
}

// The game does not consume keys through this path; returning false lets the launcher handle them.
JNIEXPORT jboolean JNICALL Java_com_mojang_minecraftpe_MainActivity_nativeKeyHandler(JNIEnv* env, jobject, jint keycode, jint action, jint source) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "nativeKeyHandler");
    log.integer(keycode);
    log.integer(action);
    log.integer(source);
    log.emit();
    g_state.keyHandlerCalls++;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_mojang_minecraftpe_MainActivity_nativeSetTextboxText(JNIEnv* env, jobject, jstring text, jint begin, jint end) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "nativeSetTextboxText");
    log.str(text);
    log.integer(begin);
    log.integer(end);
    log.emit();
    std::string value;
    if (text != nullptr) {
        const char* chars = env->GetStringUTFChars(text, nullptr);
        if (chars != nullptr) {
            value = chars;
            env->ReleaseStringUTFChars(text, chars);
        }
    }
    std::lock_guard<std::mutex> lock(g_state.textMutex);
    g_state.textbox = value;
}

JNIEXPORT void JNICALL Java_com_mojang_minecraftpe_MainActivity_nativeSetHeadphonesConnected(JNIEnv* env, jobject, jboolean connected) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "nativeSetHeadphonesConnected");
    log.boolean(connected);
    log.emit();
    g_state.headphones = connected == JNI_TRUE;
}

// A stable identifier, like the real game's per-install device id.
JNIEXPORT jstring JNICALL Java_com_mojang_minecraftpe_MainActivity_nativeGetDeviceId(JNIEnv* env, jobject) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "nativeGetDeviceId");
    log.emit();
    return env->NewStringUTF("00000000-0000-4000-8000-0000000e2e00");
}

// Behaves like a retail (publish) build: no test infrastructure.
JNIEXPORT jboolean JNICALL Java_com_mojang_minecraftpe_MainActivity_isPublishBuild(JNIEnv* env, jobject) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "isPublishBuild");
    log.emit();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_mojang_minecraftpe_MainActivity_isTestInfrastructureDisabled(JNIEnv* env, jobject) {
    ph::JniCall log(env, "com.mojang.minecraftpe.MainActivity", "isTestInfrastructureDisabled");
    log.emit();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_mojang_minecraftpe_NetworkMonitor_nativeUpdateNetworkStatus(JNIEnv* env, jobject, jboolean cable, jboolean wifi, jboolean misc) {
    ph::JniCall log(env, "com.mojang.minecraftpe.NetworkMonitor", "nativeUpdateNetworkStatus");
    log.boolean(cable);
    log.boolean(wifi);
    log.boolean(misc);
    log.emit();
}

}  // extern "C"

// ---------------------------------------------------------------------------------------------
// Startup probes: Java methods on the launcher's MainActivity that the game calls at startup.
// ---------------------------------------------------------------------------------------------
namespace {

struct Probe {
    const char* name;
    const char* signature;
    bool isStatic;
};

constexpr Probe kStartupProbes[] = {
    {"getAndroidVersion", "()I", false},
    {"getDeviceModel", "()Ljava/lang/String;", false},
    {"getLocale", "()Ljava/lang/String;", false},
    {"getExternalStoragePath", "()Ljava/lang/String;", false},
    {"getInternalStoragePath", "()Ljava/lang/String;", false},
    {"getObbDirPath", "()Ljava/lang/String;", false},
    {"getScreenWidth", "()I", false},
    {"getScreenHeight", "()I", false},
    {"getDisplayWidth", "()I", false},
    {"getDisplayHeight", "()I", false},
    {"getPixelsPerMillimeter", "()F", false},
    {"getPlatformDpi", "()I", false},
    {"getTotalMemory", "()J", false},
    {"getFreeMemory", "()J", false},
    {"getUsedMemory", "()J", false},
    {"getMemoryLimit", "()J", false},
    {"getLowMemoryThreshold", "()J", false},
    {"hasWriteExternalStoragePermission", "()Z", false},
    {"hasHardwareKeyboard", "()Z", false},
    {"checkLicense", "()I", false},
    {"createUUID", "()Ljava/lang/String;", false},
    {"getTimeFromProcessStart", "()J", false},
    {"isOnWifi", "()Z", false},
    {"getKeyboardHeight", "()F", false},
    {"getIPAddresses", "()[Ljava/lang/String;", false},
    {"isPowerVR", "()Z", true},
};

std::string describeException(JNIEnv* env) {
    jthrowable error = env->ExceptionOccurred();
    env->ExceptionClear();
    if (error == nullptr) return "unknown";
    jclass throwable = env->FindClass("java/lang/Throwable");
    jmethodID toString = env->GetMethodID(throwable, "toString", "()Ljava/lang/String;");
    auto text = static_cast<jstring>(env->CallObjectMethod(error, toString));
    std::string result = "unknown";
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (text != nullptr) {
        const char* chars = env->GetStringUTFChars(text, nullptr);
        result = chars;
        env->ReleaseStringUTFChars(text, chars);
    }
    return result;
}

std::string javaString(JNIEnv* env, jobject value) {
    if (value == nullptr) return "null";
    const char* chars = env->GetStringUTFChars(static_cast<jstring>(value), nullptr);
    std::string s = chars == nullptr ? "<unreadable>" : chars;
    if (chars != nullptr) env->ReleaseStringUTFChars(static_cast<jstring>(value), chars);
    return '"' + s + '"';
}

void runStartupProbes(JNIEnv* env, jobject activity) {
    jclass cls = env->GetObjectClass(activity);
    int ok = 0;
    int failed = 0;
    for (const Probe& probe : kStartupProbes) {
        jmethodID method = probe.isStatic ? env->GetStaticMethodID(cls, probe.name, probe.signature)
                                          : env->GetMethodID(cls, probe.name, probe.signature);
        if (method == nullptr) {
            PH_LOGE("probe FAIL %s%s: %s", probe.name, probe.signature, describeException(env).c_str());
            failed++;
            continue;
        }
        std::string value;
        const char ret = probe.signature[2];
        switch (ret) {
            case 'I':
                value = std::to_string(probe.isStatic ? env->CallStaticIntMethod(cls, method) : env->CallIntMethod(activity, method));
                break;
            case 'J':
                value = std::to_string(probe.isStatic ? env->CallStaticLongMethod(cls, method) : env->CallLongMethod(activity, method));
                break;
            case 'F':
                value = std::to_string(probe.isStatic ? env->CallStaticFloatMethod(cls, method) : env->CallFloatMethod(activity, method));
                break;
            case 'Z':
                value = (probe.isStatic ? env->CallStaticBooleanMethod(cls, method) : env->CallBooleanMethod(activity, method)) ? "true" : "false";
                break;
            default: {
                jobject result = probe.isStatic ? env->CallStaticObjectMethod(cls, method) : env->CallObjectMethod(activity, method);
                if (!env->ExceptionCheck()) {
                    if (ret == '[') {
                        value = result == nullptr ? "null" : "array[" + std::to_string(env->GetArrayLength(static_cast<jarray>(result))) + "]";
                    } else {
                        value = javaString(env, result);
                    }
                }
                if (result != nullptr) env->DeleteLocalRef(result);
                break;
            }
        }
        if (env->ExceptionCheck()) {
            PH_LOGE("probe FAIL %s: %s", probe.name, describeException(env).c_str());
            failed++;
            continue;
        }
        PH_LOGI("probe %s -> %s", probe.name, value.c_str());
        ok++;
    }
    env->DeleteLocalRef(cls);
    PH_LOGI("probes done ok=%d fail=%d", ok, failed);
}

// ---------------------------------------------------------------------------------------------
// Rendering and the main loop.
// ---------------------------------------------------------------------------------------------
struct Renderer {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    int width = 0;
    int height = 0;

    bool ready() const { return surface != EGL_NO_SURFACE; }

    bool init(ANativeWindow* window) {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
            PH_LOGE("egl FAIL initialize: 0x%x", eglGetError());
            return false;
        }
        const EGLint attribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                                  EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
        EGLConfig config;
        EGLint count = 0;
        if (!eglChooseConfig(display, attribs, &config, 1, &count) || count < 1) {
            PH_LOGE("egl FAIL choose config: 0x%x", eglGetError());
            return false;
        }
        surface = eglCreateWindowSurface(display, config, window, nullptr);
        const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
        if (surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT || !eglMakeCurrent(display, surface, surface, context)) {
            PH_LOGE("egl FAIL surface/context: 0x%x", eglGetError());
            destroy();
            return false;
        }
        eglQuerySurface(display, surface, EGL_WIDTH, &width);
        eglQuerySurface(display, surface, EGL_HEIGHT, &height);
        PH_LOGI("egl ready %dx%d renderer=%s", width, height, reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
        return true;
    }

    void draw(long long frame) {
        const float t = static_cast<float>(frame % 240) / 240.0f;
        glViewport(0, 0, width, height);
        glClearColor(0.15f + 0.2f * t, 0.45f, 0.35f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        eglSwapBuffers(display, surface);
    }

    void destroy() {
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            eglTerminate(display);
        }
        display = EGL_NO_DISPLAY;
        surface = EGL_NO_SURFACE;
        context = EGL_NO_CONTEXT;
    }
};

struct Game {
    Renderer renderer;
    bool resumed = false;
    bool focused = false;
    long long frames = 0;
    long long keys = 0;
    long long motions = 0;
};

const char* commandName(int32_t cmd) {
    switch (cmd) {
        case APP_CMD_INIT_WINDOW: return "INIT_WINDOW";
        case APP_CMD_TERM_WINDOW: return "TERM_WINDOW";
        case APP_CMD_WINDOW_RESIZED: return "WINDOW_RESIZED";
        case APP_CMD_WINDOW_REDRAW_NEEDED: return "WINDOW_REDRAW_NEEDED";
        case APP_CMD_CONTENT_RECT_CHANGED: return "CONTENT_RECT_CHANGED";
        case APP_CMD_SOFTWARE_KB_VIS_CHANGED: return "SOFTWARE_KB_VIS_CHANGED";
        case APP_CMD_GAINED_FOCUS: return "GAINED_FOCUS";
        case APP_CMD_LOST_FOCUS: return "LOST_FOCUS";
        case APP_CMD_CONFIG_CHANGED: return "CONFIG_CHANGED";
        case APP_CMD_LOW_MEMORY: return "LOW_MEMORY";
        case APP_CMD_START: return "START";
        case APP_CMD_RESUME: return "RESUME";
        case APP_CMD_SAVE_STATE: return "SAVE_STATE";
        case APP_CMD_PAUSE: return "PAUSE";
        case APP_CMD_STOP: return "STOP";
        case APP_CMD_DESTROY: return "DESTROY";
        case APP_CMD_WINDOW_INSETS_CHANGED: return "WINDOW_INSETS_CHANGED";
        case APP_CMD_EDITOR_ACTION: return "EDITOR_ACTION";
        case APP_CMD_KEY_EVENT: return "KEY_EVENT";
        case APP_CMD_TOUCH_EVENT: return "TOUCH_EVENT";
        default: return "UNKNOWN";
    }
}

void handleCommand(android_app* app, int32_t cmd) {
    auto* game = static_cast<Game*>(app->userData);
    PH_LOGI("cmd %s", commandName(cmd));
    switch (cmd) {
        case APP_CMD_INIT_WINDOW:
            if (app->window != nullptr) game->renderer.init(app->window);
            break;
        case APP_CMD_TERM_WINDOW:
            game->renderer.destroy();
            break;
        case APP_CMD_RESUME:
            game->resumed = true;
            break;
        case APP_CMD_PAUSE:
            game->resumed = false;
            break;
        case APP_CMD_GAINED_FOCUS:
            game->focused = true;
            break;
        case APP_CMD_LOST_FOCUS:
            game->focused = false;
            break;
        default:
            break;
    }
}

void drainInput(android_app* app, Game* game) {
    android_input_buffer* input = android_app_swap_input_buffers(app);
    if (input == nullptr) return;
    for (uint64_t i = 0; i < input->keyEventsCount; i++) {
        const GameActivityKeyEvent& event = input->keyEvents[i];
        PH_LOGI("input key code=%d action=%d", event.keyCode, event.action);
        game->keys++;
    }
    for (uint64_t i = 0; i < input->motionEventsCount; i++) {
        const GameActivityMotionEvent& event = input->motionEvents[i];
        PH_LOGI("input motion action=%d pointers=%d", event.action, event.pointerCount);
        game->motions++;
    }
    android_app_clear_key_events(input);
    android_app_clear_motion_events(input);
}

}  // namespace

void android_main(android_app* app) {
    PH_LOGI("android_main start version=%s", PH_GAME_VERSION);
    Game game;
    app->userData = &game;
    app->onAppCmd = handleCommand;

    JNIEnv* env = nullptr;
    JavaVM* vm = app->activity->vm;
    if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        runStartupProbes(env, app->activity->javaGameActivity);
    } else {
        PH_LOGE("probe FAIL attach game thread to the VM");
        env = nullptr;
    }

    using Clock = std::chrono::steady_clock;
    auto lastHeartbeat = Clock::now();
    while (!app->destroyRequested) {
        const bool animating = game.resumed && game.renderer.ready();
        int events = 0;
        android_poll_source* source = nullptr;
        const int result = ALooper_pollOnce(animating ? 0 : 100, nullptr, &events, reinterpret_cast<void**>(&source));
        if (result == ALOOPER_POLL_ERROR) {
            PH_LOGE("looper poll error");
            break;
        }
        if (source != nullptr) source->process(app, source);
        drainInput(app, &game);

        if (game.resumed && game.renderer.ready()) {
            game.renderer.draw(game.frames);
            game.frames++;
            if (game.frames == 1) {
                PH_LOGI("frame first %dx%d", game.renderer.width, game.renderer.height);
            } else if (game.frames % 120 == 0) {
                PH_LOGI("frame %lld", game.frames);
            }
        }

        if (Clock::now() - lastHeartbeat >= std::chrono::seconds(2)) {
            lastHeartbeat = Clock::now();
            PH_LOGI("heartbeat frames=%lld jni=%lld resumed=%d focused=%d window=%d keys=%lld motions=%lld resize=%dx%d suspend=%d stop=%d",
                    game.frames, ph::jniCallCount().load(), game.resumed, game.focused, game.renderer.ready(),
                    game.keys, game.motions, g_state.width.load(), g_state.height.load(),
                    g_state.suspendCount.load(), g_state.stopCount.load());
        }
    }

    game.renderer.destroy();
    PH_LOGI("android_main exit frames=%lld", game.frames);
    if (env != nullptr) vm->DetachCurrentThread();
}
