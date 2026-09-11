# NaviDC standalone Android experiment

The application source is unchanged. Test files on the phone are confined to `/data/local/tmp/navidc-bench`.

## Device observed through ADB

- Samsung SM-A176U1 (Galaxy A17 5G), Android 16, arm64.
- Mali-G68 GPU, Vulkan exposed by Android.
- `/proc/meminfo` reports MemTotal 3,532,812 kB (3.37 GiB), consistent with a nominal 4 GB device. This differs from the reported 6 GB configuration; RAM Plus is not physical RAM.

## Model and runtime

- llama.cpp b10867, commit f3f1a8f2760f28325a5ec20c05b171e5b7c83a29.
- nandraj/NaviDC-OCR-GGUF: Q4_K_M language weights (484,216,960 bytes) and Q8_0 vision component (842,193,952 bytes).
- Official Android binary failed with an attention tensor shape mismatch.
- Local native build applies the published Q/K normalization and attention shape fixes; see navidc-runtime.patch.
- Model files are local during inference; no remote OCR service is used.

## Test conditions

Synthetic 640 x 240 image, three lines, saved as test.png:

    OFFLINE OCR TEST
    The blue train arrives at 9:30.
    Ticket number: 4827

Prompt matches the user's Python script: Please output the text content from the image.
Context 1024, output limit 64 tokens, image maximum 256 tokens, greedy sampling, warmup disabled. These reduced limits are a smoke test, not full-page quality validation or a direct comparison with the laptop's three-minute measurement.

## CPU results

- Two threads: correct transcription; vision encoding 48.479 seconds. Total time and peak memory were not captured for this initial run.
- Four threads: correct transcription; process exit 0; total elapsed 49 seconds (one-second resolution); vision encoding 36.857 seconds.
- Four-thread sampled peak VmHWM: 1,363,348 kB (1.30 GiB). Sampled once per second, so this is a lower bound on the final high-water mark; it is not total phone memory usage.
- A single measured run per thread setting; no thermal or statistical characterization.

Logs: load-test.txt, ocr-cpu.txt, ocr-cpu-t4.txt, cpu-t4-memory.log.
Repeat the CPU measurement with `adb shell sh /data/local/tmp/navidc-bench/run-cpu.sh` after deploying the files.

## Vulkan results

Custom Android build, same llama.cpp revision and NaviDC patch, using Vulkan headers and SPIR-V headers from Khronos. Build commands are in build-gpu.cmd. The runtime enumerates Vulkan0 as Mali-G68 (gpu-devices.txt). GPU runs requested all language layers offloaded with `-ngl 99`; hybrid additionally used `--no-mmproj-offload`.

| Configuration | Exit | Wall time | Image encoding | Transcription |
| --- | --- | --- | --- | --- |
| CPU, 4 threads | 0 | 49 s | 36.857 s | Exact |
| Vulkan, vision and language | 124 (timeout) | 181 s | Not completed | No text |
| CPU vision, Vulkan language | 0 | 131 s | 37.040 s | Exact |

GPU full-offload sampled peak VmHWM: 1,815,864 kB (1.73 GiB). Hybrid sampled peak VmHWM: 1,374,356 kB (1.31 GiB). GPU allocations may not be fully represented in these process figures. System MemAvailable dropped below 100 MiB during both GPU experiments; memory pressure is a possible contributor to the slowdowns. These runs do not isolate compute speed, memory pressure, driver overhead or first-use pipeline compilation.

No warm repeated GPU measurements or full-page photographs were tested. The evidence establishes successful offline CPU inference on this synthetic example and no speed benefit from these two Vulkan configurations; it does not establish that all acceleration options are ineffective.

All test processes exited. Final MemAvailable: 1,365,328 kB. Test assets remain under `/data/local/tmp/navidc-bench` for repeat runs. Application code and installation were not changed.

Next useful experiment: a representative photographed page with Python reference text, followed by reducing vision memory and testing a device with confirmed higher physical RAM. No measured result here justifies switching the production app to this runtime yet.

