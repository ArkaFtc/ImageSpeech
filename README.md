# ImageSpeech

Android camera reader with on-device PPOCR, UVDoc page correction, optional spoken questions, and streaming text-to-speech.

## Reading

Choose **Quick text** for signs, **Document** for one page, or **Book** for a two-page spread. Tap to photograph and read; hold the capture button for at least 400 ms to record a question. Tap a recognized passage to replay it. Stop, New photo, and Back cancel processing and playback.

Capture requests a 2560 x 1920 still with the closest supported resolution. A short sharpest-frame burst is the fallback if still capture fails. The photo remains frozen in review. Book mode locates the central gutter and reads the left page before the right. Each page is conservatively cropped, limited to a 2000-pixel working long edge, and corrected with UVDoc. Quick text skips correction. If correction fails, recognition continues on the prepared image.

PPOCR detects rotated text regions and recognizes perspective-corrected line crops. Layout ordering groups columns and passages. Low-confidence recognition gets a second crop attempt; uncertain passages remain visible and are announced as uncertain. Recognition confidence is imperfect, and formulas, clipped text, illustrations, and complex tables still need care.

## Architecture

- `capture/`: camera, gestures, recording, review, playback.
- `processing/ocr/`: page preparation, UVDoc, PPOCR, ordering, passage assembly.
- `processing/voice/`: transcription, routing, bounded speech synthesis.
- `processing/model/`: optional scene/question answering.
- `contract/`: requests, text blocks, and progress/audio events.

```kotlin
fun process(request: ProcessingRequest): Flow<ReadingEvent>
// Status -> incremental Block and Audio events -> Complete
// Request: bitmap, optional recorded PCM audio, reading mode.
// Audio: passage text and a complete WAV clip.
```

PPOCR produces recognized lines, not autoregressive output tokens. The first usable passage goes to TTS while OCR continues. Bounded coroutine buffers limit lookahead; playback runs alongside event collection. Cancellation propagates through recognition, synthesis, and playback, with native inference cancelled between calls. Only completed OCR is cached, keyed by photo identity and reading mode. Repeated questions reuse that result. Document context retains page order and uncertainty without presenting rectified coordinates as camera coordinates.

Reading bypasses the language model. Other spoken questions use the optional downloaded model; missing model support falls back to reading. The model download is managed by WorkManager. OCR and UVDoc are bundled. UVDoc's pinned revision and Apache license are in `app/src/main/assets/uvdoc-NOTICE.txt` and `uvdoc-LICENSE.txt`.

## Build and test

Requires Java 21 and Android 12+. Recorded-PCM on-device transcription requires Android 13+; model audio support depends on the device/runtime.

```powershell
$env:JAVA_HOME = 'C:\Users\aruns\.jdks\jbr-21.0.11'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

For a side-by-side device test without replacing an existing differently signed app:

```powershell
.\gradlew.bat -PreaderBenchmark=true :app:assembleDebug :app:assembleDebugAndroidTest
```

This uses application ID `com.example.test_project.reading`, label **ImageSpeech Reading Test**, and suppresses automatic language-model download. Normal builds retain the original ID and download behavior. The benchmark activity is debug-only.

Unit tests cover ordering, page splitting/gutter detection, uncertainty, early speech, bounded cancellation, question context, gestures, intent routing, sentence chunking, and WAV encoding. Android instrumentation covers warped-book streaming latency, a larger input workload, exact rotated-line recognition, and the shared real-camera capture configuration. Grant camera permission before running `CameraCaptureTest`.

Benchmark details and limitations: [reading](benchmarks/reading/RESULTS.md), [dewarp comparisons](benchmarks/dewarp/RESULTS.md), [isolated PPOCR](benchmarks/ppocr/RESULTS.md). Device validation does not yet establish microphone sensitivity, full optional-model question quality, or accuracy across diverse documents.
