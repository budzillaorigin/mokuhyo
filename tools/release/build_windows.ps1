# Builds the Windows x64 installer (MSI + portable zip) on the owner's Windows machine, instead of GitHub Actions.
# The steps mirror .github/workflows/release.yml: native cpu + vulkan, Piper, content packs, resources, package,
# packaged smoke, collect, then a fresh per-user install of the MSI and a launch from an empty data folder.
#
# Prerequisites (once):
#   - Visual Studio 2022 or newer with "Desktop development with C++" (or the Build Tools)
#   - Vulkan SDK (https://vulkan.lunarg.com/sdk/home#windows), for the GPU build; without it the build ships CPU only
#   - JDK 21 (e.g. Temurin) with JAVA_HOME set, Git, Python 3, uv (https://docs.astral.sh/uv/), GitHub CLI logged in
#     (gh auth login) to download the pinned content packs
#
# Run from a "Developer PowerShell for VS" in the repository root:
#   powershell -ExecutionPolicy Bypass -File tools\release\build_windows.ps1 [-Version 0.1.0]
# Output: build\dist\Mokuhyo-<version>-windows-x64.msi, -portable.zip and SHA256SUMS.windows-x64
param([string]$Version = "0.1.0")
$ErrorActionPreference = "Stop"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repo
$out = Join-Path $repo "build\dist"
New-Item -ItemType Directory -Force -Path $out | Out-Null
function Step($name) { Write-Host "== $name" -ForegroundColor Cyan }
function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" } }

Step "native cpu"
& powershell -NoProfile -ExecutionPolicy Bypass -File native\build.ps1 -Variant cpu; Check "native cpu"
Step "native vulkan"
& powershell -NoProfile -ExecutionPolicy Bypass -File native\build.ps1 -Variant vulkan
if ($LASTEXITCODE -ne 0) { Write-Warning "Vulkan variant failed; shipping CPU only" }
Step "piper"
& powershell -NoProfile -ExecutionPolicy Bypass -File voices\build.ps1; Check "piper"

Step "content packs, fonts, resources"
Push-Location tools
uv run --locked python release/fetch_packs.py; Check "fetch_packs"
uv run --locked python release/stage_fonts.py; Check "stage_fonts"
uv run --locked python release/stage_resources.py --require-native --require-voices; Check "stage_resources"
Pop-Location

Step "package"
.\gradlew.bat --no-daemon :desktopApp:packageMsi :desktopApp:createDistributable "-Pmokuhyo.version=$Version" --console=plain -q; Check "package"

Step "packaged smoke"
$exe = Get-ChildItem desktopApp\build\compose\binaries\main\app -Filter Mokuhyo.exe -Recurse | Select-Object -First 1
$smoke = & $exe.FullName --smoke --require-packs 2>&1 | Out-String
Write-Host $smoke
if ($smoke -notmatch "smoke: OK") { throw "packaged smoke failed" }

Step "collect"
Get-ChildItem $out -Filter "*windows-x64*" | Remove-Item
Push-Location tools
uv run --locked python release/collect.py --name windows-x64 --version $Version --out $out; Check "collect"
Pop-Location
$sums = Get-ChildItem $out -Filter "Mokuhyo-$Version-windows-x64*" | ForEach-Object {
    "$((Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower())  $($_.Name)"
}
$sums | Set-Content -Encoding ascii (Join-Path $out "SHA256SUMS.windows-x64")

Step "fresh install (per-user MSI, empty data folder)"
$fresh = Join-Path $env:TEMP ("mokuhyo-fresh-" + [IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Path $fresh | Out-Null
Copy-Item (Join-Path $out "Mokuhyo-$Version-windows-x64.msi") $fresh
Copy-Item (Join-Path $out "SHA256SUMS.windows-x64") (Join-Path $fresh "SHA256SUMS")
& powershell -NoProfile -ExecutionPolicy Bypass -File tools\gates\fresh_install_smoke.ps1 -Tag "v$Version" -Dir $fresh; Check "fresh install"

Write-Host ""
Write-Host "Done. Send these files from build\dist\ back (or upload them to the v$Version pre-release):" -ForegroundColor Green
Get-ChildItem $out -Filter "*windows-x64*" | ForEach-Object { Write-Host "  $($_.Name)  $([math]::Round($_.Length / 1MB)) MB" }
