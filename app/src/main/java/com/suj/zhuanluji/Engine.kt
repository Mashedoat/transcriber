package com.suj.zhuanluji

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** 一句话:VAD 切出来、转写过的一段。spk 在录完分离后才有(1 起)。 */
data class Line(val t0: Float, val t1: Float, val text: String, var spk: Int = 0, var tr: String = "")

/**
 * 端上识别引擎。和电脑上的 phone_pipeline.py 一一对应,参数也同步(在那边测过)。
 * 模型目录结构和 转录机/models 一致,服务端 /models/ 按同样的相对路径提供下载。
 */
object Engine {
    const val SR = 16000
    const val QWEN = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
    const val VAD = "silero_vad.onnx"
    private const val THREADS = 4          // 8 Gen 2 有 1+4 个大核
    private const val SAVER_THREADS = 2    // 省电模式:少用两个大核,发热小,每句字幕出得慢一点
    @Volatile var loadedThreads = 0        // 当前模型是用几个核载的(切了省电模式要重载才生效)
        private set
    fun threads() = if (Store.saver) SAVER_THREADS else THREADS

    lateinit var modelDir: File
    @Volatile var asr: OfflineRecognizer? = null
        private set
    private val lock = Any()

    private fun f(rel: String) = File(modelDir, rel).absolutePath

    /** 端上必须有的文件(tokenizer 目录另算)。 */
    fun required() = listOf(
        "$QWEN/conv_frontend.onnx", "$QWEN/encoder.int8.onnx", "$QWEN/decoder.int8.onnx",
        "$QWEN/tokenizer/vocab.json", "$QWEN/tokenizer/merges.txt", "$QWEN/tokenizer/tokenizer_config.json",
        VAD,
    )

    fun modelsReady() = required().all { File(modelDir, it).isFile }

    /** 载入(或带新词库重载)识别模型。约 1GB,首次要几秒,放后台线程调。 */
    @Volatile private var hotwords: List<String> = emptyList()
    private val loadLock = Any()             // 界面和录音服务可能同时要载:排队,别载两份 1GB

    /** 还没载就载;正在被别人载就等它载完。桌面「一键录音」冷启动时界面和录音服务都会调。 */
    fun ensureAsr(hotwords: List<String>) = synchronized(loadLock) { if (asr == null) loadAsr(hotwords) }

    /**
     * 识别模型只带用户自己加的最近 12 个词:词库是拼进每句提示里一起算的,电脑上实测
     * 34 个词慢 35%、60 个慢一倍、120 个慢 4.5 倍还会截断句子(2026-10-28)。
     * 完整词库(含常用词表)交给精修时的大模型去纠错。
     */
    fun asrWords(all: List<String>) = all.filter { it !in Store.STARTER }.takeLast(12)

    fun loadAsr(all: List<String>) = synchronized(loadLock) {
        val hotwords = asrWords(all)
        this.hotwords = hotwords
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 128),
            modelConfig = OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = f("$QWEN/conv_frontend.onnx"),
                    encoder = f("$QWEN/encoder.int8.onnx"),
                    decoder = f("$QWEN/decoder.int8.onnx"),
                    tokenizer = f("$QWEN/tokenizer"),
                    maxTotalLen = 1024,
                    maxNewTokens = 256,
                    // 只放短词(名字、术语)。整句会被模型硬塞进稿子,电脑上试过
                    hotwords = hotwords.joinToString(","),
                ),
                numThreads = threads(),
                provider = "cpu",
            ),
        )
        val fresh = OfflineRecognizer(null, cfg)
        synchronized(lock) {
            asr?.release()
            asr = fresh
            loadedThreads = threads()
        }
    }

    /**
     * 把识别模型从内存放下(约 1GB 还给系统)。文件留在手机存储里,下次用时重新读进来约 10 秒,不用联网。
     * 只在没录音、没在转写时调(Suj 2026-10-28 同意:App 退到后台闲着时别一直占着)。
     */
    fun release() = synchronized(loadLock) {
        synchronized(lock) {
            asr?.release()
            asr = null
            loadedThreads = 0
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private const val IDLE_RELEASE_MS = 3 * 60_000L
    @Volatile var uiVisible = false
    private val releaseIfIdle = Runnable {
        if (!uiVisible && !RecordService.running && !WorkService.running && asr != null) kotlin.concurrent.thread { release() }
    }

    /** 界面退到后台、录音或后台活做完时调:闲着 3 分钟就放下模型(期间回来就取消)。 */
    fun scheduleRelease() { main.removeCallbacks(releaseIfIdle); main.postDelayed(releaseIfIdle, IDLE_RELEASE_MS) }
    fun cancelRelease() = main.removeCallbacks(releaseIfIdle)

    fun decode(samples: FloatArray): String = synchronized(lock) {
        val r = asr ?: return ""
        val s = r.createStream()
        try {
            s.acceptWaveform(samples, SR)
            r.decode(s)
            clean(r.getResult(s).text)
        } finally {
            s.release()
        }
    }

    /**
     * 识别结果清洗:① 偶尔漏出内部标记「language X<asr_text>」;② 没声音的片段上会把词库提示原样
     * 「念」出来(真录音里出现过「某某,某某集团,某某资本,CVC LP GP」这种整串),连成串的删掉,删完只剩标点就丢。
     */
    fun clean(raw: String): String {
        var t = raw.substringAfterLast("<asr_text>").trim()
        val ws = hotwords.filter { it.isNotBlank() }.sortedByDescending { it.length }
        if (ws.isNotEmpty()) {
            val alt = ws.joinToString("|") { Regex.escape(it) }
            t = Regex("""(?:\s*[,，、 ]?\s*(?:$alt)){3,}[。.]?""").replace(t, "")
        }
        return if (t.replace(Regex("""[\s,，、。.!！?？…]"""), "").isEmpty()) "" else t.trim()
    }

    fun newVad() = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = f(VAD),
                threshold = 0.5f,
                minSilenceDuration = 0.5f,    // 访谈里的换气停顿不切开
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 12f,      // 一句最长 12s:字幕不至于憋太久,也少跨人
            ),
            sampleRate = SR,
            numThreads = 1,
        ),
    )

    fun ts(sec: Float): String {
        val s = sec.toInt()
        val h = s / 3600
        val m = s % 3600 / 60
        val ss = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
    }

    /** 手机初稿:一句一行「mm:ss 文本」。说话人不在手机上分(声纹小模型真机很糟,还最吃内存),
     *  「精修」时由 AI 按语义分。 */
    fun toDraft(lines: List<Line>): String = lines.joinToString(separator = "\n") { "${ts(it.t0)}  ${it.text}" }
}
