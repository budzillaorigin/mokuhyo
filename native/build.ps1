# Builds the Mokuhyo native AI runtime (llama.cpp + whisper.cpp JNI, one library) on Windows with MSVC.
#
#   native\build.ps1 -Variant <cpu|vulkan>
#
# Output: native\build\windows-<arch>\<variant>\mokuhyo_native.dll, and its SHA-256 recorded under "artifacts" in
# native\lock.json (native\hash.py). Run from a "Developer PowerShell for VS 2022" (or any shell where cl.exe is on
# PATH), with JAVA_HOME pointing at a JDK. The vulkan variant needs the Vulkan SDK (VULKAN_SDK, glslc).
#
# Environment: CMAKE ("cmake" on PATH, else "uvx --from cmake cmake"), JOBS (parallel jobs),
#              MOKUHYO_NATIVE_CLEAN=1 (wipe the CMake build dir first).
param(
    [Parameter(Mandatory = $true)][ValidateSet("cpu", "vulkan")][string]$Variant
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$arch = switch ($env:PROCESSOR_ARCHITECTURE) {
    "AMD64" { "x86_64" }
    "ARM64" { "arm64" }
    default { throw "unsupported arch $($env:PROCESSOR_ARCHITECTURE)" }
}
$platform = if ($arch -eq "arm64") { "ARM64" } else { "x64" }

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "include\jni.h"))) {
    throw "JAVA_HOME must point at a JDK (include\jni.h); got '$($env:JAVA_HOME)'"
}
if ($Variant -eq "vulkan" -and -not $env:VULKAN_SDK) { throw "the vulkan variant needs the Vulkan SDK (VULKAN_SDK)" }

if ($env:CMAKE) { $cmake = $env:CMAKE -split " " }
elseif (Get-Command cmake -ErrorAction SilentlyContinue) { $cmake = @("cmake") }
else { $cmake = @("uvx", "--from", "cmake", "cmake") }
$cmakeExe = $cmake[0]
$cmakeArgs = if ($cmake.Length -gt 1) { $cmake[1..($cmake.Length - 1)] } else { @() }

$jobs = if ($env:JOBS) { $env:JOBS } else { $env:NUMBER_OF_PROCESSORS }
$buildDir = Join-Path $here "build\cmake\windows-$arch-$Variant"
$outDir = Join-Path $here "build\windows-$arch\$Variant"
if ($env:MOKUHYO_NATIVE_CLEAN -eq "1" -and (Test-Path $buildDir)) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $buildDir, $outDir | Out-Null

$sw = [Diagnostics.Stopwatch]::StartNew()
Write-Host "== configure (windows-$arch $Variant)"
# No -G: CMake picks the newest installed Visual Studio (runner images move from VS 2022 to newer releases).
& $cmakeExe @cmakeArgs -S $here -B $buildDir -A $platform `
    "-DMOKUHYO_VARIANT=$Variant"
if ($LASTEXITCODE -ne 0) { throw "configure failed" }
Write-Host "== build (-j$jobs)"
& $cmakeExe @cmakeArgs --build $buildDir --config Release --target mokuhyo_native -j $jobs
if ($LASTEXITCODE -ne 0) { throw "build failed" }

Copy-Item (Join-Path $buildDir "Release\mokuhyo_native.dll") (Join-Path $outDir "mokuhyo_native.dll") -Force
Write-Host "== built $outDir\mokuhyo_native.dll ($([int]$sw.Elapsed.TotalSeconds) s)"

$py = if (Get-Command python3 -ErrorAction SilentlyContinue) { "python3" } else { "python" }
& $py (Join-Path $here "hash.py")
if ($LASTEXITCODE -ne 0) { throw "hash failed" }
