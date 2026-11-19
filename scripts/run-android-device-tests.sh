#!/usr/bin/env bash
set -euo pipefail

args=()
if [[ "${2:-false}" == "true" ]]; then
  adb install "$RUNNER_TEMP/espeak.apk"
  adb shell am start -W -a android.speech.tts.engine.INSTALL_TTS_DATA \
    -n com.reecedunn.espeak/.DownloadVoiceData
  # The installer extracts the bundled voices asynchronously.
  timeout 30 bash -c '
    while adb shell dumpsys activity activities | grep "topResumedActivity.*com.reecedunn.espeak/.DownloadVoiceData" >/dev/null; do
      sleep 1
    done
  '
  adb shell settings put secure tts_default_synth com.reecedunn.espeak
  args+=(-Pandroid.testInstrumentationRunnerArguments.testGreekTts=true)
fi

if [[ "${1:-false}" == "true" ]]; then
  ./gradlew :app:installDebug
  adb shell -T "run-as io.github.danzuep.bibleaudioproducer.debug sh -c 'mkdir -p files && cat > files/test-model.onnx'" < "$RUNNER_TEMP/neural-model.onnx"
  args+=(-Pandroid.testInstrumentationRunnerArguments.testNeuralModel=true)
fi
./gradlew :app:connectedDebugAndroidTest "${args[@]}"
