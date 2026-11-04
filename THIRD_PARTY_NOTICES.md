# Third-party components

| Component | Use | License / source |
| --- | --- | --- |
| sherpa-onnx v1.13.8 | Copied Kotlin bindings; native runtime fetched separately | [Apache-2.0](licenses/sherpa-onnx-Apache-2.0.txt), [source](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8). Original copyright headers are retained. |
| Silero VAD | Bundled `app/src/main/assets/silero_vad.onnx` | [MIT](licenses/Silero-VAD-MIT.txt), [Silero source](https://github.com/snakers4/silero-vad), [sherpa-onnx model export](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models). |
| Qwen3-ASR 0.6B | Recognition model downloaded by the user; not in this repository | [Apache-2.0 model card](https://huggingface.co/Qwen/Qwen3-ASR-0.6B), [ONNX model release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models). |
| ONNX Runtime | Used by the native runtime; not distributed here | [MIT](licenses/ONNX-Runtime-MIT.txt), [source and third-party notices](https://github.com/microsoft/onnxruntime/tree/v1.28.2). |
| Apache Commons Compress | Maven dependency for model archive extraction | [Apache-2.0](licenses/sherpa-onnx-Apache-2.0.txt), [upstream source](https://github.com/apache/commons-compress/tree/rel/commons-compress-1.27.1). |
| AndroidX / Kotlin / Gradle | Build/runtime dependencies obtained separately | Follow notices in the respective Maven artifacts and Gradle distribution. |

## Native runtime redistribution requires a separate check

The locally supplied sherpa-onnx JNI/C API libraries report version 1.13.8 and contain eSpeak phonemization code, including error strings from eSpeak-ng. The pinned upstream [sherpa-onnx CMake configuration](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/cmake/piper-phonemize.cmake) obtains Piper phonemization at `f3ff95afc03640bc1399e113e83361192a2fafb4`; that [dependency's build file](https://github.com/csukuangfj/piper-phonemize/blob/f3ff95afc03640bc1399e113e83361192a2fafb4/CMakeLists.txt) links eSpeak-ng at `7251208ee427ef87e94f39d0e99b15d287224ed6`, whose [COPYING](https://github.com/csukuangfj/espeak-ng/blob/7251208ee427ef87e94f39d0e99b15d287224ed6/COPYING) contains GPLv3. A copy is included in [licenses](licenses/eSpeak-ng-GPL-3.0.txt).

This repository intentionally does not redistribute those `.so` libraries or an APK. The restore script downloads the pinned official release for local development. Before publishing binaries, establish the applicable combined-work licensing and supply the required corresponding source and build information, or use a verified ASR-only runtime without those components. Merely adding a link to a license is not a completed binary redistribution review.

This notice documents the checked publication boundary; it is not a claim that every possible build of the upstream project has the same dependency set.
