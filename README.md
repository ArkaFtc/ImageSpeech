# ImageSpeech

An Android app that photographs text, records optional spoken questions, and reads the result aloud. Recognition and question answering run on the device after the initial model download.

## Two parts

- **`capture/`** owns the camera, sharpest-frame selection, press gestures, microphone recording, frozen-image review, and audio playback.
- **`processing/`** owns OCR, transcription, question routing, the language model, and speech synthesis. Its `ocr/`, `model/`, and `voice/` packages group the implementation by responsibility.

The shared **`contract/`** package defines the boundary:

```kotlin
suspend fun process(request: ProcessingRequest): ProcessingResponse
// Request: Bitmap + optional RecordedAudio (16 kHz mono PCM16)
// Response: recognized text blocks + Flow<SpokenAudio>
// Each SpokenAudio contains a sentence's text and complete WAV bytes.
```

The response is an actual audio stream, not a stream of text for the UI to synthesize. Collecting it starts transcription, answer generation, and synthesis. Playback controls consumption; a one-segment buffer keeps synthesis ahead without allowing an entire answer to accumulate. Cancelling collection stops generation and synthesis. Temporary audio files are deleted after use.

These are package boundaries within one Android application, not separate processes or network services. `MainActivity` composes the processor and screens and binds the model service. Capture code does not invoke OCR, transcription, or model APIs.

## Controls

- **Tap the camera button for less than 400 ms:** capture an image and read its text. Recorded audio is discarded before creating the processing request.
- **Hold for 400 ms or longer and speak:** capture an image and send the recorded question with it. The same cutoff applies to the review screen's Ask button.
- **Tap a recognized block:** read just that block. **Read all** reads the whole photo.
- **Stop and resume camera**, **New photo**, or system Back: cancel the answer and return to the live preview.
- Cancelled touches discard the recording. Keyboard and accessibility click actions perform an image-only read.

The camera selects the sharpest frame from a 300 ms burst, then unbinds the camera before opening review. The displayed photograph stays frozen through processing, speech, and after speech finishes until the user returns to capture. The same image can be reused for questions without rerunning OCR. Leaving the app stops recording and playback.

Short read commands such as “read this” use verbatim OCR. Other spoken questions use the model. Missing model support falls back to reading; silent recordings receive a microphone or no-question notice.

## Build and verification

Requires Android 12+ and Java 21. On-device transcription from recorded PCM requires Android 13+; older devices can send the audio directly to the model. The language model needs a compatible GPU and sufficient memory/storage; OCR works independently.

```powershell
$env:JAVA_HOME = 'C:\Users\aruns\.jdks\jbr-21.0.11'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Unit tests cover reading order, text grouping, sentence chunking, intent routing, the exact 400 ms boundary, and PCM-to-WAV encoding.

Physical-device verification is still needed for microphone sensitivity, on-device recognizer support, model audio understanding, audio latency, and interruption behavior. The app downloads the model once through WorkManager; download progress remains visible in the banner and system notification.
