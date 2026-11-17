#!/usr/bin/env bash
set -uo pipefail
./gradlew :app:connectedDebugAndroidTest --stacktrace
result=$?
mkdir -p app/build/playback-validation
adb exec-out run-as com.tensei.ytxtube.preview cat files/youtube-validation.json > app/build/playback-validation/youtube-validation.json || true
adb logcat -d -s YouTubeValidation:I PlayerFallback:W YTXTubeHTTP:E > app/build/playback-validation/diagnostics.log || true
exit "$result"
