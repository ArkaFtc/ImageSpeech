# ImageSpeech

An Android camera app for people who cannot see the screen. Take a photo of something, then work
through the text in it: **tap any block** to hear it read back word for word, or **hold and speak**
to ask a question about the shot.

Everything runs on the device. No network is used after the first-run model download.

## Two screens

**The camera.** One oversized button, because it is the only thing on the screen and it has to be
findable without sight. Pressing it opens a 300 ms burst, keeps the sharpest frame of it, and runs
OCR on that frame.

**The shot.** The text, frozen and grouped into blocks:

| You do | It does | Cost |
|---|---|---|
| **Tap** a block | Reads that block aloud, verbatim | ~200 ms to first word, no GPU |
| **Read all** | Reads every block, in order | ~200 ms to first word, no GPU |
| **Hold** Ask, say "read this", release | Same — reading never wakes the model | ~200 ms to first word, no GPU |
| **Hold** Ask, ask anything else, release | Gemma 4 E2B answers, using the image *and* the OCR text | first sentence ~1–2 s |

Every path but the last one leaves the language model asleep. That is deliberate: it is what keeps
the app responsive and the phone cool, since reading text is the common case and a vision model is
the expensive way to do it.

### Why the photo is frozen

A live preview can only answer "read it now": everything it found collapses into one utterance,
and by the time the third paragraph is spoken the camera is pointed somewhere else. Freezing the
frame is what makes the text browsable — a header can be skipped, a paragraph heard twice, and a
question asked about exactly the picture that was read.

### Where the prefill went

The expensive half of a question is encoding the frame and the OCR block, and it depends on nothing
the user has said yet. That work used to run under the user's voice during a press-and-hold. It now
starts the moment the shot opens, and again after every answer — so it is paid for with the seconds
spent listening to blocks, which is more time than a held button ever gave it.

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
| `MainActivity` | Host for both screens: the OCR engine, the voice, and the model binding |
| `CaptureFragment` | The camera, the burst capture, and the shutter |
| `ReviewFragment` | The blocks, tap-to-read, and the ask gesture |
| `TextBlocks` | Groups OCR lines into paragraphs, headings and table rows |
| `Shot` / `ShotViewModel` | Carries the frame and its OCR result between the two screens |
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

24 unit tests, covering the pure logic that is easy to get quietly wrong: sentence chunking across
token boundaries, the routing table, reading order for a cover that mixes type sizes, and the
grouping of OCR lines into blocks.

## Known gaps

- **The microphone path has never run on real hardware.** VAD thresholds in `AudioCapture` were
  chosen analytically, and `Transcriber` is unproven. Expect tuning.
- **No device capability gating.** `GemmaSceneAnswerer` disables itself only after an engine
  failure, so the first press on an unsupported device pays the load before giving up.
- **Audio into the model is unproven.** The clip is accepted without error, but it has not been
  confirmed the model attends to it rather than the transcript.
- **Blocking merges true columns that share a printed line.** `TextBlocks` deliberately joins boxes
  that overlap vertically, so a menu row reads as "espresso, three fifty" rather than two taps. On a
  genuine two-column page that puts one line from each column in the same block — which is the order
  `ReadingOrder` already produces, so it is not made worse here, but neither is it fixed.

`PLAN.md` has the full design rationale, the measurements behind these numbers, and what to test
first when you get a device.
