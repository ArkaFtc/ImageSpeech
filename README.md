# ImageSpeech

Android camera reader with on-device PPOCR, UVDoc page correction, optional spoken questions, and streaming text-to-speech.

## Optional Gemini processing

Open **Processing settings** and paste a Gemini Developer API key to send photos and typed or spoken questions directly from the phone to Gemini. The key is trimmed, stored in app-private preferences, excluded from Android backup/device transfer, and never built into the APK. Clear it to return to local processing. Friends may configure the same key independently; provider quotas still apply.

A saved key can be shown as a local QR code. A friend can scan it with the camera or import a QR image through Android's photo picker. ImageSpeech accepts only its versioned Gemini-key payload, stages the decoded value in the masked field, and does not replace the saved key until **Save** is tapped. QR generation and decoding are local; the key is not sent to a QR service or logged. Anyone who receives the QR can use the key and its shared provider quota.

Gemini mode uses `gemini-3.8-flash` through the public `streamGenerateContent` HTTPS endpoint with the documented `x-goog-api-key` header and low thinking. It sends bounded inline JPEG images and, for recorded questions, inline WAV audio. It does not run OCR/dewarping first, use search grounding, or require Firebase, Vertex, accounts, or a custom server. The phone retains a bounded conversation and up to four current views; follow-ups resend that context to Gemini. **New photo** starts a new subject and clears it.

Responses appear as they stream and complete sentences are synthesized and played locally. A completed response can be replayed without another API call. Failures expose Retry and Read locally where appropriate; quota failures are not automatically retried.

## Reading

The camera stays inside a bordered viewfinder. Pinch the preview or use the −/+ controls to zoom, then tap **Take photo** to read; hold the capture button for at least 400 ms to record a question. The top-right control opens Gemini key settings. There is no reading-mode selector—the capture screen handles signs, pages, books, packages, and other images through one general flow.

Capture requests a 2560 x 1920 still with the closest supported resolution. A short sharpest-frame burst is the fallback if still capture fails. The photo remains full-size behind the review UI, and locally recognized passages are highlighted at their image coordinates. Tap a highlighted box to read that passage aloud. Gemini text has no provider-supplied geometry, so it appears in a readable overlay without fabricated bounding boxes. Stop, New photo, and Back cancel processing and playback.

PPOCR detects rotated text regions and recognizes perspective-corrected line crops. Layout ordering groups columns and passages. Low-confidence recognition gets a second crop attempt; uncertain passages remain visible and are announced as uncertain. Recognition confidence is imperfect, and formulas, clipped text, illustrations, and complex tables still need care.

## Architecture

- `capture/`: camera, gestures, recording, review, playback.
- `processing/ocr/`: page preparation, UVDoc, PPOCR, ordering, passage assembly.
- `processing/voice/`: bounded local speech synthesis.
- `remote/`: direct Gemini requests, streaming parsing, and bounded conversation state.
- `contract/`: requests, text blocks, and progress/audio events.

```kotlin
fun process(request: ProcessingRequest): Flow<ReadingEvent>
// Status -> incremental Block and Audio events -> Complete
// Request: bitmap and optional recorded PCM audio (legacy reading modes remain internal).
// Audio: passage text and a complete WAV clip.
```

PPOCR produces recognized lines, not autoregressive output tokens. The first usable passage goes to TTS while OCR continues. Bounded coroutine buffers limit lookahead; playback runs alongside event collection. Cancellation propagates through recognition, synthesis, and playback, with native inference cancelled between calls. Only completed OCR is cached, keyed by photo identity and its internal processing mode. The capture UI uses coordinate-preserving local processing so overlay boxes map back to the photographed text.

There is no bundled or downloaded local language model. All language-model interactions—including typed and spoken questions and Gemini-mode reading—go through Gemini. A spoken question is sent as inline WAV audio with the current image; ImageSpeech does not transcribe it first. With no API key, ordinary reading uses local PPOCR/UVDoc and question controls explain that Gemini is required. Upgrading from an older build removes its obsolete Gemma model file. UVDoc's pinned revision and Apache license are in `app/src/main/assets/uvdoc-NOTICE.txt` and `uvdoc-LICENSE.txt`.

## Build and test

Requires Java 21 and Android 12+. Spoken questions require microphone permission and a Gemini API key.

```powershell
$env:JAVA_HOME = 'C:\Users\aruns\.jdks\jbr-21.0.11'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

For a side-by-side device test without replacing an existing differently signed app:

```powershell
.\gradlew.bat -PreaderBenchmark=true :app:assembleDebug :app:assembleDebugAndroidTest
```

This uses application ID `com.example.test_project.reading` and label **ImageSpeech Reading Test**. The benchmark activity is debug-only.

Unit tests cover ordering, page splitting/gutter detection, overlay coordinate projection, uncertainty, early speech, bounded cancellation, routing, sentence chunking, WAV encoding, versioned QR payloads, and the shared key-change cancellation flow. Android instrumentation covers warped-book streaming latency, a larger input workload, exact rotated-line recognition, and the shared real-camera capture configuration. Grant camera permission before running `CameraCaptureTest`.

Deterministic Gemini tests use MockWebServer and cover SSE chunk boundaries, filtering thought content, authentication/request settings, quota handling, interrupted output, key-based routing, and bounded follow-up context. No real key is stored in tests. Runtime timing logs include first visible text, first synthesized passage, total generation time, and upload bytes. Live Gemini/device testing remains necessary for image-reading accuracy, microphone behavior, and the approximately five-second useful-speech target.

Benchmark details and limitations: [reading](benchmarks/reading/RESULTS.md), [dewarp comparisons](benchmarks/dewarp/RESULTS.md), [isolated PPOCR](benchmarks/ppocr/RESULTS.md). Device validation does not yet establish microphone sensitivity, live Gemini question quality, or accuracy across diverse documents.

QR validation details and outstanding physical-device checks are recorded in [docs/QR_KEY_SHARING_VALIDATION.md](docs/QR_KEY_SHARING_VALIDATION.md).
