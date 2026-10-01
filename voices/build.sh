#!/usr/bin/env bash
# Builds the Piper voice service (BRIEF §3.3) for macOS or Linux from pinned, hash-checked sources (voices/lock.json).
#
#   voices/build.sh
#
# Output: voices/build/<os>-<arch>/piper/
#   piper                      the executable (GPL-3 as a whole because it loads espeak-ng; run as a child process,
#                              never linked into the app)
#   lib*.dylib | lib*.so.*     espeak-ng, piper_phonemize, onnxruntime (rpath = the executable's dir)
#   espeak-ng-data/            phoneme tables and dictionaries
#   LICENSES/                  license texts + SOURCE.txt (exact source tarballs, for GPL-3 §6)
# and its hashes under "artifacts" in voices/lock.json (voices/hash.py).
#
# Environment:
#   ARCH=x86_64|arm64     macOS only: cross-build for that arch (default: host arch; x86_64 on arm64 needs Rosetta,
#                         because espeak-ng runs itself during the build to compile its data)
#   CMAKE="..."           cmake command (default: cmake on PATH, else `uvx --from cmake cmake`)
#   JOBS=n                parallel build jobs (default: CPU count)
#   MOKUHYO_VOICES_CLEAN=1  wipe the CMake build dir first
#
# Linux: build on the oldest distro you support (CI: ubuntu-22.04). piper and piper_phonemize link libstdc++/libgcc
# statically; espeak-ng and onnxruntime need only glibc.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

case "$(uname -s)" in
    Darwin) os=macos ;;
    Linux) os=linux ;;
    *) echo "unsupported OS $(uname -s); use voices/build.ps1 on Windows" >&2; exit 2 ;;
esac

host_arch="$(uname -m)"
case "$host_arch" in aarch64) host_arch=arm64 ;; amd64) host_arch=x86_64 ;; esac
arch="${ARCH:-$host_arch}"
case "$arch" in
    arm64 | aarch64) arch=arm64 ;;
    x86_64 | amd64) arch=x86_64 ;;
    *) echo "unsupported arch $arch" >&2; exit 2 ;;
esac
if [[ "$os" == linux && "$arch" != "$host_arch" ]]; then
    echo "cross-building on Linux is not supported; build on a $arch runner" >&2; exit 2
fi
platform="$os-$arch"

if [[ -n "${CMAKE:-}" ]]; then
    read -r -a cmake <<<"$CMAKE"
elif command -v cmake >/dev/null 2>&1; then
    cmake=(cmake)
else
    cmake=(uvx --from cmake cmake)
fi
if [[ "$os" == macos ]]; then jobs="${JOBS:-$(sysctl -n hw.ncpu)}"; else jobs="${JOBS:-$(nproc)}"; fi

build_dir="$here/build/cmake/$platform"
out="$here/build/$platform/piper"
if [[ "${MOKUHYO_VOICES_CLEAN:-0}" == 1 ]]; then rm -rf "$build_dir"; fi
mkdir -p "$build_dir"

args=(-S "$here" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release -DMOKUHYO_PLATFORM="$platform")
if [[ "$os" == macos ]]; then
    export MACOSX_DEPLOYMENT_TARGET=12.0
    args+=(-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 -DCMAKE_OSX_ARCHITECTURES="$arch")
fi

start=$(date +%s)
echo "== configure ($platform)"
"${cmake[@]}" "${args[@]}"
echo "== build (-j$jobs)"
"${cmake[@]}" --build "$build_dir" --config Release -j "$jobs"
built=$(($(date +%s) - start))

# ---- assemble ----
echo "== assemble $out"
rm -rf "$out"
mkdir -p "$out/LICENSES"
cp "$build_dir/piper/piper" "$out/piper"
chmod 755 "$out/piper"
libdir="$build_dir/pi/lib"

# Copy the shared libraries piper needs (transitively), dereferencing symlinks, under the names it asks for.
copy_deps() {
    local bin="$1" name
    if [[ "$os" == macos ]]; then
        deps=$(otool -L "$bin" | awk 'NR>1 {print $1}' | grep '^@rpath/' | sed 's|^@rpath/||' || true)
    else
        deps=$(readelf -d "$bin" | awk '/NEEDED/ {gsub(/[\[\]]/, "", $5); print $5}')
    fi
    for name in $deps; do
        [[ -e "$out/$name" ]] && continue
        [[ -e "$libdir/$name" ]] || continue  # a system library
        cp -L "$libdir/$name" "$out/$name"
        chmod 644 "$out/$name"
        copy_deps "$out/$name"
    done
}
copy_deps "$out/piper"

cp -R "$build_dir/pi/share/espeak-ng-data" "$out/espeak-ng-data"

if [[ "$os" == macos ]]; then
    for f in "$out/piper" "$out"/*.dylib; do
        # Drop absolute build-tree rpaths; everything resolves from the executable's directory.
        for rp in $(otool -l "$f" | awk '/LC_RPATH/ {getline; getline; print $2}'); do
            [[ "$rp" == @* ]] || install_name_tool -delete_rpath "$rp" "$f" 2>/dev/null || true
        done
        if [[ "$f" == *.dylib ]]; then install_name_tool -id "@rpath/$(basename "$f")" "$f"; fi
    done
    install_name_tool -add_rpath @executable_path "$out/piper"
    strip -x "$out/piper"
    # install_name_tool invalidates signatures; arm64 refuses to run unsigned code, so re-sign ad hoc.
    for f in "$out/piper" "$out"/*.dylib; do codesign --force --sign - "$f" >/dev/null; done
else
    strip --strip-unneeded "$out/piper" "$out"/*.so* 2>/dev/null || true
    if command -v patchelf >/dev/null 2>&1; then
        for f in "$out/piper" "$out"/*.so*; do patchelf --set-rpath '$ORIGIN' "$f"; done
    fi
fi

# ---- licenses + source pointers ----
while IFS='=' read -r key dir; do
    case "$key" in
        piper) cp "$dir/LICENSE.md" "$out/LICENSES/piper-LICENSE.txt" ;;
        piper-phonemize) cp "$dir/LICENSE.md" "$out/LICENSES/piper-phonemize-LICENSE.txt" ;;
        espeak-ng)
            cp "$dir/COPYING" "$out/LICENSES/espeak-ng-COPYING.txt"
            for extra in COPYING.APACHE COPYING.BSD2 COPYING.UCD; do
                if [[ -f "$dir/$extra" ]]; then cp "$dir/$extra" "$out/LICENSES/espeak-ng-$extra.txt"; fi
            done ;;
        fmt) cp "$dir/LICENSE.rst" "$out/LICENSES/fmt-LICENSE.txt" ;;
        spdlog) cp "$dir/LICENSE" "$out/LICENSES/spdlog-LICENSE.txt" ;;
        onnxruntime)
            cp "$dir/LICENSE" "$out/LICENSES/onnxruntime-LICENSE.txt"
            if [[ -f "$dir/ThirdPartyNotices.txt" ]]; then
                cp "$dir/ThirdPartyNotices.txt" "$out/LICENSES/onnxruntime-ThirdPartyNotices.txt"
            fi ;;
    esac
done <"$build_dir/sources.txt"
py="$(command -v python3 || command -v python)"
"$py" "$here/hash.py" sources "$platform" >"$out/LICENSES/SOURCE.txt"

# ---- smoke: the binary loads its libraries and data ----
"$out/piper" --version >/dev/null
echo "== built $out ($built s compile, $(du -sh "$out" | cut -f1))"

"$py" "$here/hash.py" artifacts "$platform"
