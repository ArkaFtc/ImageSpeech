# Hold to Ask — ImageSpeech on-device build plan

A press-and-hold camera gesture that answers questions about what the phone is looking at: Gemma 4 E2B on the GPU, PP-OCRv6 for text, and a router that keeps the expensive model asleep most of the time.

**Verdict:** the plan holds. Verification changed one thing materially, relaxed one constraint I'd flagged, and confirmed the rest. The biggest correction is that the audio path locks you to Google's official model bundle.

## Status

> **Update, 31 August 2026 — the interaction changed.** The single press-and-hold gesture this plan
> is built around has been replaced by two screens: a camera with a shutter, then a review screen
> where the recognized text is offered as tappable blocks and the ask button lives. Section 2 is
> kept as the record of why the original gesture was shaped the way it was; what shipped is
> described in `README.md`. Everything downstream of the gesture survived the change intact — the
> routing table (section 3), the prompt contract (section 4) and the voice-throttled output
> (section 5) all still describe the app, and the prefill window of section 2 still exists, just
> opened by the review screen rather than by the length of a press.

| Phase | State |
|---|---|
| 1 — LiteRT-LM runtime | **Written, untested on device.** `litertlm-android:0.16.1` resolved; `Engine`/`EngineConfig` on the GPU backend in `GemmaSceneAnswerer`. |
| 2 — Streaming TTS with backpressure | **Done.** `SpeechQueue`, `SentenceChunker`, driven from OCR. |
| 3 — Press-and-hold capture and router | **Done.** `AudioCapture`, `Sharpness`, `Transcriber`, `IntentRouter`, rewritten `MainActivity`. |
| 4 — Gemma with image, OCR, transcript | **Written, untested on device.** `GemmaSceneAnswerer` + `GemmaTurn`. |
| 5 — Raw audio into the model | **Written, untested on device.** Clip goes in as `Content.AudioBytes`. |

Everything compiles and the 14 unit tests pass. Phases 2 and 3 are additionally **verified running
on an emulator** — both read lanes route correctly and OCR reads a held-up book cover:

```
hold=196ms  sharpness=839.0  -> READ_VERBATIM (tap)
hold=1527ms sharpness=1049.8 -> READ_VERBATIM (held, but no speech detected)
```

The second line is the case duration-based routing could not catch: a long hold with nothing spoken
into it reads verbatim instead of reaching the model.

Phase 1 is **partially verified**: the 1.92 GB bundle is pushed to the emulator, LiteRT-LM parses it
and resolves its sections, and `Engine.initialize()` then fails with
`FAILED_PRECONDITION: Can not find OpenCL library on this device` — the x86_64 emulator has no
OpenCL. The failure path itself is confirmed working: `GemmaSceneAnswerer` catches it, sets
`unusable`, disables the lane without retrying on later presses, and the app falls back to reading
with no crash.

One encouraging detail from the loader warnings: it looks for a `TF_LITE_AUDIO_ENCODER_HW` section,
which is consistent with the audio tower being present in Google's bundle (phase 5).

**Phases 4 and 5 have now run.** Swapping to the CPU backend and the plain `gemma-4-E2B-it.litertlm`
bundle produced a real answer on the emulator, from a live camera frame:

> The image shows a person holding a magazine or book with a title on it. The title reads,
> "How I Killed Pluto and Why It Had It Coming" by Mike Brown.

The title is quoted in correct reading order, so the model used the OCR block rather than reading
the image itself — the prompt contract and the "trust this block" instruction both work. The clip
was passed as `Content.AudioBytes` and was accepted without error, which is a positive signal for
phase 5 though not proof the audio was attended to; the transcript was present too.

That answer came back in roughly **10 seconds** on an x86 emulator CPU — far better than the 2–5
tok/s figure the plan assumed for CPU. Worth revisiting the assumption that a CPU backend is never
worth offering: on real arm64 silicon it may be a usable fallback for short answers rather than
something to refuse outright. It would still be the wrong default for a full-page narration.

Still unverified on real hardware: decode speed under the GPU backend, TTS backpressure across a
long answer, and thermals.

### Two bugs this found

- **Images cannot go in the system instruction.** `createConversation` fails with
  `INVALID_ARGUMENT: Provided more images than expected in the prompt`. The frame and OCR block are
  now the conversation's opening user turn (`Message.user(...)`), which still gets prefilled by
  `prefillPrefaceOnInit`. This would have failed identically on a real device.
- **Backend and model file are coupled.** The `-gpu` bundle carries GPU weights only; on CPU it
  fails with `TF_LITE_PREFILL_DECODE not found in the model`. There is no graceful fallback between
  variants — changing `Backend` means changing `ModelStore.MODEL_NAME` to match.

Build toolchain: **Gradle 8.14.5**, Kotlin 2.3.0, JDK 21 (`.jdks/jbr-21.0.11` — Android Studio's
bundled JBR is Java 25, which Gradle rejects).

**To try the model lane**, push Google's official bundle (the open-source export path has no audio
encoder, so it must be theirs):

```
adb push gemma-4-E2B-it-gpu.litertlm \
  /sdcard/Android/data/com.example.test_project/files/models/
```

The file is the 1.92 GB GPU build from
[litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
— public, Apache-2.0, no token needed. Pick the `-gpu` variant to match the `Backend.GPU()` engine
config; the plain `.litertlm` (2.47 GB) and the vendor NPU builds are for other backends.

Without it, `ModelStore.isPresent` is false, `UnavailableSceneAnswerer` is wired in instead, and
lane 3 says so and falls back to a verbatim read — the same behaviour a device that cannot run the
GPU backend gets permanently.

### Two things the implementation changed

- **Kotlin 2.0.21 → 2.3.0.** Forced: `litertlm-android` ships class metadata at version 2.3, and
  2.0.21 fails with an internal compiler error. This also required migrating `kotlinOptions` to the
  `compilerOptions` DSL.
- **Prefill is gated on the tap threshold, not the press.** Opening a turn on every press would
  make taps — the common case, which never reaches the model — pay for an image encode they throw
  away. `ConversationConfig(prefillPrefaceOnInit = true)` fires 400 ms into a hold instead, which
  is the point at which the press is known not to be a tap.

---

## 1. What verification changed

### Audio-to-Gemma works — but only from Google's prebuilt bundle · CHANGES THE PLAN

The open-source `litert-torch export_hf` path exports text and vision components only; there is no audio encoder export. Google's official `litert-community/gemma-4-E2B-it-litert-lm` bundle *does* ship audio sections, produced by an internal converter. Use that artifact as-is and audio works.

The consequence is strategic: **you cannot fine-tune the model and keep audio input.** If a tuned model ever becomes part of the roadmap, the audio tower is what you give up.

### 128K context — the budget concern I raised was unfounded · CONSTRAINT REMOVED

E2B carries a 128K window and shares KV cache across a 20/35 layer ratio. Image tokens + OCR block + audio + 4096 output tokens is a rounding error against that. Drop it from the risk list; size the session for headroom and stop thinking about it.

### The Kotlin API is Flow-based, making TTS backpressure nearly free · CONFIRMED

`Conversation.sendMessageAsync(message)` returns a `Flow<String>` of tokens alongside a callback variant. A slow collector suspends the flow, so throttling generation to the speech cursor is not a feature you build — it is what happens when you `collect` at the rate TTS drains. The thermal design falls out of idiomatic Kotlin.

### Thinking is opt-in via a token, so "no thinking" is the default · CONFIRMED

Reasoning is enabled by placing `<|think|>` at the start of the system prompt. Omit it. Nothing else to configure — no flag to hunt for, no output to strip before it reaches the speech queue.

---

## 1a. When you get an Android phone

### What the device needs

- **arm64, Android 12+** (`minSdk` is 31). The on-device recognizer path in `Transcriber` needs
  **Android 13+**; below that the transcript is null and routing falls back to VAD alone.
- **8 GB+ RAM** and a **working OpenCL GPU** — Adreno or Mali. Snapdragon 8 Gen 2/3 or Tensor G3+
  is the realistic floor. This is the exact thing the emulator lacked.

### Setup

1. Developer options → **USB debugging** on.
2. Push the GPU bundle (note: `-gpu`, matching `ModelStore.MODEL_NAME` and `Backend.GPU()`):
   ```
   adb push gemma-4-E2B-it-gpu.litertlm \
     /sdcard/Android/data/com.example.test_project/files/models/
   ```
3. `./gradlew :app:assembleDebug && adb install -r -g app/build/outputs/apk/debug/app-debug.apk`

No code changes are needed — the checked-in config is already GPU + `-gpu`.

### Test in this order — the mic path first

**The entire microphone path has never executed.** The emulator has no mic input, so VAD never
fired and routing was forced by hand for the model test. Expect this to be where the surprises are:

1. **Does a spoken question route to the model at all?** Hold, say "what colour is this", release.
   The log line reports the decision — check `hasSpeech` is true and the transcript is non-empty.
2. **VAD thresholds.** `MIN_VOICED_MS`, `NOISE_MULTIPLE` and `ABSOLUTE_FLOOR` in `AudioCapture` were
   picked analytically, never against a real microphone. A quiet room and a noisy street are both
   worth trying.
3. **`Transcriber`.** The API 33 file-source recognizer is the single least-certain piece of code in
   the app. If it returns null every time, routing still works off VAD — but you lose read-intent
   detection, so "read this" would start reaching the model.
4. **Then the model lane**: GPU init, decode speed, whether the first sentence is audible in ~2 s.
5. **Then a long narration**: is TTS backpressure actually holding the GPU duty cycle down, and does
   the phone stay cool over several minutes?
6. **Then audio-in** (phase 5): whether the clip is genuinely attended to, not merely accepted.

### What is still missing before this is shippable

- **No device gating.** `GemmaSceneAnswerer` disables itself only *after* an engine failure. There
  is no up-front capability check, so the first press on a weak device pays the full load before
  giving up.

Two earlier gaps are now closed and verified on the emulator:

- **First-run download** — `ModelDownloadWorker` fetches the 1.92 GB bundle under WorkManager,
  waiting for Wi-Fi, resuming with a range request, and renaming into place only once the byte
  count matches exactly. Verified end to end: `Worker result SUCCESS`, 2,008,432,640 bytes.
- **Foreground service** — `InferenceService` owns the engine so it survives the Activity, and
  promotes itself only when a question is actually about to load the model, so a session that never
  asks one never shows a notification.

Building the download surfaced a bug worth remembering: WorkManager runs its *own*
`SystemForegroundService`, which merges in from the library with no `foregroundServiceType`.
Declaring the type on our service was not enough — the platform rejected `startForeground` and the
crash landed on the main thread, killing the process mid-download. The manifest now overrides
WorkManager's service with `android:foregroundServiceType="dataSync"`.

---

## 2. Interaction: one gesture, four beats

*Superseded — see the update at the top of Status. The prefill argument below is why the two-screen
version still opens a turn before it is asked anything; the four beats themselves no longer happen
in one press.*

The button press is not just an input event — it is the start of a prefill window. Everything expensive that does not depend on the question happens while the user is still asking it.

### 0 ms — press: shutter and microphone fire together

Capture a 2–3 frame burst over the first ~300 ms and keep the sharpest by Laplacian variance. A blind user has no preview to steady against, and blur costs OCR far more than it costs scene description. Committing to one frame this early is what unlocks the prefill window — the burst buys blur protection without giving that up.

`AudioRecord.start()` · `ImageCapture` burst · sharpness pick

### ~300 ms — hold: OCR and vision prefill run under the user's voice

PP-OCRv6 runs on the chosen frame while the LiteRT-LM session prefills the image and the OCR block. On-device `SpeechRecognizer` runs in parallel on CPU — it does not contend with the GPU. By release, the dominant latency cost has already been paid with time the user was spending anyway.

PP-OCRv6 det + rec · image prefill · `SpeechRecognizer`

### release — routing: route on speech, not on duration

Stop recording, then decide. A hold under ~400 ms is a tap — the deliberate "just read it" gesture, no audio analysis needed. Anything longer gets VAD plus the transcript. Your instinct that hold length carries intent is right as an *affordance*; it is unreliable as a *detector*, so it decides only the tap case.

VAD energy gate · transcript confidence · intent match

### +0 ms — speak: first audio before generation finishes

The read path is instant — OCR text goes straight to the speech queue. The Gemma path starts speaking at the first sentence boundary and generates the rest underneath playback. Either way the perceived latency is the same, and it does not grow with the length of the answer.

sentence chunker · TTS queue · barge-in cancel

---

## 3. Routing: three lanes, and Gemma owns only one

The routing table is where the thermal budget is actually won. Two of three lanes never wake the model at all, and they are the common cases.

| Trigger | Destination | Cost |
|---|---|---|
| hold < 400 ms, **or** VAD finds no speech | OCR → TTS, verbatim | ~200 ms to first word, no GPU |
| transcript matches read-intent ("read this", "what does it say") | OCR → TTS, verbatim | ~200 ms to first word, no GPU |
| speech present, intent is anything else | **Gemma 4 E2B** — image + OCR + audio + transcript | first sentence ~1–2 s, GPU throttled to speech rate |

**Lane 1** is the default gesture. Tap and the page is read back exactly as PP-OCRv6 extracted it — no paraphrase, no dropped lines, no model load.

**Lane 2** is the correction from our earlier exchange, now explicit: asking for a page to be read must not route to Gemma. Re-emitting text OCR already has costs minutes of GPU and introduces paraphrase where you need fidelity. A short keyword allowlist covers this; misses fail gracefully into lane 3, which can also read a page — just slower.

**Lane 3** is scene questions, cross-referencing text against what is around it, summarizing, restructuring multi-column or tabular reading order. This is the lane that justifies the model, and it is the minority of real queries.

---

## 4. Prompt contract

Prompt order is not cosmetic. Everything above the boundary is independent of the question, so it can be prefilled during the hold; everything below waits for release. Keep them in this order or the optimization disappears.

```
// system — no <|think|> token, so reasoning stays off
system: You answer questions about what the camera sees, for a
        listener who cannot see the screen. Prefer the OCR block
        for anything quoted verbatim.

┌─ prefilled during the hold ──────────────────────────┐
image:  <sharpest frame, downscaled>

ocr:    <lines in reading order, with box coordinates>
        PP-OCRv6 is more accurate than your own reading.
        Treat this block as ground truth for exact text.
└──────────────────────────────────────────────────────┘

audio:  <raw clip, ~6 tokens per second of speech>
text:   <SpeechRecognizer transcript, for logging and fallback>
```

Two notes on the OCR block. Label it and tell the model to trust it — otherwise a VLM will happily "correct" a specialized detector that is right. And include coordinates: `OcrResult` currently discards `Box` after `sortedInReadingOrder()`, and a flat line list gives the model no way to reason about columns, which is exactly what you want it for.

On feeding both audio and transcript: audio is cheap in prefill and preserves emphasis the text bottleneck drops. The transcript costs almost nothing, and you need it anyway — for the intent match, for logging, and as the fallback if the audio tower disappoints.

---

## 5. Output: 4096 tokens without cooking the phone

You were right to reject the token cap. The resolution is not a bigger ceiling — it is letting playback set the pace. Speech consumes tokens roughly seven times slower than the GPU produces them, so a collector that suspends on the TTS queue idles the GPU most of the time.

| Metric | Value |
|---|---|
| Decode, Snapdragon 8 Gen 3 | ~25 tok/s |
| Speech consumes | 3–4 tok/s |
| **Resulting GPU duty cycle** | **~15%** |
| Max output | 4096 tokens |

- **Collect, don't drain.** Suspend the Flow collector until the TTS queue falls below ~2 pending utterances. Unthrottled, a 4096-token narration is 2–3 minutes of continuous GPU and will throttle the device; throttled, it produces less heat than an unthrottled 500-token answer.
- **Chunk on sentence boundaries.** Never hand TTS a partial word. Buffer until a terminator, then enqueue.
- **Barge-in is free.** A new button press cancels the collector mid-stream. Nothing is wasted because nothing ran ahead.
- **Keep the engine warm.** Loading ~1.5 GB per query is fatal. Hold the `Engine` in a foreground service; build a fresh `Conversation` per query.

---

## 6. Build order — riskiest last, shippable throughout

Each phase leaves the app in a working state, and the piece most likely to fail is isolated at the end where it cannot strand the rest.

### Phase 1 — Land LiteRT-LM, text only

Add the dependency, wire the first-run model download with WorkManager, stand up `Engine` and `EngineConfig` against the GPU backend. No camera, no audio, no OCR — prove the runtime loads and streams on your actual target device.

> **Gate:** tokens streaming to logcat on device, GPU backend confirmed active.

### Phase 2 — Streaming TTS with backpressure, on the OCR path

Sentence chunker, TTS queue with `UtteranceProgressListener`, suspend-on-full collector, barge-in cancellation. Drive it from PP-OCRv6 output only. This phase alone ships a genuinely better reader app than what is in the repo today, with Gemma nowhere in the loop.

> **Gate:** a long page reads end to end, interruptible mid-sentence.

### Phase 3 — Press-and-hold capture and the router

Replace the tap button in `MainActivity` with hold-to-talk. Frame burst and sharpness selection, `AudioRecord` during hold, VAD plus `SpeechRecognizer`, three-lane routing. Lane 3 stubs to a toast. Both read lanes are live and correct before the model is ever consulted.

> **Gate:** tap reads; a spoken question routes to the stub without misfiring.

### Phase 4 — Gemma with image, OCR, and transcript

Assemble the prompt in contract order, prefill the image and OCR block during the hold, feed the transcript at release. Text-based questions answered against the scene. This is the feature working — audio input is still not involved.

> **Gate:** first sentence audible within ~2 s; sustained load stays off the throttle.

### Phase 5 — Raw audio into the model

Swap the transcript for the clip itself, keeping the transcript alongside. Deliberately last: it depends on Google's bundle shipping working audio sections through the Android runtime, which is the one claim here resting on a converter you cannot inspect. If it does not hold, phases 1–4 are unaffected and you keep the transcript path permanently.

> **Gate:** audio-in matches or beats transcript-in on accented and noisy speech.

---

## 7. What can still go wrong

| Risk | Severity | Handling |
|---|---|---|
| GPU backend behaves differently across vendors; CPU fallback is 2–5 tok/s and unusable | **High** | Gate the Gemma lane on a device check at first run. Below the bar, ship the two OCR lanes only — the app stays useful. |
| Audio sections in the official bundle don't expose through the Android runtime | Medium | Isolated in phase 5. Transcript path is already shipping and stays as the permanent fallback. |
| Foreground service holding ~1.5 GB gets killed under memory pressure | Medium | Detect eviction and reload on next press with an audible "one moment". Consider the sub-1 GB 2-bit build on tighter devices. |
| ~1.5 GB first-run download over cellular, or storage refusal | Medium | WorkManager on unmetered-preferred, resumable, with spoken progress. Never in `assets/` — you already carry 138 MB of ONNX there. |
| Motion blur defeats OCR on a handheld page | Medium | Burst plus sharpness selection in phase 3. If variance stays below threshold across the burst, say so rather than reading garbage. |
| Read-intent keyword match misfires | Low | Fails into the Gemma lane, which can also read a page. Slower and less verbatim, but not wrong. |

---

## 8. Changes to what's already in the repo

- **Raise `minSdk`.** It sits at 24 in `app/build.gradle.kts`, well below what the GPU backend needs.
- **Keep boxes in `OcrResult`.** `LocalPPOCRv6Runner` sorts by reading order then throws the geometry away. The prompt wants it.
- **Drop one OCR stack.** ML Kit text recognition and PP-OCRv6 are both wired in. PP-OCRv6 is the stronger one and the one the plan depends on.
- **Move capture off the tap handler.** `btnTakePicture.setOnClickListener` becomes a touch listener with press and release beats.
- **Replace `QUEUE_FLUSH` speaking.** The current single-shot `speak()` call becomes the queue and chunker from phase 2.

---

## Sources

Performance figures are from LiteRT-LM benchmarks at 1024 prefill / 256 decode, 2048 context — measure at your real context length before trusting them.

- [Audio encoder export status](https://github.com/google-ai-edge/litert-torch/issues/1039)
- [Official E2B bundle](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- [LiteRT-LM Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md)
- [Gemma 4 model overview](https://ai.google.dev/gemma/docs/core)
- [LiteRT-LM performance](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)
