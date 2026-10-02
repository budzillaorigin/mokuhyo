#!/usr/bin/env bash
# Cross-builds the Windows x64 Piper voice service with mingw-w64 on macOS (D-026), the counterpart of
# voices/build.ps1 for when no Windows machine with MSVC is available. Same pinned sources (voices/lock.json) and
# the same patch (patch_piper.cmake).
#
#   WIN_JDK=<Windows JDK dir> voices/build_mingw.sh
#
# Needs a host Piper build first (voices/build.sh): espeak-ng compiles its phoneme data by running espeak-ng, and the
# freshly built one is a Windows .exe, so host_espeak.sh substitutes the host build's espeak-ng (same source; the data
# is platform-neutral).
# Output: voices/build/windows-x86_64/piper/ — piper.exe, its DLLs, the GCC runtime DLLs (GPL-3 with the GCC Runtime
# Library Exception; winpthreads MIT/BSD), the MSVC runtime that Microsoft's onnxruntime.dll needs (copied from the
# Windows JDK), espeak-ng-data/ and LICENSES/.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
: "${WIN_JDK:?set WIN_JDK to an unpacked Windows x64 JDK (for the MSVC runtime DLLs)}"
host_os=$([ "$(uname -s)" = Darwin ] && echo macos || echo linux)
host_arch=$(uname -m | sed 's/aarch64/arm64/;s/amd64/x86_64/')
host_espeak="$here/build/cmake/$host_os-$host_arch/ei/bin/espeak-ng"
[[ -x "$host_espeak" ]] || { echo "no host espeak-ng at $host_espeak; run voices/build.sh first" >&2; exit 2; }
if command -v cmake >/dev/null; then cmake=(cmake); else cmake=(uvx --from "cmake>=3.28" cmake); fi

platform=windows-x86_64
build_dir="$here/build/cmake/$platform-mingw"
out="$here/build/$platform/piper"
start=$(date +%s)
echo "== configure ($platform, mingw-w64)"
"${cmake[@]}" -S "$here" -B "$build_dir" -DCMAKE_TOOLCHAIN_FILE="$repo/native/mingw-x86_64.cmake" \
  -DCMAKE_BUILD_TYPE=Release -DMOKUHYO_PLATFORM=$platform
echo "== build"
VALGRIND="$here/host_espeak.sh" HOST_ESPEAK_NG="$host_espeak" "${cmake[@]}" --build "$build_dir" -j "${JOBS:-8}"
built=$(($(date +%s) - start))

echo "== assemble $out"
rm -rf "$out" && mkdir -p "$out/LICENSES"
cp "$build_dir/piper/piper.exe" "$out/"
cp "$build_dir"/pi/bin/*.dll "$build_dir"/pi/lib/*.dll "$out/" 2>/dev/null || true
cp "$build_dir"/piper/*.dll "$out/"
cp "$build_dir/piper/libtashkeel_model.ort" "$out/" 2>/dev/null || true
gcc_lib=$(dirname "$(x86_64-w64-mingw32-g++ -print-libgcc-file-name)")
for dll in libstdc++-6.dll libgcc_s_seh-1.dll libwinpthread-1.dll; do
  f=$(find "$(dirname "$(dirname "$(command -v x86_64-w64-mingw32-g++)")")/.." "$gcc_lib/../../.." -name "$dll" -path "*x86_64*" 2>/dev/null | head -1)
  [[ -n "$f" ]] || { echo "missing $dll in the mingw-w64 toolchain" >&2; exit 1; }
  cp "$f" "$out/"
done
for dll in msvcp140.dll vcruntime140.dll vcruntime140_1.dll; do cp "$WIN_JDK/bin/$dll" "$out/"; done
cp -R "$build_dir/pi/share/espeak-ng-data" "$out/espeak-ng-data"

# Every DLL any shipped binary imports must be shipped or be a Windows system DLL.
missing=$(for f in "$out"/*.exe "$out"/*.dll; do x86_64-w64-mingw32-objdump -p "$f" | awk '/DLL Name/ {print $3}'; done | sort -u |
  grep -viE '^(api-ms-win-.*|kernel32|user32|advapi32|ws2_32|shell32|ole32|oleaut32|bcrypt|ntdll|msvcrt|ucrtbase|dbghelp|setupapi|shlwapi|crypt32|secur32|rpcrt4|winmm|version|psapi|iphlpapi|dxgi|d3d12|dxcore)\.dll$' |
  while read -r d; do [[ -f "$out/$d" ]] || ls "$out" | grep -qix "$d" || echo "$d"; done)
[[ -z "$missing" ]] || { echo "unresolved DLL imports: $missing" >&2; exit 1; }

echo "== licenses"
lic="$out/LICENSES"
while IFS='=' read -r key dir; do
  case "$key" in
    piper) cp "$dir/LICENSE.md" "$lic/piper-LICENSE.txt" ;;
    piper-phonemize) cp "$dir/LICENSE.md" "$lic/piper-phonemize-LICENSE.txt" ;;
    espeak-ng) cp "$dir/COPYING" "$lic/espeak-ng-COPYING.txt"
               for x in COPYING.APACHE COPYING.BSD2 COPYING.UCD; do [[ -f "$dir/$x" ]] && cp "$dir/$x" "$lic/espeak-ng-$x.txt"; done ;;
    fmt) cp "$dir/LICENSE.rst" "$lic/fmt-LICENSE.txt" ;;
    spdlog) cp "$dir/LICENSE" "$lic/spdlog-LICENSE.txt" ;;
    onnxruntime) cp "$dir/LICENSE" "$lic/onnxruntime-LICENSE.txt"
                 [[ -f "$dir/ThirdPartyNotices.txt" ]] && cp "$dir/ThirdPartyNotices.txt" "$lic/onnxruntime-ThirdPartyNotices.txt" ;;
  esac
done < "$build_dir/sources.txt"
cat > "$lic/gcc-runtime-NOTICE.txt" <<'TXT'
libstdc++-6.dll and libgcc_s_seh-1.dll are from GCC (mingw-w64 toolchain), licensed GPL-3.0-or-later WITH the
GCC Runtime Library Exception 3.1, which permits redistributing them with programs of any license
(https://www.gnu.org/licenses/gcc-exception-3.1.html). Source: https://gcc.gnu.org/
libwinpthread-1.dll is from mingw-w64 winpthreads (MIT and BSD-3-Clause). Source: https://www.mingw-w64.org/
msvcp140.dll, vcruntime140.dll and vcruntime140_1.dll are the Microsoft Visual C++ runtime, redistributable
"Distributable Code" under the Visual Studio license terms, required by Microsoft's onnxruntime.dll; copied from the
Eclipse Temurin JDK, which redistributes them under the same terms.
TXT
python3 "$here/hash.py" sources $platform > "$lic/SOURCE.txt"
python3 "$here/hash.py" artifacts $platform
echo "== built $out (${built} s, $(du -sh "$out" | cut -f1))"
