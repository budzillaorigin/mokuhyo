# Builds the Piper voice service (BRIEF §3.3) on Windows with MSVC, from pinned, hash-checked sources
# (voices\lock.json). The Windows counterpart of voices/build.sh.
#
#   voices\build.ps1
#
# Output: voices\build\windows-<arch>\piper\
#   piper.exe                  the executable (GPL-3 as a whole because it loads espeak-ng; run as a child process,
#                              never linked into the app)
#   *.dll                      espeak-ng, piper_phonemize, onnxruntime, and the MSVC runtime DLLs (app-local copy
#                              from the Visual Studio redist folder, so no VC++ redistributable install is needed)
#   espeak-ng-data\            phoneme tables and dictionaries
#   LICENSES\                  license texts + SOURCE.txt (exact source tarballs, for GPL-3 §6)
# and its hashes under "artifacts" in voices\lock.json (voices\hash.py).
#
# Run from a "Developer PowerShell for VS 2022" (cl.exe on PATH, VCToolsRedistDir set), with Python 3 on PATH.
# Environment: CMAKE ("cmake" on PATH, else "uvx --from cmake cmake"), JOBS (parallel jobs),
#              MOKUHYO_VOICES_CLEAN=1 (wipe the CMake build dir first).
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$arch = switch ($env:PROCESSOR_ARCHITECTURE) {
    "AMD64" { "x86_64" }
    "ARM64" { "arm64" }
    default { throw "unsupported arch $($env:PROCESSOR_ARCHITECTURE)" }
}
$vsPlatform = if ($arch -eq "arm64") { "ARM64" } else { "x64" }
$redistArch = if ($arch -eq "arm64") { "arm64" } else { "x64" }
$platform = "windows-$arch"

if ($env:CMAKE) { $cmake = $env:CMAKE -split " " }
elseif (Get-Command cmake -ErrorAction SilentlyContinue) { $cmake = @("cmake") }
else { $cmake = @("uvx", "--from", "cmake", "cmake") }
$cmakeExe = $cmake[0]
$cmakeArgs = if ($cmake.Length -gt 1) { $cmake[1..($cmake.Length - 1)] } else { @() }
$py = if (Get-Command python3 -ErrorAction SilentlyContinue) { "python3" } else { "python" }

$jobs = if ($env:JOBS) { $env:JOBS } else { $env:NUMBER_OF_PROCESSORS }
$buildDir = Join-Path $here "build\cmake\$platform"
$out = Join-Path $here "build\$platform\piper"
if ($env:MOKUHYO_VOICES_CLEAN -eq "1" -and (Test-Path $buildDir)) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$sw = [Diagnostics.Stopwatch]::StartNew()
Write-Host "== configure ($platform)"
& $cmakeExe @cmakeArgs -S $here -B $buildDir -G "Visual Studio 17 2022" -A $vsPlatform "-DMOKUHYO_PLATFORM=$platform"
if ($LASTEXITCODE -ne 0) { throw "configure failed" }
Write-Host "== build (-j$jobs)"
& $cmakeExe @cmakeArgs --build $buildDir --config Release -j $jobs
if ($LASTEXITCODE -ne 0) { throw "build failed" }
$built = [int]$sw.Elapsed.TotalSeconds

# ---- assemble ----
Write-Host "== assemble $out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path (Join-Path $out "LICENSES") | Out-Null

$exe = Get-ChildItem -Path (Join-Path $buildDir "piper"), (Join-Path $buildDir "pp") -Recurse -Filter piper.exe |
    Select-Object -First 1
if (-not $exe) { throw "piper.exe not found under $buildDir" }
Copy-Item $exe.FullName (Join-Path $out "piper.exe")

foreach ($sub in @("pi\bin", "pi\lib")) {
    $dir = Join-Path $buildDir $sub
    if (Test-Path $dir) {
        Get-ChildItem -Path $dir -Filter *.dll | ForEach-Object { Copy-Item $_.FullName (Join-Path $out $_.Name) -Force }
    }
}
foreach ($need in @("espeak-ng.dll", "piper_phonemize.dll", "onnxruntime.dll")) {
    if (-not (Test-Path (Join-Path $out $need))) { throw "missing $need next to piper.exe" }
}

# MSVC runtime, app-local (Microsoft's "Distributable Code" for the VC++ runtime).
if ($env:VCToolsRedistDir) {
    $crt = Get-ChildItem -Path (Join-Path $env:VCToolsRedistDir $redistArch) -Directory -Filter "Microsoft.VC*.CRT" |
        Select-Object -First 1
    if ($crt) {
        Get-ChildItem -Path $crt.FullName -Include "msvcp140*.dll", "vcruntime140*.dll", "concrt140.dll" -Recurse |
            ForEach-Object { Copy-Item $_.FullName (Join-Path $out $_.Name) -Force }
    } else { Write-Warning "no Microsoft.VC*.CRT folder under $env:VCToolsRedistDir\$redistArch" }
} else {
    Write-Warning "VCToolsRedistDir is not set (not a Developer PowerShell?): MSVC runtime DLLs not copied"
}

Copy-Item -Recurse (Join-Path $buildDir "pi\share\espeak-ng-data") (Join-Path $out "espeak-ng-data")

# ---- licenses + source pointers ----
$lic = Join-Path $out "LICENSES"
foreach ($line in Get-Content (Join-Path $buildDir "sources.txt")) {
    $key, $dir = $line -split "=", 2
    switch ($key) {
        "piper" { Copy-Item (Join-Path $dir "LICENSE.md") (Join-Path $lic "piper-LICENSE.txt") }
        "piper-phonemize" { Copy-Item (Join-Path $dir "LICENSE.md") (Join-Path $lic "piper-phonemize-LICENSE.txt") }
        "espeak-ng" {
            Copy-Item (Join-Path $dir "COPYING") (Join-Path $lic "espeak-ng-COPYING.txt")
            foreach ($extra in @("COPYING.APACHE", "COPYING.BSD2", "COPYING.UCD")) {
                $f = Join-Path $dir $extra
                if (Test-Path $f) { Copy-Item $f (Join-Path $lic "espeak-ng-$extra.txt") }
            }
        }
        "fmt" { Copy-Item (Join-Path $dir "LICENSE.rst") (Join-Path $lic "fmt-LICENSE.txt") }
        "spdlog" { Copy-Item (Join-Path $dir "LICENSE") (Join-Path $lic "spdlog-LICENSE.txt") }
        "onnxruntime" {
            Copy-Item (Join-Path $dir "LICENSE") (Join-Path $lic "onnxruntime-LICENSE.txt")
            $tpn = Join-Path $dir "ThirdPartyNotices.txt"
            if (Test-Path $tpn) { Copy-Item $tpn (Join-Path $lic "onnxruntime-ThirdPartyNotices.txt") }
        }
    }
}
$sources = & $py (Join-Path $here "hash.py") sources $platform
if ($LASTEXITCODE -ne 0) { throw "hash.py sources failed" }
[IO.File]::WriteAllLines((Join-Path $lic "SOURCE.txt"), $sources)

# ---- smoke: the binary loads its DLLs ----
& (Join-Path $out "piper.exe") --version | Out-Null
if ($LASTEXITCODE -ne 0) { throw "piper.exe --version failed (missing DLL?)" }
$size = (Get-ChildItem -Recurse $out | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("== built $out ($built s compile, {0:N0} MB)" -f $size)

& $py (Join-Path $here "hash.py") artifacts $platform
if ($LASTEXITCODE -ne 0) { throw "hash.py artifacts failed" }
