# APK distribution and corresponding source

The Transcriber 1.2 Android APK is offered as a combined work under GNU GPL version 3, without warranty. Recipients may copy, modify and redistribute it under those terms. Read [APK-LICENSE.txt](APK-LICENSE.txt). Original application source retains its MIT grant in [LICENSE](LICENSE); individual upstream files retain their own notices and licenses.

## Download source alongside the APK

The [v1.2 Release](https://github.com/Mashedoat/transcriber/releases/tag/v1.2) provides the signed APK, `transcriber-v1.2-corresponding-source.zip`, source/component manifests and SHA-256 sums. The source archive is available without an extra charge or access restriction. It contains:

- The exact application source and Gradle build files used for this release.
- Fixed source archives for sherpa-onnx, ONNX Runtime, its Android build recipes, eSpeak-ng, Piper phonemization and other native build dependencies.
- Source JARs for the Maven dependencies recorded by the Android build, and their component manifest.
- Native build recipes and notices. Standard compilers, Android SDK/NDK, JDK and other general-purpose build tools are obtained separately.
- The same four unmodified native runtime inputs, verified against the pinned official Android archive, so rebuilding the application does not first require downloading that archive. Their hashes and official archive members are recorded in `distribution/native-binary-manifest.json`.

No author API key, private signing key, device data, personal transcript, or local PC address is required to build a modified copy. Use your own Android signing key; uninstalling an existing differently signed app can remove its data, so export recordings first if switching to a separately signed build.

## Application build

1. Unzip the application source from the corresponding-source bundle.
2. Use JDK 17+ and Android SDK 35. Set your SDK location in local, ignored `local.properties` or the environment.
3. The corresponding-source bundle already includes the verified native runtime. For a Git checkout, restore it with `py -X utf8 scripts/fetch_native.py` on Windows, or `python3 scripts/fetch_native.py` elsewhere. The version and archive checksum are pinned; `--archive` supports a separately downloaded official package.
4. Run `gradlew.bat assemblePubDebug` on Windows or `./gradlew assemblePubDebug` elsewhere. For your own release signature, supply your local `keystore.properties` and build `assemblePubRelease`.

The published APK uses the author's existing signing certificate for update compatibility. That private key is not included and is not needed to compile, sign and install your own version.

## Native build provenance

The unchanged prebuilt libraries identify the following source revisions:

| Component | Revision / version | Evidence |
| --- | --- | --- |
| sherpa-onnx | `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf` / 1.13.8 | `SherpaOnnxGetVersionStr`, `SherpaOnnxGetGitSha1`, `SherpaOnnxGetGitDate` constants in the supplied C API library; upstream release tag matches. |
| ONNX Runtime | `33ca9628233dc8f002435e868d4c2e9f82766ca1` / 1.28.2 | `ORT Build Info` in `libonnxruntime.so` contains `git-commit-id=33ca962`; the source revision resolves to the full commit above. |
| ONNX Runtime Android builder | `5cc3d2e84d9eade2562cf29a93fa3a520a75ca57` | Fixed `v1.28.2` recipe from `csukuangfj/onnxruntime-libs`, including the shared-library SONAME patch and Android flags. |
| eSpeak-ng | `ed530aa113046142eb5115cf2fc9157854d0ffe1` | sherpa-onnx's explicit `cmake/espeak-ng-for-piper.cmake` override. This takes precedence over Piper's standalone default. |
| Piper phonemization | `f3ff95afc03640bc1399e113e83361192a2fafb4` | sherpa-onnx's pinned `cmake/piper-phonemize.cmake`. |

To rebuild sherpa-onnx's Android libraries, unpack its source and use the supplied `build-android-arm64-v8a.sh` with Android NDK and CMake/Make available, `ANDROID_NDK` set, and `SHERPA_ONNX_ENABLE_C_API=ON`, as in the included upstream Android workflow. This retains the release defaults, including TTS and speaker diarization. Use the source archives and pinned declarations in the bundle for build dependencies. The ONNX Runtime builder's included workflow gives its source patch and complete Android build flags.

The bundled build recipes document the upstream binary build; this release did not recompile those native libraries or claim bit-for-bit reproduction. Application compilation and Android lint were run against the unchanged binaries. Runtime/device validation limits are stated in README.

## Redistributing modified versions

Keep applicable copyright notices and license texts, identify your modifications, and provide the corresponding source and build information alongside distributed APKs as required by GPLv3. If mirroring an APK, mirror or maintain equivalent access to its matching source bundle rather than pointing users only to an unrelated latest branch. Do not include user API keys or personal recordings in a redistributed build.
