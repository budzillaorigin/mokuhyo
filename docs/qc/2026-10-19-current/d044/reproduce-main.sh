#!/usr/bin/env bash
# Copies ONLY tests onto the reviewed main. These tests are expected to fail at runtime.
set -euo pipefail
repo=$(git rev-parse --show-toplevel)
base=${1:-9a1c7612fdb2fc2296ca13105ff34e816c993916}
repro=$(mktemp -d)
echo "Baseline checkout and test reports: $repro"
git -C "$repo" archive "$base" | tar -x -C "$repro"
for test in InterviewHarness InterviewCaptureTest InterviewCancelTest InterviewCheckpointTest InterviewDeviceTest InterviewStorageFailureTest TopicCaptureTest; do
    cp "$repo/desktopApp/src/test/kotlin/app/mokuhyo/desktop/$test.kt" "$repro/desktopApp/src/test/kotlin/app/mokuhyo/desktop/"
done
mkdir -p "$repro/desktopApp/src/test/resources/META-INF/services"
cp "$repo/desktopApp/src/test/resources/META-INF/services/javax.sound.sampled.spi.MixerProvider" "$repro/desktopApp/src/test/resources/META-INF/services/"
cp "$repo/shared/src/commonTest/kotlin/app/mokuhyo/opi/SpeechPriorityTest.kt" "$repro/shared/src/commonTest/kotlin/app/mokuhyo/opi/"
cp "$repo/shared/src/commonTest/kotlin/app/mokuhyo/speech/VadStartupTest.kt" "$repro/shared/src/commonTest/kotlin/app/mokuhyo/speech/"
"$repro/gradlew" -p "$repro" :shared:jvmTest --tests '*SpeechPriorityTest' --tests '*VadStartupTest' \
    :desktopApp:test --tests '*InterviewCaptureTest' --tests '*InterviewCancelTest' --tests '*InterviewCheckpointTest' \
    --tests '*InterviewDeviceTest' --tests '*InterviewStorageFailureTest' --tests '*TopicCaptureTest' --continue --console=plain
