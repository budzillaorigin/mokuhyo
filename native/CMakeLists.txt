# Mokuhyo native AI runtime for the desktop JVM (BRIEF §3.3): llama.cpp (MIT) and whisper.cpp (MIT) on ONE
# ggml (MIT), behind a JNI surface in package app.mokuhyo.ai.jni. Output: a single shared library
# `mokuhyo_native` (libmokuhyo_native.dylib / libmokuhyo_native.so / mokuhyo_native.dll).
#
# Sources come from pinned release tarballs with SHA-256 hashes, recorded in native/lock.json (update tag + hash
# together, here and there):
#   llama.cpp   b11040
#   whisper.cpp b5130
#
# Variants (-DMOKUHYO_VARIANT=...): cpu (every OS), metal (macOS), vulkan (Windows, Linux).
# Build with native/build.sh <variant> or native/build.ps1 -Variant <variant>, not by hand.
cmake_minimum_required(VERSION 3.22)
project(mokuhyo_native LANGUAGES C CXX)

set(MOKUHYO_VARIANT "cpu" CACHE STRING "cpu | metal | vulkan")
set_property(CACHE MOKUHYO_VARIANT PROPERTY STRINGS cpu metal vulkan)
if (NOT MOKUHYO_VARIANT MATCHES "^(cpu|metal|vulkan)$")
    message(FATAL_ERROR "MOKUHYO_VARIANT must be cpu, metal or vulkan (got '${MOKUHYO_VARIANT}')")
endif()
if (MOKUHYO_VARIANT STREQUAL "metal" AND NOT APPLE)
    message(FATAL_ERROR "the metal variant is macOS only")
endif()
if (MOKUHYO_VARIANT STREQUAL "vulkan" AND APPLE)
    message(FATAL_ERROR "the vulkan variant is Windows/Linux only (macOS uses metal)")
endif()

# Where the source tarballs are cached (hash-verified on every configure).
set(MOKUHYO_DOWNLOAD_DIR "${CMAKE_CURRENT_LIST_DIR}/src" CACHE PATH "tarball cache")

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Hide everything that isn't a JNI entry point (JNIEXPORT marks those visible / dllexport).
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
if (MSVC)
    # Static MSVC runtime: the DLL then only needs system DLLs, not a matching msvcp140.dll next to the JVM.
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

include(FetchContent)

# --- engine options: static libs, no tools/tests/examples/network, no OpenMP --------------------------------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_STATIC OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # no libgomp/libomp to ship
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
# No BLAS backend: on macOS it is Accelerate's new-LAPACK cblas, which needs macOS 13.3 while we target 12.0
# (it would crash on load on 12.x). ggml's own CPU matmul kernels are used instead; Metal does the heavy lifting.
set(GGML_BLAS OFF CACHE BOOL "" FORCE)

# Portable CPU flags. x86_64: x86-64-v3 (AVX2 + FMA + F16C + BMI2), no AVX-512.
if (CMAKE_GENERATOR_PLATFORM)          # Visual Studio generator: -A x64 | ARM64
    set(_mokuhyo_arch "${CMAKE_GENERATOR_PLATFORM}")
elseif (CMAKE_OSX_ARCHITECTURES)       # macOS: build.sh passes exactly one arch
    set(_mokuhyo_arch "${CMAKE_OSX_ARCHITECTURES}")
else()
    set(_mokuhyo_arch "${CMAKE_SYSTEM_PROCESSOR}")
endif()
if (_mokuhyo_arch MATCHES "^(x86_64|AMD64|amd64|x64|X64)$")
    foreach (f SSE42 AVX AVX2 FMA F16C BMI2)
        set(GGML_${f} ON CACHE BOOL "" FORCE)
    endforeach()
    foreach (f AVX512 AVX512_VBMI AVX512_VNNI AVX512_BF16 AVX_VNNI AMX_TILE AMX_INT8 AMX_BF16)
        set(GGML_${f} OFF CACHE BOOL "" FORCE)
    endforeach()
endif()
# arm64 macOS: Apple clang's default target (apple-m1 baseline) — nothing to set.

# GPU backend for the variant; everything else explicitly off (GGML_METAL defaults ON on Apple).
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_WEBGPU OFF CACHE BOOL "" FORCE)
if (MOKUHYO_VARIANT STREQUAL "metal")
    set(GGML_METAL ON CACHE BOOL "" FORCE)
    set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE) # shaders inside the dylib; compiled at load time
    if (CMAKE_OSX_DEPLOYMENT_TARGET)
        set(GGML_METAL_MACOSX_VERSION_MIN "${CMAKE_OSX_DEPLOYMENT_TARGET}" CACHE STRING "" FORCE)
    endif()
elseif (MOKUHYO_VARIANT STREQUAL "vulkan")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

if (WIN32)
    # whisper.cpp adds this when it builds its own ggml; we hand it llama.cpp's, so add it for everyone.
    add_compile_definitions(_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR)
endif()

FetchContent_Declare(llama
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11040.tar.gz
    URL_HASH SHA256=5205d346d8a5cc005202128b68fe88afe11c1c4b9a004b9215af19d7046eab8f
    DOWNLOAD_NAME llama.cpp-b11040.tar.gz
    DOWNLOAD_DIR ${MOKUHYO_DOWNLOAD_DIR}
    DOWNLOAD_EXTRACT_TIMESTAMP ON)
FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/b5130.tar.gz
    URL_HASH SHA256=a9ad0f82f30cb6ac5b627874895f89571dd64f7c49d0df3a38550fdb9325fe9b
    DOWNLOAD_NAME whisper.cpp-b5130.tar.gz
    DOWNLOAD_DIR ${MOKUHYO_DOWNLOAD_DIR}
    DOWNLOAD_EXTRACT_TIMESTAMP ON)

# llama.cpp first: it defines the `ggml` target, which whisper.cpp then reuses instead of adding its own copy.
FetchContent_MakeAvailable(llama)
FetchContent_MakeAvailable(whisper)

# --- JNI headers only (never link libjvm: the library is loaded into a running JVM) -------------------------------
set(_java_home "$ENV{JAVA_HOME}")
if (_java_home AND EXISTS "${_java_home}/include/jni.h")
    set(MOKUHYO_JNI_INCLUDE "${_java_home}/include")
    if (APPLE)
        list(APPEND MOKUHYO_JNI_INCLUDE "${_java_home}/include/darwin")
    elseif (WIN32)
        list(APPEND MOKUHYO_JNI_INCLUDE "${_java_home}/include/win32")
    else()
        list(APPEND MOKUHYO_JNI_INCLUDE "${_java_home}/include/linux")
    endif()
else()
    find_package(JNI REQUIRED COMPONENTS JVM)
    set(MOKUHYO_JNI_INCLUDE ${JNI_INCLUDE_DIRS})
endif()

add_library(mokuhyo_native SHARED cpp/llama_jni.cpp cpp/whisper_jni.cpp cpp/ggml_jni.cpp cpp/jni_common.h)
target_include_directories(mokuhyo_native PRIVATE ${MOKUHYO_JNI_INCLUDE})
target_link_libraries(mokuhyo_native PRIVATE llama whisper ggml)
target_compile_definitions(mokuhyo_native PRIVATE MOKUHYO_VARIANT="${MOKUHYO_VARIANT}")
if (APPLE)
    # Export only the JNI entry points; strip everything pulled in from the static archives.
    target_link_options(mokuhyo_native PRIVATE "-Wl,-exported_symbol,_Java_*" "-Wl,-exported_symbol,_JNI_OnLoad"
                        "-Wl,-dead_strip")
elseif (MINGW)
    # Cross-built from macOS/Linux with mingw-w64 (native/build_mingw.sh): System.mapLibraryName gives
    # mokuhyo_native.dll (no "lib" prefix), and the GCC/pthread runtimes are linked in so only system DLLs are needed.
    set_target_properties(mokuhyo_native PROPERTIES PREFIX "")
    target_link_options(mokuhyo_native PRIVATE "-static" "-static-libstdc++" "-static-libgcc" "-Wl,--gc-sections")
elseif (UNIX)
    # Also link libstdc++/libgcc statically so the .so runs on older distros than the build host.
    target_link_options(mokuhyo_native PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections" "-Wl,--as-needed"
                        "-static-libstdc++" "-static-libgcc")
endif()
