# Dewarping and PPOCR phone benchmark — 2026-09-08

All requested pipelines ran successfully on the connected Samsung SM-A176U1, Android 16, SoC s5e8535. The application was unchanged; tests used a separate package, `com.example.dewarpbenchmark`.

## Standalone dewarping

Means of five measured calls after one first/warmup call. Times include processing of the full 1100 × 708 image; model initialization is separate.

| Method | Model inference + transfer | Preprocessing | Reconstruction/postprocessing | Total dewarp | Initialization |
| --- | ---: | ---: | ---: | ---: | ---: |
| DewarpNet LiteRT GPU | 0.433 s | 0.002 s | 0.666 s | 1.101 s | 1.671 s |
| DewarpNet LiteRT CPU (4 threads) | 0.798 s | 0.002 s | 0.656 s | 1.456 s | 0.305 s |
| UVDoc ONNX CPU | 0.848 s | 0.012 s | 0.015 s | 0.875 s | 0.159 s |

## Dewarp → project PPOCR chain

Means of three measured calls after one first/warmup call per method. Both models remain loaded in the same process. Initialization and image decoding are excluded. Every call reruns dewarping and OCR; results are not cached.

| Pipeline | Dewarp | OCR | Total mean | Total min–max | Recognized regions | Output characters |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| PPOCR only | 0.000 s | 20.736 s | 20.736 s | 19.641–21.937 s | 35 | 335 |
| DewarpNet LiteRT GPU | 1.384 s | 22.903 s | 24.287 s | 23.752–24.650 s | 33 | 499 |
| DewarpNet LiteRT CPU (4 threads) | 2.120 s | 24.513 s | 26.634 s | 26.434–26.867 s | 35 | 527 |
| UVDoc ONNX CPU | 1.028 s | 34.158 s | 35.186 s | 34.983–35.526 s | 40 | 1076 |

UVDoc produced more readable lines and substantially more text in this example, but all pipelines still have omissions and errors. Character/region counts are coverage diagnostics, not accuracy scores. No ground-truth transcription or CER/WER evaluation was supplied. Dewarping also changes which regions PPOCR detects and their crop widths, so OCR time itself changes. A faster chain is not necessarily a better transcription.

The input is a dense, curved two-page book photograph, not the earlier three-line synthetic image. Therefore the earlier 2.09-second PPOCR result is not directly comparable. The photo is already cut off at the top; dewarping cannot restore absent content. These models may crop or stretch it further.

## Models and runtime

- [DewarpNet-LiteRT](https://huggingface.co/litert-community/DewarpNet-LiteRT), revision `dfc68e3e462a6eb7eb8c4dbbff4049e3bd3cc9e3`, file `dewarp.tflite` (188,852,928 bytes). LiteRT `CompiledModel` 2.2.0. GPU log confirms 371/371 nodes delegated to LITERT_CL in one partition; CPU requests four threads. BGR / 255, 256 × 256 model input, 128 × 128 backward grid. Host reconstruction follows the card's blur, resize, bilinear sample, and zero-padding conventions.
- [Official PaddlePaddle UVDoc ONNX](https://huggingface.co/PaddlePaddle/UVDoc_onnx), revision `3bcf535371727d11e783101f79a504c68848aae3`, file `inference.onnx` (31,684,150 bytes). The project's ONNX Runtime Android 1.18.0, default CPU sessions/thread settings. The graph accepts dynamic full-resolution input and returns a full-resolution image; it does not force a 288 × 288 final image. BGR / 255 input and RGB output, as verified against the official PaddleX adapter.
- PPOCR: exact production source snapshots already verified in `../ppocr`, the project's bundled detector/recognizer/dictionary, ONNX Runtime Android 1.18.0 and default CPU session settings. No OCR changes or tuning.
- [Public test photograph](https://paddle-model-ecology.bj.bcebos.com/paddlex/imgs/demo_image/doc_test.jpg), used by the official UVDoc documentation. Original 1100 × 708 pixels retained throughout.

## Validation and limits

- Android UVDoc versus independent host ONNX reference: mean absolute pixel difference 0.005934 on a 0–255 scale; maximum 1.
- DewarpNet CPU/GPU predicted-grid correlation: 0.99999688. Small backend numerical differences can alter downstream OCR detections.
- Kotlin reconstruction versus independent OpenCV/NumPy implementation: mean absolute pixel difference 0.000218; maximum 1. See `validation.json` and `validate.py`.
- Thermal status was 0 throughout every recorded first and measured call. Short sequential runs, not a sustained or randomized thermal benchmark. Other phone activity was not controlled.
- Debug APK, no debugger. DewarpNet reconstruction is scalar Kotlin code, while UVDoc's grid sampling is inside the native ONNX graph. Therefore standalone full-dewarp times compare these implemented pipelines, not just neural-network architectures. Native/GPU reconstruction could reduce DewarpNet overhead. The separate model-inference column exposes this distinction.
- Chain times differ from standalone times because OCR is also resident and running between dewarp calls. Loading, decoding, PNG saving, text formatting, and logs are excluded from timed intervals. Model inference includes buffer transfer/readback, not just GPU kernel time.

## Outputs

| Input / method | Image | Recognized text | Raw chain timings |
| --- | --- | --- | --- |
| Warped input / PPOCR | [Input](baseline-chain.png) | [OCR](baseline-ocr.txt) | [JSON](baseline-chain.json) |
| DewarpNet GPU | [Flattened](dewarp-gpu-chain.png) | [OCR](dewarp-gpu-ocr.txt) | [JSON](dewarp-gpu-chain.json) |
| DewarpNet CPU | [Flattened](dewarp-cpu-chain.png) | [OCR](dewarp-cpu-ocr.txt) | [JSON](dewarp-cpu-chain.json) |
| UVDoc ONNX | [Flattened](uvdoc-chain.png) | [OCR](uvdoc-ocr.txt) | [JSON](uvdoc-chain.json) |

`*-alone.json` and `*.log` contain standalone measurements and captured instrumentation output. `hashes.txt` records model, image and source hashes. `gpu-runtime.log` records delegation. The separate APK and models remain on the phone for repeat runs.

## Repeat

From the repository root in PowerShell, with the same models staged in the benchmark package:

```powershell
$env:JAVA_HOME='C:\Users\aruns\.jdks\jbr-21.0.11'
$env:GRADLE_USER_HOME='C:\Users\aruns\.gradle'
.\gradlew.bat -p benchmarks\dewarp assembleDebug --console=plain
& benchmarks\dewarp\run-benchmarks.ps1 -Phase alone
& benchmarks\dewarp\run-benchmarks.ps1 -Phase chain
python benchmarks\dewarp\collect.py baseline-chain dewarp-gpu-chain dewarp-cpu-chain uvdoc-chain dewarp-gpu-alone dewarp-cpu-alone uvdoc-alone
python benchmarks\dewarp\summarize.py
```

The APK filename is `build/outputs/apk/debug/isolated-ppocr-benchmark-debug.apk` under this directory because the standalone Gradle project retains the earlier harness project name. Its Android package is the separate `com.example.dewarpbenchmark`. For a fresh device, install that APK, push the pinned dewarp.tflite, uvdoc.onnx and warped.jpg to /data/local/tmp/dewarp-bench, then use `adb shell run-as com.example.dewarpbenchmark cp` to copy those three files into its `files/` directory. Configure this directory's local.properties with sdk.dir before rebuilding on another machine.
