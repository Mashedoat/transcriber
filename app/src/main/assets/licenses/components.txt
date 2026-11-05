# Third-party components

**Transcriber 1.2 APK distribution:** the combined APK is offered under GNU GPL version 3, without warranty. Original app source remains MIT; upstream copyright and license notices remain in force. Read [APK-LICENSE.txt](APK-LICENSE.txt) and [DISTRIBUTION.md](DISTRIBUTION.md). The [v1.2 Release](https://github.com/Mashedoat/transcriber/releases/tag/v1.2) supplies matching source and build materials alongside the APK. About → Licenses provides offline notices.

| Component | Use | License / source |
| --- | --- | --- |
| sherpa-onnx v1.13.8 | Kotlin bindings and APK native runtime | [Apache-2.0](licenses/sherpa-onnx-Apache-2.0.txt), [source](https://github.com/k2-fsa/sherpa-onnx/tree/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf). Original copyright headers are retained. |
| Silero VAD | Bundled `app/src/main/assets/silero_vad.onnx` | [MIT](licenses/Silero-VAD-MIT.txt), [Silero source](https://github.com/snakers4/silero-vad), [sherpa-onnx model export](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models). |
| Qwen3-ASR 0.6B | Recognition model downloaded by the user; not in this repository | [Apache-2.0 model card](https://huggingface.co/Qwen/Qwen3-ASR-0.6B), [ONNX model release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models). |
| ONNX Runtime 1.28.2 | APK native runtime | [MIT](licenses/ONNX-Runtime-MIT.txt), [source and third-party notices](https://github.com/microsoft/onnxruntime/tree/33ca9628233dc8f002435e868d4c2e9f82766ca1). |
| Apache Commons Compress | Maven dependency for model archive extraction | [Apache-2.0](licenses/sherpa-onnx-Apache-2.0.txt), [upstream source](https://github.com/apache/commons-compress/tree/rel/commons-compress-1.27.1). |
| AndroidX / Kotlin / Gradle | Build/runtime dependencies obtained separately | Follow notices in the respective Maven artifacts and Gradle distribution. |

## Native runtime distribution

The unchanged sherpa-onnx JNI/C API libraries report version 1.13.8 and contain eSpeak phonemization code. The effective eSpeak-ng revision is **`ed530aa113046142eb5115cf2fc9157854d0ffe1`**, explicitly selected by sherpa-onnx's `cmake/espeak-ng-for-piper.cmake`. This overrides Piper's standalone default. Piper phonemization is pinned to `f3ff95afc03640bc1399e113e83361192a2fafb4` (MIT). eSpeak-ng's GPLv3 text is included in [licenses](licenses/eSpeak-ng-GPL-3.0.txt).

Native `.so` files remain excluded from Git; the signed GPLv3 APK and its corresponding source are distributed as release attachments. Source materials include pinned sherpa-onnx, ONNX Runtime, Piper, eSpeak-ng, kaldi-native-fbank, kaldi-decoder, OpenFst, simple-sentencepiece, JSON and hclust-cpp archives, dependency manifests and Android build recipes. Optional upstream build dependencies retain their own notices. Apache Commons and Maven runtime source materials retain their LICENSE/NOTICE files.

Native revision identification and application compilation do not establish a bit-for-bit native rebuild; no such rebuild is claimed. See DISTRIBUTION.md for source availability, build instructions and redistribution terms.
