cmake_minimum_required(VERSION 3.22)
project(wasmcart-android-player C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(DEPS "${CMAKE_CURRENT_SOURCE_DIR}/../deps")
set(WC_NATIVE "${DEPS}/wasmcart-native")
set(LIBNODE_DIR "${DEPS}/libnode" CACHE PATH "Path to android-aarch64 libnode.a + headers")

if(NOT EXISTS "${LIBNODE_DIR}/libnode.a" OR NOT EXISTS "${WC_NATIVE}/src/cart_host.cpp")
    message(FATAL_ERROR "Dependencies missing. Run: bash deps/fetch-deps.sh")
endif()

# ─── SDL2 (fetched source, builds libSDL2.so + the Java side we compile) ──

set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory("${DEPS}/SDL" "${CMAKE_CURRENT_BINARY_DIR}/SDL2" EXCLUDE_FROM_ALL)

# ─── cpu-features: V8 calls android_getCpuFeatures ────────────────────────

add_library(cpufeatures STATIC "${ANDROID_NDK}/sources/android/cpufeatures/cpu-features.c")
target_include_directories(cpufeatures PUBLIC "${ANDROID_NDK}/sources/android/cpufeatures")

# ─── libmain.so: SDL entry + shared wasmcart host + libnode ───────────────

# The host's sources, as wasmcart-libretro lists them. WebGPU stays off:
# no WASMCART_WGPU_NATIVE and no wgpu/ directory, so a WebGPU-only cart is
# refused with the reason and a dual cart runs on GL.
add_library(main SHARED
    android_main.c
    overlay.c
    ${WC_NATIVE}/src/cart_host.cpp
    ${WC_NATIVE}/src/gl_imports.cpp
    ${WC_NATIVE}/src/gl_procs.c
    ${WC_NATIVE}/src/gl_track.c
    ${WC_NATIVE}/src/wgpu_imports.cpp
    ${WC_NATIVE}/src/wgpu_gl_share.cpp
    ${WC_NATIVE}/src/code_cache.cpp
    ${WC_NATIVE}/src/asset_loader.c
    ${WC_NATIVE}/deps/miniz.c
    ${WC_NATIVE}/deps/cJSON.c
)

target_include_directories(main PRIVATE
    ${WC_NATIVE}/include
    ${WC_NATIVE}/src
    ${WC_NATIVE}/deps
    ${WC_NATIVE}/vendor/gl
    ${LIBNODE_DIR}/include
)

# GL context is OWNED BY SDL. The cart's GL entry points all flow through
# wc_host_set_gl_loader(SDL_GL_GetProcAddress); GLESv3 here is only for the
# app's own 2D blit path and on-screen controls. Never create an EGL context
# in native code.
target_link_libraries(main PRIVATE
    SDL2
    ${LIBNODE_DIR}/libnode.a
    cpufeatures
    GLESv3
    dl m log android
)

# Android 15+ 16KB page-size compliance
target_link_options(main PRIVATE -Wl,-z,max-page-size=16384)
target_link_options(SDL2 PRIVATE -Wl,-z,max-page-size=16384)

target_compile_options(main PRIVATE -Wno-unused-parameter)
