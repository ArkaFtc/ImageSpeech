# Streaming document reader validation

Device: Samsung SM-A176U1, Android 16. Runtime: the app's ONNX Runtime 1.18.0, bundled PPOCR and official UVDoc ONNX; Android TTS and MediaPlayer.

The initial streaming implementation processed the public warped 1100 x 708 book fixture in Book mode. Per-trial measurements are in `initial-latency.json`. The first trial emitted a passage at 4.406 s, produced WAV audio at 4.938 s, and started MediaPlayer at 5.194 s. Full recognition and synthesis took 43.498 s. Timing starts at processing, excluding camera capture and app/model startup. Playback start is a software callback, not a microphone measurement of audible speaker onset. Later audio clips were synthesized but not played in this benchmark.

This supports early listening while OCR continues; it does not guarantee a fixed latency on every document. The first passage in the fixture is clipped and misrecognized, so time to first audio is not a measure of accuracy. The initial centered split also cut some right-page letters. The current implementation adds gutter location to address that geometry issue.

After the gutter refinement, three device instrumentation tests passed (`instrumentation-final.txt`): full book streaming, a 2560-pixel upscaled input workload, and exact recognition of two synthetic lines rotated by -8 degrees.

| Final book trial | First passage | Audio ready | Playback start | Full recognition + synthesis |
| --- | --- | --- | --- | --- |
| 1 | 3.480 s | 4.105 s | 4.155 s | 45.623 s |
| 2 | 3.819 s | 4.268 s | 4.349 s | 46.807 s |
| 3 | 4.000 s | 4.274 s | 4.330 s | 48.120 s |

Mean playback start was **4.28 s**, while the full run averaged **46.85 s**. The larger 2560 x 1648 input took **9.301 s** to playback (`photo-sized-latency.json`); upscaling adds workload, not detail. The refined first passage reads "combination formulas to work out the chances of the". Prose improved, but formulas and illustration text still contain errors; no character-error-rate claim is made.

The latest shared real-camera configuration passed its device test (1 test). The final side-by-side app and instrumentation APK both build, all **40 JVM tests pass**, and lint completes without errors. Existing lint warnings remain. The updated APK was installed successfully.

The side-by-side test app preserves the existing app and its data because their signing certificates differ. Detailed test outputs and JSON are retained in this directory.
