#!/usr/bin/env bash
# Builds the Mokuhyo native AI runtime (llama.cpp + whisper.cpp JNI, one library) for macOS or Linux.
#
#   native/build.sh <cpu|metal|vulkan>
#
# Output: native/build/<os>-<arch>/<variant>/<libmokuhyo_native.dylib|.so>, and its SHA-256 recorded under
# "artifacts" in native/lock.json (native/hash.sh). Sources are pinned tarballs (CMakeLists.txt, lock.json).
#
# Environment:
#   ARCH=x86_64|arm64   macOS only: cross-build for that arch (default: host arch)
#   JAVA_HOME           JDK whose include/ has jni.h (default: /usr/libexec/java_home on macOS)
#   CMAKE="..."         cmake command (default: cmake on PATH, else `uvx --from cmake cmake`)
#   JOBS=n              parallel build jobs (default: CPU count)
#   MOKUHYO_NATIVE_CLEAN=1  wipe the CMake build dir first
set -euo pipefail

variant="${1:-}"
case "$variant" in
    cpu | metal | vulkan) ;;
    *) echo "usage: $0 <cpu|metal|vulkan>" >&2; exit 2 ;;
esac

here="$(cd "$(dirname "$0")" && pwd)"

case "$(uname -s)" in
    Darwin) os=macos ;;
    Linux) os=linux ;;
    *) echo "unsupported OS $(uname -s); use native/build.ps1 on Windows" >&2; exit 2 ;;
esac
if [[ "$os" == macos && "$variant" == vulkan ]]; then echo "vulkan is Windows/Linux only; use metal" >&2; exit 2; fi
if [[ "$os" == linux && "$variant" == metal ]]; then echo "metal is macOS only" >&2; exit 2; fi

host_arch="$(uname -m)"
arch="${ARCH:-$host_arch}"
case "$arch" in
    arm64 | aarch64) arch=arm64 ;;
    x86_64 | amd64) arch=x86_64 ;;
    *) echo "unsupported arch $arch" >&2; exit 2 ;;
esac
if [[ "$os" == linux && "$arch" != "$( [[ $host_arch == aarch64 ]] && echo arm64 || echo "$host_arch")" ]]; then
    echo "cross-building on Linux is not supported; build on a $arch runner" >&2; exit 2
fi

if [[ -z "${JAVA_HOME:-}" && "$os" == macos ]]; then JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"; fi
if [[ -z "${JAVA_HOME:-}" ]]; then
    javac_path="$(command -v javac || true)"
    [[ -n "$javac_path" ]] && JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$javac_path")")")"
fi
if [[ -z "${JAVA_HOME:-}" || ! -f "$JAVA_HOME/include/jni.h" ]]; then
    echo "JAVA_HOME must point at a JDK (include/jni.h); got '${JAVA_HOME:-}'" >&2; exit 2
fi
export JAVA_HOME

if [[ -n "${CMAKE:-}" ]]; then
    read -r -a cmake <<<"$CMAKE"
elif command -v cmake >/dev/null 2>&1; then
    cmake=(cmake)
else
    cmake=(uvx --from cmake cmake)
fi

if [[ "$os" == macos ]]; then jobs="${JOBS:-$(sysctl -n hw.ncpu)}"; else jobs="${JOBS:-$(nproc)}"; fi

build_dir="$here/build/cmake/$os-$arch-$variant"
out_dir="$here/build/$os-$arch/$variant"
[[ "${MOKUHYO_NATIVE_CLEAN:-0}" == 1 ]] && rm -rf "$build_dir"
mkdir -p "$build_dir" "$out_dir"

args=(-S "$here" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release -DMOKUHYO_VARIANT="$variant")
if [[ "$os" == macos ]]; then
    export MACOSX_DEPLOYMENT_TARGET=12.0
    args+=(-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 -DCMAKE_OSX_ARCHITECTURES="$arch")
    lib=libmokuhyo_native.dylib
else
    lib=libmokuhyo_native.so
fi

start=$(date +%s)
echo "== configure ($os-$arch $variant)"
"${cmake[@]}" "${args[@]}"
echo "== build (-j$jobs)"
"${cmake[@]}" --build "$build_dir" --config Release --target mokuhyo_native -j "$jobs"

cp "$build_dir/$lib" "$out_dir/$lib"
if [[ "$os" == macos ]]; then
    strip -x "$out_dir/$lib"
    # The library has no rpath deps beyond system frameworks; give it a stable install name.
    install_name_tool -id "@rpath/$lib" "$out_dir/$lib"
    codesign --force --sign - "$out_dir/$lib" >/dev/null 2>&1 || true
else
    strip --strip-unneeded "$out_dir/$lib"
fi
echo "== built $out_dir/$lib ($(($(date +%s) - start)) s)"

"$here/hash.sh"
