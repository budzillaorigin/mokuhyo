# Expected SHA-256 of one file from a SHA256SUMS list. Accepts both coreutils formats: "<hash>  <name>" (text mode,
# written on Linux for releases) and "<hash> *<name>" (binary mode, written by Git for Windows' sha256sum in the
# release dry run, 39663569689), with LF or CRLF line ends. Throws when the file isn't listed.
function Get-ExpectedSha256([string]$SumsPath, [string]$Asset) {
    foreach ($line in Get-Content -LiteralPath $SumsPath) {
        if ($line -match '^([0-9a-fA-F]{64}) [ *](.+?)\s*$' -and $Matches[2] -eq $Asset) { return $Matches[1].ToLower() }
    }
    throw "SHA256SUMS has no entry for $Asset"
}
