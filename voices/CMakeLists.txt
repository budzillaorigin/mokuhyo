# Superbuild for the Piper voice service (BRIEF §3.3). Driven by voices/build.sh and voices/build.ps1; every
# source is a pinned tarball checked against the SHA-256 in voices/lock.json (nothing is fetched from a branch).
#
#   onnxruntime 1.14.1 (Microsoft prebuilt) ─┐
#   espeak-ng (rhasspy fork, GPL-3) ─────────┼─> piper-phonemize ─┐
#   fmt 10.0.0, spdlog 1.12.0 ───────────────┴────────────────────┴─> piper 2023.11.14-2 (+ patch_piper.cmake)
#
# Output prefixes under the build dir: ort/ (onnxruntime), ei/ (espeak-ng), fi/, si/, pi/ (piper-phonemize, which
# also gathers espeak-ng and onnxruntime libs + espeak-ng-data) and piper/ (piper's own install). The scripts then
# assemble voices/build/<os>-<arch>/piper/.
cmake_minimum_required(VERSION 3.19)
project(mokuhyo_voices NONE)
if(POLICY CMP0135)
  cmake_policy(SET CMP0135 NEW) # extracted files get the extraction time, so a changed URL rebuilds
endif()

if(NOT MOKUHYO_PLATFORM)
  message(FATAL_ERROR "pass -DMOKUHYO_PLATFORM=<os>-<arch>, e.g. macos-arm64")
endif()

include(ExternalProject)
file(READ "${CMAKE_CURRENT_LIST_DIR}/lock.json" lock)

function(src_url name out_url out_hash)
  string(JSON url GET "${lock}" sources ${name} url)
  string(JSON sha GET "${lock}" sources ${name} sha256)
  set(${out_url} "${url}" PARENT_SCOPE)
  set(${out_hash} "SHA256=${sha}" PARENT_SCOPE)
endfunction()

set(P "${CMAKE_BINARY_DIR}")
set(DL "${CMAKE_CURRENT_LIST_DIR}/build/downloads")

# Settings every sub-build gets.
set(common_args
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5
)
if(APPLE)
  list(APPEND common_args
    -DCMAKE_OSX_ARCHITECTURES=${CMAKE_OSX_ARCHITECTURES}
    -DCMAKE_OSX_DEPLOYMENT_TARGET=${CMAKE_OSX_DEPLOYMENT_TARGET})
endif()
# Windows keeps the default dynamic MSVC runtime (/MD): piper.exe and piper_phonemize.dll pass std::string and
# std::vector across the DLL boundary, which needs one shared CRT heap. build.ps1 copies the VC++ runtime DLLs
# (app-local deployment) next to piper.exe instead.
if(CMAKE_C_COMPILER)
  list(APPEND common_args -DCMAKE_C_COMPILER=${CMAKE_C_COMPILER})
endif()
if(CMAKE_CXX_COMPILER)
  list(APPEND common_args -DCMAKE_CXX_COMPILER=${CMAKE_CXX_COMPILER})
endif()

# ---- onnxruntime (prebuilt, per platform) ----
string(JSON ort_url GET "${lock}" sources onnxruntime platforms ${MOKUHYO_PLATFORM} url)
string(JSON ort_sha GET "${lock}" sources onnxruntime platforms ${MOKUHYO_PLATFORM} sha256)
ExternalProject_Add(ort
  PREFIX "${P}/o"
  URL "${ort_url}"
  URL_HASH SHA256=${ort_sha}
  DOWNLOAD_DIR "${DL}"
  SOURCE_DIR "${P}/ort"
  CONFIGURE_COMMAND ""
  BUILD_COMMAND ""
  INSTALL_COMMAND ""
)

# ---- espeak-ng (same options piper-phonemize uses) ----
src_url(espeak-ng espeak_url espeak_hash)
ExternalProject_Add(espeak_ng
  PREFIX "${P}/e"
  URL "${espeak_url}"
  URL_HASH ${espeak_hash}
  DOWNLOAD_DIR "${DL}"
  CMAKE_ARGS ${common_args}
    -DCMAKE_INSTALL_PREFIX:PATH=${P}/ei
    -DUSE_ASYNC:BOOL=OFF
    -DBUILD_SHARED_LIBS:BOOL=ON
    -DUSE_MBROLA:BOOL=OFF
    -DUSE_LIBSONIC:BOOL=OFF
    -DUSE_LIBPCAUDIO:BOOL=OFF
    -DUSE_KLATT:BOOL=OFF
    -DUSE_SPEECHPLAYER:BOOL=OFF
    -DEXTRA_cmn:BOOL=ON
    -DEXTRA_ru:BOOL=ON
    -DCMAKE_C_FLAGS=-D_FILE_OFFSET_BITS=64
)

# ---- fmt, spdlog ----
src_url(fmt fmt_url fmt_hash)
ExternalProject_Add(fmt
  PREFIX "${P}/f"
  URL "${fmt_url}"
  URL_HASH ${fmt_hash}
  DOWNLOAD_DIR "${DL}"
  CMAKE_ARGS ${common_args} -DCMAKE_INSTALL_PREFIX:PATH=${P}/fi -DFMT_TEST:BOOL=OFF -DFMT_DOC:BOOL=OFF
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
)
src_url(spdlog spdlog_url spdlog_hash)
ExternalProject_Add(spdlog
  PREFIX "${P}/s"
  URL "${spdlog_url}"
  URL_HASH ${spdlog_hash}
  DOWNLOAD_DIR "${DL}"
  CMAKE_ARGS ${common_args} -DCMAKE_INSTALL_PREFIX:PATH=${P}/si -DSPDLOG_BUILD_EXAMPLE:BOOL=OFF
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
)

# ---- piper-phonemize (pinned commit instead of piper's unpinned master.zip) ----
set(pp_args ${common_args} -DCMAKE_INSTALL_PREFIX:PATH=${P}/pi -DONNXRUNTIME_DIR=${P}/ort -DESPEAK_NG_DIR=${P}/ei)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  # Runs on Ubuntu 22.04+ without depending on the build host's libstdc++ version.
  list(APPEND pp_args "-DCMAKE_SHARED_LINKER_FLAGS=-static-libstdc++ -static-libgcc")
endif()
src_url(piper-phonemize pp_url pp_hash)
ExternalProject_Add(piper_phonemize
  PREFIX "${P}/p"
  URL "${pp_url}"
  URL_HASH ${pp_hash}
  DOWNLOAD_DIR "${DL}"
  DEPENDS ort espeak_ng
  CMAKE_ARGS ${pp_args}
)

# ---- piper ----
src_url(piper piper_url piper_hash)
# The patch step re-runs whenever patch_piper.cmake changes (its hash is part of the command line).
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CMAKE_CURRENT_LIST_DIR}/patch_piper.cmake")
file(SHA256 "${CMAKE_CURRENT_LIST_DIR}/patch_piper.cmake" patch_sha)
ExternalProject_Add(piper
  PREFIX "${P}/pp"
  URL "${piper_url}"
  URL_HASH ${piper_hash}
  DOWNLOAD_DIR "${DL}"
  DEPENDS piper_phonemize fmt spdlog
  PATCH_COMMAND ${CMAKE_COMMAND} -DFILE=<SOURCE_DIR>/src/cpp/main.cpp -DPATCH_SHA=${patch_sha} -P ${CMAKE_CURRENT_LIST_DIR}/patch_piper.cmake
  CMAKE_ARGS ${common_args} -DCMAKE_INSTALL_PREFIX:PATH=${P}/piper
    -DFMT_DIR=${P}/fi -DSPDLOG_DIR=${P}/si -DPIPER_PHONEMIZE_DIR=${P}/pi
    -DBUILD_TESTING=OFF
)

# Source trees whose license texts ship in LICENSES/ (the scripts copy them from here).
ExternalProject_Get_Property(espeak_ng SOURCE_DIR)
set(espeak_src "${SOURCE_DIR}")
ExternalProject_Get_Property(piper_phonemize SOURCE_DIR)
set(pp_src "${SOURCE_DIR}")
ExternalProject_Get_Property(piper SOURCE_DIR)
set(piper_src "${SOURCE_DIR}")
ExternalProject_Get_Property(fmt SOURCE_DIR)
set(fmt_src "${SOURCE_DIR}")
ExternalProject_Get_Property(spdlog SOURCE_DIR)
set(spdlog_src "${SOURCE_DIR}")
file(WRITE "${P}/sources.txt"
  "piper=${piper_src}\npiper-phonemize=${pp_src}\nespeak-ng=${espeak_src}\nfmt=${fmt_src}\nspdlog=${spdlog_src}\nonnxruntime=${P}/ort\n")
