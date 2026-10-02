#!/usr/bin/env bash
# Cross-builds the Windows x64 native library (mokuhyo_native.dll) with mingw-w64 on macOS or Linux, for when no
# Windows machine with MSVC is available (D-026). Same sources and options as native/build.ps1.
#
#   JAVA_HOME=<a Windows JDK unpacked here, for include/win32> native/build_mingw.sh <cpu|vulkan>
#
# vulkan also needs VULKAN_INCLUDE (Vulkan-Headers include dir, e.g. $(brew --prefix vulkan-headers)/include),
# VULKAN_IMPORT_LIB (a mingw import library for vulkan-1.dll; tools/release/build_windows_cross.sh makes one from
# vulkan_core.h) and glslc on PATH (brew install shaderc).
set -euo pipefail
variant="${1:?usage: build_mingw.sh <cpu|vulkan>}"
here="$(cd "$(dirname "$0")" && pwd)"
[[ -f "${JAVA_HOME:-}/include/win32/jni_md.h" ]] || { echo "JAVA_HOME must be a Windows JDK (include/win32/jni_md.h)" >&2; exit 2; }
if command -v cmake >/dev/null; then cmake=(cmake); else cmake=(uvx --from "cmake>=3.28" cmake); fi
build_dir="$here/build/cmake/windows-x86_64-mingw-$variant"
out_dir="$here/build/windows-x86_64/$variant"
mkdir -p "$build_dir" "$out_dir"
extra=()
if [[ "$variant" == vulkan ]]; then
  : "${VULKAN_INCLUDE:?set VULKAN_INCLUDE}" "${VULKAN_IMPORT_LIB:?set VULKAN_IMPORT_LIB}"
  extra=(-DVulkan_INCLUDE_DIR="$VULKAN_INCLUDE" -DVulkan_LIBRARY="$VULKAN_IMPORT_LIB" -DVulkan_GLSLC_EXECUTABLE="$(command -v glslc)"
         -DSPIRV-Headers_DIR="${SPIRV_HEADERS_DIR:?set SPIRV_HEADERS_DIR (brew install spirv-headers)}"
         "-DCMAKE_CXX_FLAGS=-isystem $SPIRV_HEADERS_DIR/../../../include")
fi
"${cmake[@]}" -S "$here" -B "$build_dir" -DCMAKE_TOOLCHAIN_FILE="$here/mingw-x86_64.cmake" -DCMAKE_BUILD_TYPE=Release \
  -DMOKUHYO_VARIANT="$variant" ${extra[@]+"${extra[@]}"}
"${cmake[@]}" --build "$build_dir" --config Release --target mokuhyo_native -j "${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc)}"
cp "$build_dir/mokuhyo_native.dll" "$out_dir/"
x86_64-w64-mingw32-strip --strip-unneeded "$out_dir/mokuhyo_native.dll"
echo "== built $out_dir/mokuhyo_native.dll ($(du -h "$out_dir/mokuhyo_native.dll" | cut -f1))"
x86_64-w64-mingw32-objdump -p "$out_dir/mokuhyo_native.dll" | grep "DLL Name" | sort -u
