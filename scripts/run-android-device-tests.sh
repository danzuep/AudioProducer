#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-false}" == "true" ]]; then
  ./gradlew :app:installDebug
  adb shell -T "run-as io.github.danzuep.bibleaudioproducer.debug sh -c 'cat > files/test-model.onnx'" < "$RUNNER_TEMP/neural-model.onnx"
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.testNeuralModel=true
else
  ./gradlew :app:connectedDebugAndroidTest
fi
