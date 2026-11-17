#!/usr/bin/env bash
set +e
set -uo pipefail
timeout --signal=INT --kill-after=30s 1080s ./gradlew --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8' :app:connectedDebugAndroidTest --stacktrace
result=$?
mkdir -p app/build/playback-validation
timeout 15s adb exec-out run-as com.tensei.ytxtube.preview cat files/youtube-validation.json > app/build/playback-validation/youtube-validation.json || true
timeout 15s adb logcat -d -s YouTubeValidation:I PlayerFallback:W YTXTubeHTTP:E AndroidRuntime:E > app/build/playback-validation/diagnostics.log || true
free -m > app/build/playback-validation/host-memory.txt
if [[ "$result" -ne 0 ]]; then
  sudo dmesg --ctime | tail -80 > app/build/playback-validation/host-kernel.log || true
fi
exit "$result"
