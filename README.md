# ImageSpeech

An Android camera app for people who cannot see the screen. Point the phone at something and
either **tap** to have its text read back word for word, or **hold and speak** to ask a question
about what the camera sees.

Everything runs on the device. No network is used after the first-run model download.

## The one gesture

| You do | It does | Cost |
|---|---|---|
| **Tap** the button | Reads the text aloud, verbatim | ~200 ms to first word, no GPU |
| **Hold**, say "read this", release | Same — reading never wakes the model | ~200 ms to first word, no GPU |
| **Hold**, ask anything else, release | Gemma 4 E2B answers, using the image *and* the OCR text | first sentence ~1–2 s |

Two of the three paths never load the language model. That is deliberate: it is what keeps the app
responsive and the phone cool, since reading text is the common case and a vision model is the
expensive way to do it.

### Why holding is not just a longer tap

The press opens a work window. The shutter and microphone start together, the sharpest frame of a
short burst goes straight into OCR, and — once the press passes 400 ms and is known not to be a tap
— the image and OCR text are encoded into the model's context. All of that finishes while you are
still speaking, so the expensive part is paid for with time you were spending anyway.

## Requirements

- **Android 12+** (`minSdk` 31). The on-device speech recognizer needs **Android 13+**; below that
  routing falls back to voice-activity detection alone.
- **arm64 with a working OpenCL GPU** for the question-answering lane — Snapdragon 8 Gen 2/3 or
  Tensor G3+ in practice. Without it the two reading lanes still work; the app says so and reads
  the text instead.
- **8 GB+ RAM.** The model is ~2 GB resident.
- **~2 GB free storage** and a Wi-Fi connection for the one-time model download.

## Build and run

```bash
export JAVA_HOME="$HOME/.jdks/jbr-21.0.11"     # Java 21; Android Studio's bundled JBR is 25,
                                               # which Gradle rejects
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

`-g` grants camera, microphone and notification permissions up front so you skip the dialogs.

### The model

On first launch the app queues a **1.92 GB download** of
[`gemma-4-E2B-it-gpu.litertlm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
(public, Apache-2.0, no token needed). It waits for Wi-Fi, resumes if interrupted, survives the app
being closed, and announces itself aloud. Reading text works normally in the meantime.

To side-load it instead:

```bash
adb push gemma-4-E2B-it-gpu.litertlm \
  /sdcard/Android/data/com.example.test_project/files/models/
```

The backend and the file are coupled. A `-gpu` bundle carries GPU weights only and fails on CPU
with `TF_LITE_PREFILL_DECODE not found in the model`; the plain `.litertlm` is the CPU build.
Changing `Backend` in `GemmaSceneAnswerer` means changing `ModelStore.MODEL_NAME` to match.

## How it fits together

| File | Responsibility |
|---|---|
| `MainActivity` | The gesture, the burst capture, and which lane a press takes |
| `IntentRouter` | Tap / read-intent / question — the three-way decision |
| `AudioCapture` | Records while held; energy-based VAD decides whether anything was said |
| `Transcriber` | Recognizes the recorded clip without touching the live microphone |
| `LocalPPOCRv6Runner` | DBNet detection → CRNN recognition, with `ReadingOrder` sorting |
| `ReadingOrder` | Top-to-bottom, left-to-right, robust to mixed type sizes |
| `Sharpness` | Laplacian variance; picks the least blurred frame of the burst |
| `SpeechQueue` | Sentence chunking and the backpressure that throttles generation |
| `SceneAnswerer` | The model lane's contract, split into prefill and ask |
| `GemmaSceneAnswerer` | LiteRT-LM binding |
| `InferenceService` | Holds the engine so it survives the Activity |
| `ModelDownloadWorker` | Resumable first-run download |

### Two design decisions worth knowing

**Generation is throttled by the voice, not run flat out.** Speech consumes 3–4 tokens/sec while
the GPU produces ~25. `SpeechQueue.speak` suspends once two utterances are pending, and that
backpressure propagates up the token `Flow` to the decoder — so it runs a sentence or two ahead and
then idles. An unthrottled 4096-token narration is minutes of continuous GPU and will thermally
throttle the phone; the same answer throttled produces less heat than an unthrottled short one.
This also makes barge-in free: a new press cancels mid-stream and nothing was wasted.

**OCR is trusted over the model.** PP-OCRv6 is far smaller than Gemma but much better at reading
text, so its output goes into the prompt as a labelled block the model is told to treat as ground
truth, with box coordinates so it can reason about columns and tables.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

19 unit tests, covering the pure logic that is easy to get quietly wrong: sentence chunking across
token boundaries, the routing table, and reading order for a cover that mixes type sizes.

## Known gaps

- **The microphone path has never run on real hardware.** VAD thresholds in `AudioCapture` were
  chosen analytically, and `Transcriber` is unproven. Expect tuning.
- **No device capability gating.** `GemmaSceneAnswerer` disables itself only after an engine
  failure, so the first press on an unsupported device pays the load before giving up.
- **Audio into the model is unproven.** The clip is accepted without error, but it has not been
  confirmed the model attends to it rather than the transcript.

`PLAN.md` has the full design rationale, the measurements behind these numbers, and what to test
first when you get a device.
