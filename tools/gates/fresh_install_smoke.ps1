# Fresh-machine install smoke (BRIEF §11.1 gate_release), Windows: downloads the MSI for a release tag, verifies it
# against the release's SHA256SUMS, installs it per-user the way a learner would, and launches the installed app's
# headless smoke test. CI runs it on a fresh GitHub runner in release.yml.
#
#   powershell -File tools/gates/fresh_install_smoke.ps1 -Tag v0.1.0
#   powershell -File tools/gates/fresh_install_smoke.ps1 -Tag main -Dir dist   # installers from a folder (dry run)
param([Parameter(Mandatory = $true)][string]$Tag, [string]$Name = "windows-x64", [string]$Dir = "")
$ErrorActionPreference = "Stop"
$version = if ($Tag -match '^v\d') { $Tag.TrimStart("v") } else { "0.1.0" }
if ($Dir) { $Dir = (Resolve-Path $Dir).Path }
$asset = "Mokuhyo-$version-$Name.msi"
$work = Join-Path $env:RUNNER_TEMP ([IO.Path]::GetRandomFileName())
if (-not $env:RUNNER_TEMP) { $work = Join-Path $env:TEMP ([IO.Path]::GetRandomFileName()) }
New-Item -ItemType Directory -Path $work | Out-Null
Set-Location $work
Write-Host "== fresh install: $asset"
if ($Dir) {
    Copy-Item (Join-Path $Dir $asset), (Join-Path $Dir "SHA256SUMS") .
} else {
    gh release download $Tag --pattern $asset --pattern SHA256SUMS
    if ($LASTEXITCODE -ne 0) { throw "download failed" }
}
$want = (Select-String -Path SHA256SUMS -Pattern " $([regex]::Escape($asset))$").Line.Split(" ")[0]
$got = (Get-FileHash $asset -Algorithm SHA256).Hash.ToLower()
if ($want -ne $got) { throw "checksum mismatch: $got != $want" }
$p = Start-Process msiexec.exe -ArgumentList "/i", "`"$work\$asset`"", "/qn", "/l*v", "`"$work\install.log`"" -Wait -PassThru
if ($p.ExitCode -ne 0) { Get-Content "$work\install.log" -Tail 40; throw "msiexec exit $($p.ExitCode)" }
# The cross-built MSI (D-026) also ships MokuhyoConsole.exe, whose output can be captured; prefer it.
$dirs = "$env:LOCALAPPDATA\Mokuhyo", "$env:ProgramFiles\Mokuhyo"
$exe = Get-ChildItem -Path $dirs -Filter MokuhyoConsole.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $exe) { $exe = Get-ChildItem -Path $dirs -Filter Mokuhyo.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 }
if (-not $exe) { throw "installed Mokuhyo.exe not found" }
Write-Host "== fresh install: launching $($exe.FullName) --smoke"
# An empty data directory: no settings, no learner, nothing cached.
$out = & $exe.FullName --smoke --require-packs --data-dir (Join-Path $work "data") 2>&1 | Out-String
Write-Host $out
if ($out -notmatch "smoke: OK") { throw "smoke failed" }
Write-Host "fresh_install_smoke: PASS ($asset)"
