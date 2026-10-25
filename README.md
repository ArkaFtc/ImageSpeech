# ImageSpeech

Android camera reader with on-device PPOCR, UVDoc page correction, optional spoken questions, and streaming text-to-speech.

## Optional on-device Gemma

ImageSpeech never uses a cloud API or an API key. Open **Processing settings** and tap **Download Gemma** to fetch Google's Gemma 4 E2B model (`gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes) from the `litert-community` Hugging Face repo. The download runs once, under WorkManager, on Wi-Fi only, resumes after interruptions, and is accepted only when the byte count is exact. The file lives in the app's private external files directory and is deleted on uninstall or with **Delete Gemma**.

Once the model is present, reading and typed or spoken questions run on the phone through [LiteRT-LM](https://ai.google.dev/edge/litert-lm/android) (`litertlm-android` 0.18.1). Photos and questions never leave the device. Settings depend on the phone's memory (`GemmaProfile`). Phones reporting at least 6.5 GB of RAM load Gemma in the background at startup, try the GPU backend first and fall back to CPU, and use a 4,096-token context. Smaller phones use the CPU backend only, a 2,048-token context, and load Gemma after the photo is taken, once the camera is released. On a 4 GB Galaxy A17 (SM-A176U1), the GPU backend got the app killed for low memory every time; on the CPU it stayed alive, with the first answer after launch taking about 25–40 seconds and follow-ups about 4–5 seconds to first text. If Android kills the app for low memory while Gemma is working, the next launch turns Gemma off (`GemmaHealth`) and settings offer **Try Gemma again**. Thinking is disabled. Model settings and the shared system prompt live in `ondevice/GemmaConfig.kt`.

Gemma mode does not run OCR or dewarping first. Spoken questions are turned into text while the button is held, using the phone's on-device speech recognizer when it has one (`capture/SpeechInput.kt`); the words fill the question box as they are recognised and are sent to Gemma as a typed question. Text is much lighter for Gemma than audio. Phones with no speech recognizer fall back to passing 16 kHz WAV audio to Gemma. After a photo, nothing is read aloud automatically: the review screen offers **Read all text**, **Summarize**, and typed or spoken questions (Summarize and questions need Gemma). Without Gemma, local OCR still finds and highlights the text silently. Gemma starts processing a photo as soon as it is taken: the conversation is opened with the photo as a complete first exchange (the photo, then a fixed reply, "I can see the photo. What would you like to know?") and processed up front (`prefillPrefaceOnInit`), separately from the automatic read. Questions are then sent exactly as asked, never in the same message as a photo; another view of the same subject goes in as its own exchange. On a real photo, leaving the photo as the last message, sending it together with the question, or wrapping the question in a label such as "User question:" made Gemma repeat the question back instead of answering (`GemmaEchoDiagnosticTest`). That work runs outside any single request, so asking a question while the read is still going stops the read but keeps the processed photo; the question waits for processing already under way rather than starting over. On a 4 GB Galaxy A17, processing a photo takes about 21–26 seconds, after which questions get first text in about 2 seconds. One conversation is kept per subject, so follow-ups reuse the photo the model has already processed. Completed turns are also kept on the phone; an interrupted, failed, or overlong conversation is rebuilt from them, trimming the oldest turns while keeping the photo. **New photo** starts a new subject.

Answers appear as they stream and complete sentences are spoken locally. A completed answer can be replayed without generating it again. Failures offer Retry and Read locally. Deleting the model stops active work, frees the engine, and returns to local OCR.

## Reading

The camera stays inside a bordered viewfinder. Pinch the preview or use the −/+ controls to zoom, then tap **Take photo** to read; hold the capture button for at least 400 ms to record a question. The top-right control opens processing settings, where Gemma can be downloaded or deleted. There is no reading-mode selector—the capture screen handles signs, pages, books, packages, and other images through one general flow.

Capture requests a 2560 x 1920 still with the closest supported resolution. A short sharpest-frame burst is the fallback if still capture fails. The photo remains full-size behind the review UI, and locally recognized passages are highlighted at their image coordinates. Tap a highlighted box to read that passage aloud. Gemma text has no image coordinates, so it appears in the text box at the top without fabricated bounding boxes. The red Stop button at the top stops talking and stays on the photo, so you can ask something else; it does not interrupt the silent photo preparation. LiteRT-LM 0.18.1 cannot continue a conversation once an answer is cancelled part way (every later message is cancelled at once, measured on device), so a stopped answer is not cancelled: it finishes unheard in the background and the next request waits for it (`ModelSession.settle`). If it would take more than 20 seconds, it is cancelled and the conversation is rebuilt from the saved turns. Because Gemma finished that answer, it counts it as given; asking the same thing again can get "I have already read that". `GemmaStopDeviceTest` covers Stop followed by another question. New photo and Back cancel processing and playback. The camera screen has one button, Take photo, which only takes the photo; typed and spoken questions are asked on the review screen.

**Summarize** gives one to three sentences, depending on how much text the photo has. Questions get one or two short sentences. After a summary or an answer to a question, **More detail** asks Gemma, in the same conversation, for a longer answer in plain sentences. Markdown symbols Gemma still produces (asterisks, `#` headings, bullets, backticks, backslashes) are removed before text is shown or spoken (`ondevice/PlainText.kt`), so the voice never says "asterisk". **Repeat** speaks whatever the text box at the top shows (the last answer, or the passage last tapped) with the phone's voice, without another Gemma request. Gemma's answers appear only in that box; they have no position in the photo, so nothing is drawn over it. The small **A** / big **A** buttons on the review screen, also in Processing settings, change the size of all text in the app (85–200%, on top of the phone's font size) and are remembered. At 130% and above, Read all text and Summarize stack vertically so words are not broken. The app draws edge to edge, so it handles the keyboard itself: the screen ends above the keyboard, and while typing the answer box takes the photo's space and the other controls hide. Send on the keyboard asks the question.

PPOCR detects rotated text regions and recognizes perspective-corrected line crops. Layout ordering groups columns and passages. Low-confidence recognition gets a second crop attempt; uncertain passages remain visible and are announced as uncertain. Recognition confidence is imperfect, and formulas, clipped text, illustrations, and complex tables still need care.

## Architecture

- `capture/`: camera, gestures, recording, review, playback.
- `processing/ocr/`: page preparation, UVDoc, PPOCR, ordering, passage assembly.
- `processing/voice/`: bounded local speech synthesis.
- `ondevice/`: Gemma download, LiteRT-LM engine, conversation state, and streaming answers.
- `contract/`: requests, text blocks, and progress/audio events.

```kotlin
fun process(request: ProcessingRequest): Flow<ReadingEvent>
// Status -> incremental Block and Audio events -> Complete
// Request: bitmap and optional recorded PCM audio (legacy reading modes remain internal).
// Audio: passage text and a complete WAV clip.
```

PPOCR produces recognized lines, not autoregressive output tokens. The first usable passage goes to TTS while OCR continues. Bounded coroutine buffers limit lookahead; playback runs alongside event collection. Cancellation propagates through recognition, synthesis, and playback, with native inference cancelled between calls. Only completed OCR is cached, keyed by photo identity and its internal processing mode. The capture UI uses coordinate-preserving local processing so overlay boxes map back to the photographed text.

Without the Gemma model, ordinary reading uses local PPOCR/UVDoc and question controls explain that Gemma is required. With it, all reading and questions use Gemma; **Read locally** remains available. UVDoc's pinned revision and Apache license are in `app/src/main/assets/uvdoc-NOTICE.txt` and `uvdoc-LICENSE.txt`.

## Build and test

Requires Java 21 and Android 12+. Spoken questions require microphone permission and the downloaded Gemma model. Gemma needs roughly 3 GB of free storage and a phone with enough memory to hold the model (8 GB RAM or more is a sensible starting point).

```powershell
$env:JAVA_HOME = 'C:\Users\aruns\.jdks\jbr-21.0.11'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

For a side-by-side device test without replacing an existing differently signed app:

```powershell
.\gradlew.bat -PreaderBenchmark=true :app:assembleDebug :app:assembleDebugAndroidTest
```

This uses application ID `com.example.test_project.reading` and label **ImageSpeech Reading Test**. The benchmark activity is debug-only.

Unit tests cover ordering, page splitting/gutter detection, overlay coordinate projection, uncertainty, early speech, bounded cancellation, routing, sentence chunking, and WAV encoding. Android instrumentation covers warped-book streaming latency, a larger input workload, exact rotated-line recognition, and the shared real-camera capture configuration. Grant camera permission before running `CameraCaptureTest`.

Deterministic Gemma tests use a scripted fake model (no native code) and cover streaming text and ordered speech, follow-ups that reuse encoded images, additional views, failures with partial text, cancellation and stale-turn suppression, conversation rebuilding and trimming, spoken questions passed as audio, replay without the model, download completeness checks, and lane routing. Runtime logs report first visible text, first synthesized passage, total generation time, and input bytes. Real-device testing with the downloaded model is still needed for reading accuracy, speed, memory use, and the GPU/CPU fallback.

Benchmark details and limitations: [reading](benchmarks/reading/RESULTS.md), [dewarp comparisons](benchmarks/dewarp/RESULTS.md), [isolated PPOCR](benchmarks/ppocr/RESULTS.md). Device validation does not yet establish microphone sensitivity, on-device Gemma answer quality, or accuracy across diverse documents.

## Speech reliability

Text could show without being read aloud. Causes found and fixed:

- The phone's speech service can be paused or killed in the background. The next passage was then refused, which ended the answer's speech. `SpeechSynthesizer` now restarts the engine and tries the passage once more. A passage that never finishes times out after 15 seconds as an ordinary failure; it used to time out as if the user had stopped, silencing the rest of the answer.
- A passage that still cannot be voiced is skipped, and the rest of the answer is still spoken (Gemma and local reading).
- `AudioPlayer` skips a passage that cannot play (no audio focus, a player error, or another app taking the speaker) instead of throwing. An exception there ended the review screen's playback loop, so nothing more was spoken until the screen was reopened, and some errors crashed the app.
- Verified on the phone: `SpeechSynthesizerDeviceTest` force-stops the speech service mid-run and every later passage is still produced. In the app, answers kept being spoken after the speech service was force-stopped between two questions; the first sentence was about 3 seconds slower while it restarted.

## Profile and dietary checks

On first launch a profile page opens over the camera (Save or Skip for now). It can be edited any time from the person button in the top-left corner of the camera screen. The user types dietary restrictions or allergies in their own words, such as "allergic to walnuts" or "I don't eat meat".

- Stored only on the phone, in app-private storage (`profile/Profile.kt`). It is excluded from cloud backup and device transfer (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`). Gemma runs on the phone, so it never leaves the device.
- When a photo is taken and restrictions are saved, Gemma checks it right away (`ProcessingAction.DIET_CHECK`) and says whether it looks safe and what conflicts. More detail works after it.
- Photos that have nothing to do with food or drink stay silent: Gemma replies "NOT FOOD", and the processor holds back the first words until it can tell, so that reply is never shown or spoken.
- Taking a photo with a spoken question answers the question instead. Without Gemma there is no check.
- Measured on the phone (`GemmaDietDeviceTest`): an EXIT sign stayed silent. Ingredient labels were judged correctly: banana bread flagged for walnuts and milk and safe for no meat; chicken soup flagged for meat and safe for walnuts and milk. A cookie box photo with no ingredient list got "cannot tell for sure". An earlier wording ("if this is not food, reply NOT FOOD") silenced that cookie box, so the opt-out now applies only when the photo has nothing to do with food at all.
- The page tells users that checks can miss things and to read the label themselves for a serious allergy.
