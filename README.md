# 转录机 · Transcriber

Android 会议与访谈录音工具。字幕在手机上离线识别；需要整理对话稿、翻译或提问时，使用你自己的 DeepSeek 或 OpenAI API key。

Android 10+ · arm64-v8a · 中文 / English · 源码版本 1.2

## 能做什么

- 录音并显示离线字幕，使用 Qwen3-ASR 0.6B int8 和 sherpa-onnx。
- 导入手机上的音频，在本机转写；转写进度定期保存，支持中断后继续处理。
- 用 AI 精修对话稿、按语义标注说话人、生成标题，并保留原始逐句稿供回听核对。说话人标注不是声纹识别，数字和人名仍需核对。
- 实时中英翻译，可选择英译中、中译英或双向。
- 对当前录音或历史稿件提问；DeepSeek 使用其搜索接口，GPT 使用 OpenAI 网页搜索，出处可点击。搜索会产生额外费用。
- 手工维护个人词库。离线识别使用用户新增的最近 12 个词，精修参考更完整的词库。
- 分享文字稿为 `.txt`，导出音频，使用桌面快捷方式或小组件开始录音。

## 第一次使用

1. 先按下文从源码编译并安装。目前这个仓库没有发布可下载 APK。
2. 在 App 中下载或导入识别模型：`sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2`，压缩包约 879 MB，来源为 [sherpa-onnx 官方模型发布页](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)。下载、解压需要额外空闲空间。模型就位后，录音和转写可离线使用。
3. 想用 AI 功能，在「AI 设置」选择 DeepSeek 或 GPT，填写对应服务的 key。两家的设置分别保存，切换服务会载入该服务自己的 key；不会复用另一家的 key。
4. GPT 默认模型为 `gpt-6-luna`；DeepSeek 保留 `deepseek-flash` 默认值。服务地址和模型名称可编辑，需使用 HTTPS，并以对应服务实际可用的模型为准。
5. 长录音或转写前，检查系统是否允许 App 在后台继续运行；部分手机需要额外开启后台耗电权限。

## 隐私与联网范围

公开版包名为 `app.zhuanluji`，不连接作者的电脑或配套服务器。

- 音频、离线字幕、稿件、词库及问答记录保存在 App 私有存储。系统云备份和换机迁移已配置为排除 App 数据。卸载 App 会丢失本机数据，请自行导出需要留存的文件。
- AI key 保存在 App 私有设置中，没有内置作者密钥，也没有使用 Android Keystore 单独加密。不要向不可信的服务地址填写 key。
- 首次模型下载会访问 GitHub；模型导入后无需联网转写。
- 实时翻译、精修、问 AI、AI 起标题会把相关文字发给所选服务，可能包含词库、录音标题、参与者身份、问答上下文；不发送音频。
- 问 AI 可以联网检索，检索关键词也会发送到所选服务。每次问答最多允许两次搜索工具调用。设置中的「测试」会发送一条测试消息。
- GPT 请求设置 `store: false`，不创建供后续检索的已保存 Response；这不代表服务商没有安全日志或保留期，具体以服务商的数据政策和账户设置为准。
- 手工分享或导出文件后，接收方如何保存文件由相应 App 决定。

请在有权录音和处理内容的场景中使用。AI 精修会改写文字，涉及数字、人名和专业结论时请回听原录音。

## 从源码编译

需要 JDK 17+（本次本机构建使用 JDK 21）、Android SDK 35，以及 Python 3.9+（仅用于恢复原生依赖，无额外 Python 包）。

原生 `.so` 不在仓库内。先获取官方固定版本：

```sh
python3 scripts/fetch_native.py
# Windows 使用：py -X utf8 scripts/fetch_native.py
```

脚本下载 sherpa-onnx v1.13.8 的官方 Android 包（约 46 MB），校验其 SHA-256，只取 arm64-v8a 的四个库。也可以先自行下载，再执行 `--archive /path/to/sherpa-onnx-v1.13.8-android.tar.bz2`。

用 Android Studio 打开根目录，或设置 `ANDROID_HOME` / 本机 `local.properties` 后运行：

```sh
./gradlew assemblePubDebug
# Windows：.\gradlew.bat assemblePubDebug
```

调试包输出在 `app/build/outputs/apk/pub/debug/`。发布构建使用 `assemblePubRelease`；缺少本地 `keystore.properties` 时沿用 debug 签名，仅适合本地试用。签名文件、密码和 API key 不应提交。

Gradle Wrapper 使用官方 Gradle 8.11.1 下载地址。构建还需从配置的 Maven 仓库获取 Android/Kotlin 依赖。仓库同时保留 `suj` flavor 供本地配套开发；配套电脑服务不在本仓库，公开版使用 `pub` flavor。

## 本次验证范围

公开版 `assemblePubRelease` 与 `lintPubRelease` 通过，静态检查保留 21 项警告。GPT-6 Luna 使用虚构文本的翻译、结构化整理和带出处网页搜索接口测试通过。这里的接口测试在开发电脑运行，不等于手机端界面或完整录音流程的测试；本次未做手机端回归。

构建使用本机已有的原生库。恢复脚本已做语法检查，官方下载、校验及恢复链路未在本次环境完整验证；初次构建仍依赖能够访问上游发布文件。

## 许可证与发行范围

应用自有代码沿用 [MIT](LICENSE)。复制的 sherpa-onnx Kotlin 接口保留原版权及 Apache-2.0 许可，随附 Silero VAD 使用 MIT。详见 [第三方说明](THIRD_PARTY_NOTICES.md) 和 [licenses](licenses/)。

本仓库公开应用源码、接口绑定及 VAD 文件，不分发原生库或 APK。现有 sherpa-onnx 预编译库包含 eSpeak-ng 相关代码；其上游许可证为 GPLv3，不能仅凭 sherpa-onnx 顶层的 Apache-2.0 就把完整 APK 声明为纯 MIT。对外分发 APK 前，需解决相关许可证、对应源码和构建信息的提供，或使用经过核验、不含相关组件的 ASR 专用运行库。

## English

Transcriber records meetings and interviews, runs offline captions on Android with Qwen3-ASR 0.6B through sherpa-onnx, and imports existing recordings for on-device transcription. With your own DeepSeek or OpenAI key it can polish transcripts, translate live captions, suggest titles and answer questions with web search. Original transcripts remain available for checking.

In AI settings, choose **DeepSeek** or **GPT · Luna**. Each provider keeps its own key, endpoint and model. GPT defaults to `gpt-6-luna` and uses the Responses API with response storage disabled. Audio stays on-device; AI features send relevant transcript text and context to the selected provider. Web search can add costs. App data is excluded from Android cloud backup and device transfer; export important recordings before uninstalling.

This is a **source release**, with no downloadable APK or native binaries. Restore native dependencies using `python3 scripts/fetch_native.py`, then build `./gradlew assemblePubDebug` with JDK 17+ and Android SDK 35. The upstream native runtime includes eSpeak-ng code with GPLv3 licensing; review [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before redistributing any compiled app. The source-level MIT license does not replace third-party terms.
