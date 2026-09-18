#!/usr/bin/env bash
# Downloads the official prebuilt llama.cpp and whisper.cpp xcframeworks (MIT) into iosApp/Frameworks/
# (git-ignored), verifying pinned SHA-256 hashes. Run once on a Mac before building the iOS app; CI runs it too.
#
# Pinned releases (update both the tag and the hash together; the Swift bridges follow these headers):
#   llama.cpp   b11040  llama-b11040-xcframework.zip   (slices: ios-arm64, macos — no iOS Simulator slice)
#   whisper.cpp b5130   whisper-b5130-xcframework.zip  (slices: ios-arm64, ios-arm64_x86_64-simulator, …)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/iosApp/Frameworks"
CACHE="$ROOT/tools/.cache/frameworks"
mkdir -p "$DEST" "$CACHE"

fetch() {
  local name="$1" url="$2" sha="$3"
  local zip="$CACHE/$name.zip"
  if [ ! -f "$zip" ] || ! echo "$sha  $zip" | shasum -a 256 -c --status; then
    echo "Downloading ${name}..."
    curl -fsSL --retry 3 -o "$zip.part" "$url"
    mv "$zip.part" "$zip"
  fi
  echo "$sha  $zip" | shasum -a 256 -c --status || { echo "SHA-256 mismatch for $name" >&2; exit 1; }
  local tmp
  tmp="$(mktemp -d)"
  unzip -q "$zip" -d "$tmp"
  rm -rf "$DEST/$name.xcframework"
  mv "$tmp/build-apple/$name.xcframework" "$DEST/$name.xcframework"
  rm -rf "$tmp"
  echo "Installed $DEST/$name.xcframework"
}

fetch llama \
  "https://github.com/ggml-org/llama.cpp/releases/download/b11040/llama-b11040-xcframework.zip" \
  "b04aa78c994c9c781ed5e864d6e072275994d8990f3d91b31e1382640c4173ce"
fetch whisper \
  "https://github.com/ggml-org/whisper.cpp/releases/download/b5130/whisper-b5130-xcframework.zip" \
  "033a43b0174e8cf9b366f72e4a428cdcf126f93ad1c87d3fa119a96bed6f231a"
