cmake_minimum_required(VERSION 3.22)

project(levilauncher_android_mod_menu_examples LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(FetchContent)

get_filename_component(LL_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(PRELOADER_ANDROID_ROOT
    "${LL_REPO_ROOT}/app/src/main/cpp/preloader"
    CACHE PATH "Path to LiteLDev/preloader-android")

if(NOT EXISTS "${PRELOADER_ANDROID_ROOT}/include/pl/ModMenu.hpp")
    message(FATAL_ERROR
        "PRELOADER_ANDROID_ROOT must point to a preloader-android checkout. "
        "Missing: ${PRELOADER_ANDROID_ROOT}/include/pl/ModMenu.hpp")
endif()

option(LL_MOD_MENU_EXAMPLES_LINK_PRELOADER
       "Build and link the local preloader CMake target for direct API examples"
       ON)

if(LL_MOD_MENU_EXAMPLES_LINK_PRELOADER AND NOT TARGET preloader)
    add_subdirectory("${PRELOADER_ANDROID_ROOT}"
                     "${CMAKE_BINARY_DIR}/_preloader_android")
endif()

if(NOT TARGET fmt::fmt)
    FetchContent_Declare(
        fmt
        GIT_REPOSITORY https://github.com/fmtlib/fmt.git
        GIT_TAG 11.2.0)
    set(FMT_DOC OFF CACHE BOOL "Disable fmt docs" FORCE)
    set(FMT_TEST OFF CACHE BOOL "Disable fmt tests" FORCE)
    set(FMT_INSTALL OFF CACHE BOOL "Disable fmt install rules" FORCE)
    FetchContent_MakeAvailable(fmt)
endif()

if(ANDROID_ABI)
    set(LL_EXAMPLE_OUTPUT_ABI "${ANDROID_ABI}")
else()
    set(LL_EXAMPLE_OUTPUT_ABI "host")
endif()
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out/${LL_EXAMPLE_OUTPUT_ABI}")

find_library(LL_LOG_LIB log)
function(configure_mod_menu_example target_name)
    target_include_directories("${target_name}" PRIVATE
        "${PRELOADER_ANDROID_ROOT}/include")
    target_compile_options("${target_name}" PRIVATE
        $<$<COMPILE_LANG_AND_ID:CXX,Clang,GNU>:-Wall -Wextra -Wpedantic -fvisibility=hidden -fvisibility-inlines-hidden>)
    if(LL_LOG_LIB)
        target_link_libraries("${target_name}" PRIVATE "${LL_LOG_LIB}")
    endif()
    target_link_libraries("${target_name}" PRIVATE fmt::fmt)
endfunction()

function(link_preloader_or_allow_runtime_lookup target_name)
    if(TARGET preloader)
        target_link_libraries("${target_name}" PRIVATE preloader)
    else()
        target_link_options("${target_name}" PRIVATE
            -Wl,--unresolved-symbols=ignore-all)
    endif()
endfunction()

add_library(modmenu_cpp_lifecycle SHARED
    src/cpp_lifecycle_mod.cpp)
configure_mod_menu_example(modmenu_cpp_lifecycle)
link_preloader_or_allow_runtime_lookup(modmenu_cpp_lifecycle)
