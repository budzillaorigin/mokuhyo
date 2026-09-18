# Native inference for Android: llama.cpp (MIT) and whisper.cpp (MIT), both built on one ggml (MIT).
#
# Sources come from pinned release tarballs with SHA-256 hashes (update tag + hash together; the iOS
# xcframeworks in tools/models/fetch_ios_frameworks.sh pin the same releases):
#   llama.cpp   b11040
#   whisper.cpp b5130
#
# Output: libtsumugi_llama.so and libtsumugi_whisper.so (arm64-v8a). Each statically links its engine and a
# copy of ggml; only the JNI entry points are exported, so the two copies never clash.
cmake_minimum_required(VERSION 3.22.1)
project(tsumugi_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

include(FetchContent)

# Static libraries, no tools/tests/examples, no network code, CPU backend only.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
# ARMv8.2 dot-product kernels (every arm64 SoC since ~2018). LlamaJni/WhisperJni check /proc/cpuinfo for
# "asimddp" before loading, so older cores get an error message instead of SIGILL.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(llama
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11040.tar.gz
    URL_HASH SHA256=5205d346d8a5cc005202128b68fe88afe11c1c4b9a004b9215af19d7046eab8f
    DOWNLOAD_EXTRACT_TIMESTAMP ON)
FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/b5130.tar.gz
    URL_HASH SHA256=a9ad0f82f30cb6ac5b627874895f89571dd64f7c49d0df3a38550fdb9325fe9b
    DOWNLOAD_EXTRACT_TIMESTAMP ON)

# llama.cpp first: it defines the `ggml` target, which whisper.cpp then reuses instead of adding its own copy.
FetchContent_MakeAvailable(llama)
FetchContent_MakeAvailable(whisper)

# Export only JNI symbols; drop everything pulled in from the static archives.
set(TSUMUGI_LINK_FLAGS "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")

add_library(tsumugi_llama SHARED llama_jni.cpp)
target_link_libraries(tsumugi_llama PRIVATE llama android log)
target_link_options(tsumugi_llama PRIVATE ${TSUMUGI_LINK_FLAGS})

add_library(tsumugi_whisper SHARED whisper_jni.cpp)
target_link_libraries(tsumugi_whisper PRIVATE whisper android log)
target_link_options(tsumugi_whisper PRIVATE ${TSUMUGI_LINK_FLAGS})
