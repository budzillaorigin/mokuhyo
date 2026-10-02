#!/usr/bin/env bash
# Builds the Windows x64 installer (MSI + portable zip) on macOS, with no Windows machine (D-026). jpackage can't
# target Windows from macOS, so this assembles the same app image jpackage would make, by hand:
#   - Java runtime: this machine's jlink linking the Windows JDK's jmods (same JDK version, required for cross-linking)
#   - launcher: jpackage's own Windows launcher (jpackageapplauncherw.exe from the Windows JDK) + app\Mokuhyo.cfg
#   - jars: the app's jars from the macOS app image, with skiko's macOS runtime swapped for skiko-windows-x64
#   - native: mokuhyo_native.dll cross-built with mingw-w64 (native/build_mingw.sh)
#   - voices: Piper cross-built with mingw-w64 (voices/build_mingw.sh)
#   - MSI: wixl (msitools), per-user install into %LOCALAPPDATA%\Mokuhyo with a Start-menu shortcut, same
#     UpgradeCode as the jpackage MSI.
#
#   tools/release/build_windows_cross.sh [VERSION]       # default 0.1.0; output in build/dist/
#
# Prerequisites: brew install mingw-w64 msitools vulkan-headers spirv-headers shaderc; a macOS app image (tools/release/build_macos_arm64.sh) for the jars;
# native/build_mingw.sh cpu and voices/build_mingw.sh already run (this script runs them if their output is missing).
# Untested by construction on the build machine: run the MSI on Windows (tools/gates/fresh_install_smoke.ps1).
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO="$PWD"
VERSION="${1:-0.1.0}"
W="$REPO/build/windows-x64"
OUT="$REPO/build/dist"
SKIKO=0.150.1
UPGRADE_CODE=5b8f1f0e-3c1d-4d6c-9a77-6d2a8f0f6a11
MAC_APP="$REPO/desktopApp/build/compose/binaries/main/app/Mokuhyo.app/Contents"
mkdir -p "$W" "$OUT"

[[ -f "$MAC_APP/app/Mokuhyo.cfg" ]] || { echo "no macOS app image at $MAC_APP; run tools/release/build_macos_arm64.sh first" >&2; exit 2; }
if [[ ! -f "$W/jdk/release" ]]; then
  echo "== Windows JDK 21"
  curl -fsSL -o "$W/jdk.zip" "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
  (cd "$W" && unzip -q jdk.zip && mv jdk-21* jdk && rm jdk.zip)
fi
host_jdk=$(/usr/libexec/java_home -v 21)
want=$(grep JAVA_VERSION= "$W/jdk/release" | tr -d "\r"); have=$(grep JAVA_VERSION= "$host_jdk/release" | tr -d "\r")
[[ "$want" == "$have" ]] || { echo "jlink needs the same JDK version on both sides: Windows $want, host $have" >&2; exit 2; }

[[ -f native/build/windows-x86_64/cpu/mokuhyo_native.dll ]] || JAVA_HOME="$W/jdk" native/build_mingw.sh cpu
[[ -f voices/build/windows-x86_64/piper/piper.exe ]] || WIN_JDK="$W/jdk" voices/build_mingw.sh
if [[ ! -f native/build/windows-x86_64/vulkan/mokuhyo_native.dll ]]; then
  # GPU variant: an import library for vulkan-1.dll (shipped with every current GPU driver) generated from the core
  # (suffix-less) prototypes in vulkan_core.h; only the functions the DLL calls end up in its import table.
  vh="$(brew --prefix vulkan-headers)/include"
  python3 - "$vh/vulkan/vulkan_core.h" > "$W/vulkan-1.def" <<'PY'
import re, sys
names = set(re.findall(r"VKAPI_ATTR \w+ VKAPI_CALL (vk\w+)\(", open(sys.argv[1]).read()))
vendor = re.compile(r"(KHR|EXT|NVX?|AMDX?|INTEL|QCOM|ARM|HUAWEI|VALVE|GOOGLE|FUCHSIA|ANDROID|MVK|MESA|SEC|QNX|OHOS|IMG|NN|LUNARG|MSFT|GGP|BRCM|VIV)$")
print("LIBRARY vulkan-1.dll\nEXPORTS")
print("\n".join(sorted(n for n in names if not vendor.search(n))))
PY
  x86_64-w64-mingw32-dlltool --def "$W/vulkan-1.def" --output-lib "$W/libvulkan-1.a" --dllname vulkan-1.dll
  JAVA_HOME="$W/jdk" VULKAN_INCLUDE="$vh" VULKAN_IMPORT_LIB="$W/libvulkan-1.a" \
    SPIRV_HEADERS_DIR="$(brew --prefix spirv-headers)/share/cmake/SPIRV-Headers" native/build_mingw.sh vulkan ||
    echo "WARNING: Vulkan variant failed; shipping CPU only"
fi

# Dark title bar helper (D-027): a 13 KB DLL that needs only system DLLs.
mkdir -p native/build/windows-x86_64/win
x86_64-w64-mingw32-gcc -O2 -shared -s -o native/build/windows-x86_64/win/mokuhyo_win.dll native/win/dark_titlebar.c \
  -I"$W/jdk/include" -I"$W/jdk/include/win32" -ldwmapi -static-libgcc

IMG="$W/image/Mokuhyo"
rm -rf "$W/image" && mkdir -p "$IMG/app"
echo "== runtime (jlink → Windows)"
modules=$(sed -n 's/^MODULES="\(.*\)"/\1/p' "$MAC_APP/runtime/Contents/Home/release" | tr ' ' ',')
"$host_jdk/bin/jlink" --module-path "$W/jdk/jmods" --add-modules "$modules" --output "$IMG/runtime" \
  --strip-debug --no-header-files --no-man-pages --strip-native-commands
[[ -f "$IMG/runtime/bin/server/jvm.dll" ]] || { echo "jlink produced no Windows runtime" >&2; exit 1; }

echo "== launcher"
if [[ ! -f "$W/jpk/classes/jdk/jpackage/internal/resources/jpackageapplauncherw.exe" ]]; then
  mkdir -p "$W/jpk" && (cd "$W/jpk" && tail -c +5 ../jdk/jmods/jdk.jpackage.jmod > j.zip && { unzip -o -q j.zip || true; } && rm j.zip)
fi
cp "$W/jpk/classes/jdk/jpackage/internal/resources/jpackageapplauncherw.exe" "$IMG/Mokuhyo.exe"
# Console launcher for smoke tests and support (stdout visible): MokuhyoConsole.exe reads app\MokuhyoConsole.cfg.
cp "$W/jpk/classes/jdk/jpackage/internal/resources/jpackageapplauncher.exe" "$IMG/MokuhyoConsole.exe"

echo "== jars"
for j in "$MAC_APP"/app/*.jar; do
  case "$(basename "$j")" in skiko-awt-runtime-macos-*) ;; *) cp "$j" "$IMG/app/" ;; esac
done
skiko_jar="$W/skiko-awt-runtime-windows-x64-$SKIKO.jar"
if [[ ! -f "$skiko_jar" ]]; then
  u="https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-windows-x64/$SKIKO/skiko-awt-runtime-windows-x64-$SKIKO.jar"
  curl -fsSL -o "$skiko_jar.tmp" "$u"
  [[ "$(shasum "$skiko_jar.tmp" | cut -d' ' -f1)" == "$(curl -fsSL "$u.sha1")" ]] || { echo "skiko jar checksum mismatch" >&2; exit 1; }
  mv "$skiko_jar.tmp" "$skiko_jar"
fi
cp "$skiko_jar" "$IMG/app/"
# Everything skiko ships next to its DLL: Skia's ICU data (icudtl.dat) is required on Windows — without it
# text layout aborts ("SkLoadICU: datafile ... is missing", check(fUnicode)).
(cd "$IMG/app" && unzip -o -q "$skiko_jar" -x 'META-INF/*')
for f in skiko-windows-x64.dll icudtl.dat; do [[ -f "$IMG/app/$f" ]] || { echo "skiko $f missing" >&2; exit 1; }; done

echo "== app config"
{
  echo "[Application]"
  grep '^app.mainclass=' "$MAC_APP/app/Mokuhyo.cfg"
  for j in "$IMG"/app/*.jar; do echo "app.classpath=\$APPDIR\\$(basename "$j")"; done
  echo
  echo "[JavaOptions]"
  grep '^java-options=' "$MAC_APP/app/Mokuhyo.cfg" | grep -v -- '-Xdock:' | sed 's|\$APPDIR/|$APPDIR\\|g' |
    sed "s|-Djpackage.app-version=.*|-Djpackage.app-version=$VERSION|"
} | sed 's/$/\r/' > "$IMG/app/Mokuhyo.cfg"
cp "$IMG/app/Mokuhyo.cfg" "$IMG/app/MokuhyoConsole.cfg"

echo "== resources"
R="$IMG/app/resources"
mkdir -p "$R/native/cpu" "$R/voices"
for d in fonts models packs voices; do
  [[ -d "desktopApp/resources/common/$d" ]] && cp -R "desktopApp/resources/common/$d" "$R/"
done
cp native/build/windows-x86_64/cpu/mokuhyo_native.dll "$R/native/cpu/"
cp native/build/windows-x86_64/win/mokuhyo_win.dll "$R/native/"
[[ -f native/build/windows-x86_64/vulkan/mokuhyo_native.dll ]] && mkdir -p "$R/native/vulkan" && cp native/build/windows-x86_64/vulkan/mokuhyo_native.dll "$R/native/vulkan/"
rm -rf "$R/voices/piper" && cp -R voices/build/windows-x86_64/piper "$R/voices/piper"
for need in packs/ja/exam.json models voices/manifest.json voices/piper/piper.exe fonts; do
  [[ -e "$R/$need" ]] || { echo "missing resource $need" >&2; exit 1; }
done
cp desktopApp/icons/mokuhyo.ico "$IMG/Mokuhyo.ico"

echo "== portable zip"
rm -f "$OUT"/*windows-x64*
(cd "$W/image" && zip -q -r -9 "$OUT/Mokuhyo-$VERSION-windows-x64-portable.zip" Mokuhyo)

echo "== MSI (wixl)"
M="$W/msi" && rm -rf "$M" && mkdir -p "$M"
(cd "$W/image/Mokuhyo" && find . -type f | sed 's|^\./||' | sort) |
  wixl-heat --prefix "" --directory-ref INSTALLDIR --component-group AppFiles --var var.SourceDir --win64 > "$M/files.wxs"
cat > "$M/main.wxs" <<WXS
<?xml version="1.0" encoding="utf-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="*" Name="Mokuhyo" Language="1033" Version="$VERSION" Manufacturer="Mokuhyo contributors"
           UpgradeCode="$UPGRADE_CODE">
    <Package InstallerVersion="500" Compressed="yes" InstallScope="perUser"
             Description="DLPT- and OPI-style practice for 11 languages, fully offline" />
    <MajorUpgrade DowngradeErrorMessage="A newer version of Mokuhyo is already installed." />
    <Media Id="1" Cabinet="mokuhyo.cab" EmbedCab="yes" />
    <Icon Id="MokuhyoIcon" SourceFile="\$(var.SourceDir)/Mokuhyo.ico" />
    <Property Id="ARPPRODUCTICON" Value="MokuhyoIcon" />
    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="LocalAppDataFolder">
        <Directory Id="INSTALLDIR" Name="Mokuhyo" />
      </Directory>
      <Directory Id="ProgramMenuFolder">
        <Directory Id="MenuDir" Name="Mokuhyo" />
      </Directory>
    </Directory>
    <DirectoryRef Id="MenuDir">
      <Component Id="Shortcuts" Guid="7c1f3c2e-9a4b-4d1e-8f2a-3b6e5d4c2a19" Win64="yes">
        <Shortcut Id="MenuShortcut" Name="Mokuhyo" Target="[INSTALLDIR]Mokuhyo.exe" WorkingDirectory="INSTALLDIR"
                  Icon="MokuhyoIcon" />
        <RemoveFolder Id="RemoveMenuDir" On="uninstall" />
        <RegistryValue Root="HKCU" Key="Software\\Mokuhyo" Name="installed" Type="integer" Value="1" KeyPath="yes" />
      </Component>
    </DirectoryRef>
    <Feature Id="Main" Level="1">
      <ComponentGroupRef Id="AppFiles" />
      <ComponentRef Id="Shortcuts" />
    </Feature>
  </Product>
</Wix>
WXS
wixl -a x64 -D Win64=yes -D SourceDir="$W/image/Mokuhyo" -o "$OUT/Mokuhyo-$VERSION-windows-x64.msi" "$M/main.wxs" "$M/files.wxs"

(cd "$OUT" && shasum -a 256 Mokuhyo-"$VERSION"-windows-x64* > SHA256SUMS.windows-x64)
ls -la "$OUT" | grep windows-x64
