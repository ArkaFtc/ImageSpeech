# Isolated project PPOCR benchmark — 2026-09-08

Connected Samsung SM-A176U1, Android 16, SoC reported as s5e8535. Executed using ADB on device R5GL6478N0N.

## Results

| Measurement | Time |
| --- | ---: |
| Initialization, including first model copies into cache | 1,248.82 ms |
| First full OCR call | 2,048.46 ms |
| Initialization + first OCR (sum; excludes image decoding and process startup) | 3,297.28 ms |
| Mean of 10 measured warm calls | 2,089.18 ms |
| Median of 10 measured warm calls | 2,089.80 ms |
| Minimum / maximum | 2,016.97 / 2,171.69 ms |

All 13 passes (first, two additional warmups, ten measured) returned exactly three regions and the expected text:

```text
OFFLINE OCR TEST
The blue train arrives at 9:30.
Ticket number: 4827
```

Android thermal status remained 0 throughout. Device battery temperature before the test was 33.4 C, battery 99%, external power connected. This is one short sequential benchmark session, not a sustained thermal test.

## Isolation and measurement scope

Separate APK/package `com.example.ppocrbenchmark`, using byte-identical snapshots of the project's four OCR/geometry Kotlin files. Snapshot identity was verified by SHA-256. Assets are read directly from the project's asset directory at build time: pp_ocrv6_det.onnx, pp_ocrv6_rec.onnx, pp_ocr_keys.txt. Dependency is the project's exact `com.microsoft.onnxruntime:onnxruntime-android:1.18.0`, default CPU sessions and thread settings. No accelerators or runtime tuning were added.

The production application source and installation were not changed. The separate benchmark package remains installed for repeat runs; its process finished. The harness has no activity, camera, speech, or language-model workload. Other phone/system activity was not controlled.

Timing uses Android's monotonic elapsedRealtimeNanos around the unmodified runOcr(bitmap), covering bitmap preprocessing, detector inference, box extraction, crop recognition, and CTC decoding. Excludes image decoding, initialization, process startup, logging, and lazy text-block grouping. Debug APK; no debugger attached. This is application pipeline latency, not a detector-only or native-kernel-only benchmark.

Input is the existing synthetic 640 x 240 three-line image from ../navidc/test.png. Full-page photographs and larger text counts can take different amounts of time. These figures do not characterize general OCR accuracy.

## Artifacts and repeat commands

- results-first.json: raw timings, output text, cache state, and thermal readings.
- instrumentation-first.txt: captured instrument output.
- device-before.txt: device, battery, and thermal information.
- source-hashes.txt: production source/model and input SHA-256 hashes.
- src/main/java/runtime: exact production source snapshots used in this run.

PowerShell, from the repository root:

```powershell
$env:JAVA_HOME = 'C:\Users\aruns\.jdks\jbr-21.0.11'
$env:GRADLE_USER_HOME = 'C:\Users\aruns\.gradle'
# For a fresh checkout, copy the root local.properties here or configure sdk.dir.
.\gradlew.bat -p benchmarks\ppocr assembleDebug --console=plain
$adbPath = 'C:\Users\aruns\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adbPath -s R5GL6478N0N install -r benchmarks\ppocr\build\outputs\apk\debug\isolated-ppocr-benchmark-debug.apk
& $adbPath -s R5GL6478N0N shell am instrument -w -e iterations 10 com.example.ppocrbenchmark/bench.OcrBenchmark
& $adbPath -s R5GL6478N0N shell run-as com.example.ppocrbenchmark cat files/results.json
```

Repeat runs reuse the benchmark's model cache, so their initialization time is not a fresh-install measurement. The source snapshots intentionally preserve this measured revision; refresh and re-hash them when benchmarking a changed production runner.
