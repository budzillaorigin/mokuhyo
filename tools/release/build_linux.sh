#!/usr/bin/env bash
# Builds the Linux x64 installers (.deb + portable .tar.gz) on a developer machine with Docker (or Colima), in an
# Ubuntu 22.04 amd64 container — the oldest glibc the app supports — instead of GitHub Actions. The steps mirror
# .github/workflows/release.yml: native cpu + vulkan, Piper, resources, package, packaged smoke, collect.
#
#   tools/release/build_linux.sh [VERSION]        # default 0.1.0; output in build/dist/
#
# Sources come from the committed tree (git archive HEAD), so uncommitted edits are not built. Content packs come from
# the local content/packs (built by tools/packs/build_packs.py), models/fonts/voices from the local caches, all
# mounted read-only. Build state persists in the Docker volume mokuhyo-linux-work for faster re-runs.
# On Apple silicon, Colima with Rosetta runs the amd64 container at near-native speed:
#   colima start --vm-type vz --vz-rosetta --cpu 4 --memory 6 --disk 40
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO="$PWD"
VERSION="${1:-0.1.0}"
OUT="$REPO/build/dist"
mkdir -p "$OUT"
JDK_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
VK=1.4.309.0

git archive --format=tar HEAD > "$REPO/build/linux-src.tar"
docker run --rm --platform linux/amd64 \
  -v mokuhyo-linux-work:/work \
  -v "$REPO/build/linux-src.tar:/src.tar:ro" \
  -v "$REPO/content/packs:/packs:ro" \
  -v "$REPO/tools/.cache/models:/cache/models:ro" \
  -v "$REPO/tools/.cache/fonts:/cache/fonts:ro" \
  -v "$REPO/voices/models:/voice-models:ro" \
  -v "$OUT:/out" \
  -e VERSION="$VERSION" -e JDK_URL="$JDK_URL" -e VK="$VK" \
  ubuntu:22.04 bash -euo pipefail -c '
    export DEBIAN_FRONTEND=noninteractive
    if [ ! -f /work/.deps-done ]; then
      apt-get update -qq
      apt-get install -y -qq build-essential cmake git curl ca-certificates xz-utils unzip fakeroot dpkg-dev \
        libvulkan-dev libasound2 libgl1 libfontconfig1 libfreetype6 libx11-6 libxext6 libxrender1 libxtst6 libxi6 >/dev/null
      mkdir -p /work/jdk && curl -fsSL "$JDK_URL" | tar -xz -C /work/jdk --strip-components=1
      mkdir -p /work/vulkan && curl -fsSL "https://sdk.lunarg.com/sdk/download/$VK/linux/vulkansdk-linux-x86_64-$VK.tar.xz" | tar -xJ -C /work/vulkan
      curl -LsSf https://astral.sh/uv/install.sh | env UV_INSTALL_DIR=/work/bin sh >/dev/null
      touch /work/.deps-done
    fi
    # The apt packages live in the container, not the volume: reinstall the light ones on every run.
    [ -x /usr/bin/fakeroot ] || { apt-get update -qq && apt-get install -y -qq build-essential cmake git curl ca-certificates xz-utils \
        unzip fakeroot dpkg-dev libvulkan-dev libasound2 libgl1 libfontconfig1 libfreetype6 libx11-6 libxext6 libxrender1 \
        libxtst6 libxi6 >/dev/null; }
    export JAVA_HOME=/work/jdk PATH=/work/jdk/bin:/work/bin:/work/vulkan/$VK/x86_64/bin:$PATH VULKAN_SDK=/work/vulkan/$VK/x86_64
    # Ubuntu 22.04 ships CMake 3.22; the native and Piper builds need 3.24+ (FetchContent DOWNLOAD_EXTRACT_TIMESTAMP).
    export CMAKE="uvx --from cmake>=3.28 cmake"
    mkdir -p /work/src && tar -xf /src.tar -C /work/src && cd /work/src
    mkdir -p tools/.cache content/packs voices/models
    ln -sfn /cache/models tools/.cache/models; ln -sfn /cache/fonts tools/.cache/fonts
    cp -a /packs/. content/packs/
    cp -a /voice-models/. voices/models/
    echo "== native cpu"; native/build.sh cpu
    # The Vulkan shader generator can deadlock under amd64 emulation (Colima + Rosetta); bound it and fall back to CPU.
    echo "== native vulkan"; timeout 3600 native/build.sh vulkan || { echo "WARNING: Vulkan variant failed or timed out; shipping CPU only"; rm -rf native/build/linux-x86_64/vulkan; }
    echo "== piper"; voices/build.sh
    echo "== stage"
    (cd tools && uv run --locked python release/stage_fonts.py && uv run --locked python release/stage_resources.py --require-native --require-voices)
    echo "== package"
    ./gradlew --no-daemon :desktopApp:packageDeb :desktopApp:createDistributable -Pmokuhyo.version=$VERSION --console=plain -q
    echo "== packaged smoke"
    BIN=$(tools/gates/app_binary.sh); "$BIN" --smoke --require-packs
    rm -f /out/*linux-x64*
    (cd tools && uv run --locked python release/collect.py --name linux-x64 --version $VERSION --out /out)
  '
rm -f "$REPO/build/linux-src.tar"
(cd "$OUT" && shasum -a 256 Mokuhyo-"$VERSION"-linux-x64* > SHA256SUMS.linux)

echo "== fresh install in a clean Ubuntu 22.04 container"
docker run --rm --platform linux/amd64 -v "$OUT:/dist:ro" -v "$REPO/tools/gates:/gates:ro" ubuntu:22.04 bash -euo pipefail -c '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  mkdir -p /tmp/d && cp /dist/Mokuhyo-*-linux-x64.deb /tmp/d/ && cp /dist/SHA256SUMS.linux /tmp/d/SHA256SUMS
  FROM_DIR=/tmp/d /gates/fresh_install_smoke.sh v'"$VERSION"' linux-x64'
ls -la "$OUT" | grep linux-x64
