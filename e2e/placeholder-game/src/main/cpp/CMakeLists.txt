cmake_minimum_required(VERSION 3.22)
project(placeholder_game LANGUAGES C CXX)

# Mirrors the launcher's native build settings (app/src/main/cpp/CMakeLists.txt).
set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "Build type" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_STANDARD 17)
add_link_options("-Wl,-z,max-page-size=16384")

if(NOT PH_GAME_VERSION)
    message(FATAL_ERROR "PH_GAME_VERSION is required")
endif()

find_package(game-activity REQUIRED CONFIG)

# libminecraftpe.so: GameActivity glue + main loop + every game-side JNI native.
add_library(minecraftpe SHARED game.cpp generated/jni_minecraftpe.cpp)
target_compile_definitions(minecraftpe PRIVATE PH_GAME_VERSION="${PH_GAME_VERSION}")
# The real game exports GameActivity's JNI entry point; keep the whole static glue so the
# launcher's GameActivity finds Java_com_google_androidgamesdk_GameActivity_initializeNativeCode.
target_link_libraries(minecraftpe
    -Wl,--whole-archive game-activity::game-activity_static -Wl,--no-whole-archive
    android log EGL GLESv2)

function(placeholder_companion name)
    add_library(${name} SHARED companion.cpp ${ARGN})
    target_compile_definitions(${name} PRIVATE PH_LIBRARY_NAME="lib${name}.so")
    target_link_libraries(${name} log)
endfunction()

placeholder_companion(fmod generated/jni_fmod.cpp)
placeholder_companion(MediaDecoders_Android)
if(PH_MALLOC_REDIRECT)
    placeholder_companion(MallocRedirect)
endif()

# libHttpClient.Android.so: the real library, built from the launcher's own source tree, so the
# launcher's PlayFab and the com.xbox.httpclient classes see the same exports as with the game.
set(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared libraries" FORCE)
add_subdirectory(${PH_LAUNCHER_CPP_DIR}/libHttpClient/Build/libHttpClient.Android
                 ${CMAKE_CURRENT_BINARY_DIR}/libHttpClient)
