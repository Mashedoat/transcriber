package com.suj.zhuanluji

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 中英两份文字放一起:t("中文", "English")。 */
fun t(zh: String, en: String) = if (Store.english) en else zh

/** 文件布局:files/sessions/<yyyyMMdd_HHmmss>/{audio.wav, lines.json, draft.txt};files/vocab.txt。 */
object Store {
    lateinit var root: File
    private lateinit var ctx: Context

    fun init(c: Context) {
        ctx = c.applicationContext
        root = ctx.filesDir
        Engine.modelDir = File(ctx.getExternalFilesDir(null) ?: root, "models").apply { mkdirs() }
        if (!vocabFile().exists()) saveVocab(STARTER)   // 第一次用:先放一份常用词
        // VAD 只有 640KB,打包在 APK 里:公开版只需另下识别模型(用户也可能是从文件导入的模型包)
        val vad = File(Engine.modelDir, Engine.VAD)
        if (!vad.isFile) try {
            ctx.assets.open(Engine.VAD).use { i -> File(vad.path + ".part").outputStream().use { i.copyTo(it) } }
            File(vad.path + ".part").renameTo(vad)
        } catch (_: Exception) {}
    }

    fun sessions(): List<File> =
        (File(root, "sessions").listFiles()?.filter { it.isDirectory } ?: emptyList())
            .sortedByDescending { it.name }

    fun newSession(id: String) = File(root, "sessions/$id").apply { mkdirs() }

    /** 一场占多少空间(主要是 audio.wav:16k 单声道每分钟约 1.9MB)。 */
    fun sizeOf(dir: File): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** 删掉手机上这场的录音和初稿。已进电脑转录机的稿子不受影响。 */
    fun deleteSession(dir: File) = dir.deleteRecursively()

    /** 先写 .tmp 再改名:写到一半被杀,旧文件还在,不会剩半个。 */
    fun writeAtomic(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** 电池温度(°C),拿不到就 0。手机热不热主要看它,系统降频也按它。 */
    fun batteryTemp(): Float {
        val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }

    /**
     * 打开 App 时收拾上次被杀留下的残局(Suj 2026-10-29:「进度丢失、数据不可复原」是最掉 SAN 的):
     * ① 录音中途被杀,WAV 头里的长度还是 0,有的播放器会当成空文件 → 按实际大小补上;
     * ② 导入解码到一半被杀 → 副本还在就重新排上;
     * ③ 排着队、还没轮到就被杀的导入(副本在,场次还没建)→ 也重新排上。
     * 返回 (副本, 场次);场次为 null = 当新导入。
     */
    fun recover(skip: Set<File>): List<Pair<File, File?>> {
        val redo = mutableListOf<Pair<File, File?>>()
        val claimed = mutableSetOf<String>()
        for (d in sessions()) {
            try {
                val src = File(d, IMPORT_SRC)
                val copy = if (src.isFile) File(src.readText().trim()) else null
                copy?.let { claimed += it.absolutePath }
                if (d in skip) continue
                fixWavHeader(File(d, "audio.wav"))
                if (copy?.isFile == true) redo += copy to d
            } catch (_: Exception) {}
        }
        // 两分钟以内的可能是刚选的文件正在排队,别抢
        File(root, "import").listFiles()?.forEach {
            if (it.absolutePath !in claimed && System.currentTimeMillis() - it.lastModified() > 120_000L) redo += it to null
        }
        return redo
    }

    /** 副本文件名是「毫秒时间戳_原名」,还原出原名(不带扩展名)。 */
    fun importName(copy: File) = copy.name.substringAfter('_').substringBeforeLast('.')

    /** 导入中的场次里放这个文件,内容是外来音频副本的路径;解码完删掉。 */
    const val IMPORT_SRC = "import_src.txt"

    fun fixWavHeader(f: File) {
        if (!f.isFile || f.length() <= 44) return
        RandomAccessFile(f, "rw").use { raf ->
            val data = f.length() - 44
            raf.seek(40)
            val h = ByteArray(4).also { raf.readFully(it) }
            val had = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (had == data) return
            WavWriter.header(data).let { raf.seek(0); raf.write(it) }
        }
    }

    // ---- 设置 ----
    private fun prefs() = ctx.getSharedPreferences("zlj", Context.MODE_PRIVATE)
    // 默认地址在 local.properties 的 zlj.server(不进仓库)。用 IP 不用主机名:手机内置网页解析不了
    // 局域网主机名(真机 ERR_NAME_NOT_RESOLVED,2026-10-27),存过不带点的主机名的,换成默认地址
    val DEFAULT_SERVER = BuildConfig.SERVER
    var server: String
        get() = prefs().getString("server", DEFAULT_SERVER)!!
            .let { if (Regex("""^https?://[A-Za-z0-9-]+:\d+$""").matches(it) && DEFAULT_SERVER.isNotEmpty()) DEFAULT_SERVER else it }
        set(v) = prefs().edit().putString("server", v.trimEnd('/')).apply()

    /** 「我是谁」:录音的人的身份(如「投资人,来拜访对方」),电脑按语义分说话人时当锚点。 */
    var me: String
        get() = prefs().getString("me", "")!!
        set(v) = prefs().edit().putString("me", v.trim()).apply()

    /** 问过「锁屏也接着转」没有(只主动问一次,之后在菜单里)。 */
    var askedBg: Boolean
        get() = prefs().getBoolean("asked_bg", false)
        set(v) = prefs().edit().putBoolean("asked_bg", v).apply()

    var askedMe: Boolean
        get() = prefs().getBoolean("asked_me", false)
        set(v) = prefs().edit().putBoolean("asked_me", v).apply()

    /** 界面和 prompt 的语言:false 中文(默认),true 英文(Suj 2026-10-28:职业场合用)。切换后界面重建生效。 */
    var english: Boolean
        get() = prefs().getBoolean("english", false)
        set(v) = prefs().edit().putBoolean("english", v).apply()

    /**
     * 实时翻译(要联网,每句调一次 AI,所以默认关):0 不翻;1 只翻英文句子(英→中);2 只翻中文句子(中→英);3 双向。
     * 录音中途也能切,只管之后的句子。分方向是因为中英混着开会时,每边只需要看懂对方那半(Suj 2026-10-28)。
     */
    const val TR_OFF = 0; const val TR_EN = 1; const val TR_ZH = 2; const val TR_BOTH = 3

    /** 省电模式:识别只用 2 个大核(平时 4 个),发热小,字幕可能慢一点。下次载入模型时生效。 */
    var saver: Boolean
        get() = prefs().getBoolean("saver", false)
        set(v) = prefs().edit().putBoolean("saver", v).apply()
    var trMode: Int
        get() = prefs().getInt("tr_mode", if (prefs().getBoolean("translate", false)) TR_BOTH else TR_OFF)
        set(v) = prefs().edit().putInt("tr_mode", v).apply()
    val translate get() = trMode != TR_OFF

    // Each provider keeps its own credentials and endpoint; switching never reuses the other key.
    const val DEEPSEEK = "deepseek"
    const val OPENAI = "openai"
    class AiProfile(val provider: String, val auth: String, val base: String, val model: String)
    val aiProvider: String
        get() = prefs().getString("ai_provider", DEEPSEEK)!!.let { if (it == OPENAI) OPENAI else DEEPSEEK }
    private fun aiSuffix(provider: String) = if (provider == OPENAI) "_openai" else ""
    fun aiProfile(provider: String = aiProvider): AiProfile {
        val values = prefs().all
        val suffix = aiSuffix(provider)
        fun value(name: String, fallback: String = "") = (values[name + suffix] as? String).orEmpty().ifBlank { fallback }
        return AiProfile(provider, value("api_key"),
            value("api_base", if (provider == OPENAI) "https://api.openai.com/v1" else "https://api.deepseek.com"),
            value("fast_model", if (provider == OPENAI) "gpt-6-luna" else "deepseek-flash"))
    }
    fun saveAi(profiles: Collection<AiProfile>, selected: String) {
        val edit = prefs().edit().putString("ai_provider", selected)
        for (profile in profiles) {
            val suffix = aiSuffix(profile.provider)
            edit.putString("api_key" + suffix, profile.auth.trim())
                .putString("api_base" + suffix, profile.base.trim().trimEnd('/'))
                .putString("fast_model" + suffix, profile.model.trim())
        }
        edit.apply()
    }

    // Existing DeepSeek settings retain their original preference names.
    var apiKey: String
        get() = aiProfile().auth
        set(v) = prefs().edit().putString("api_key" + aiSuffix(aiProvider), v.trim()).apply()
    var apiBase: String
        get() = aiProfile().base
        set(v) = prefs().edit().putString("api_base" + aiSuffix(aiProvider), v.trim().trimEnd('/')).apply()
    /** 实时翻译、精修、问 AI 都用它。以前还有个「强模型」只给推荐词用,推荐词 2026-10-29 撤了就一起撤了。 */
    var fastModel: String
        get() = aiProfile().model
        set(v) = prefs().edit().putString("fast_model" + aiSuffix(aiProvider), v.trim()).apply()

    /** 上次和电脑同步时的词库(用来算出"手机上删了哪些")。 */
    var vocabSynced: List<String>
        get() = prefs().getString("vocab_synced", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = prefs().edit().putString("vocab_synced", v.joinToString("\n")).apply()


    /** 导入音频的原文件名(去掉扩展名)。录音机导出的名字里有地点和日期,电脑那边靠它起名。 */
    fun titleOf(dir: File): String? = File(dir, "title.txt").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
    fun saveTitle(dir: File, t: String) = File(dir, "title.txt").writeText(t)

    /**
     * 标题哪来的:file = 导入时的原文件名(老场次没记的,有标题就当是这个),ai = 精修时 AI 起的,user = 用户自己改的。
     * 精修只换 file / ai 的,用户改过的不动(Suj 2026-10-29)。
     */
    fun titleSrc(dir: File): String? =
        File(dir, "title_src.txt").takeIf { it.isFile }?.readText()?.trim() ?: if (titleOf(dir) != null) "file" else null

    fun setTitle(dir: File, title: String, src: String) {
        if (titleSrc(dir) == "file") titleOf(dir)?.let { orig ->
            // 原文件名不显示了,但留着:里面常有日期、地点,下次精修还当背景给 AI
            if (!File(dir, "orig_name.txt").isFile) File(dir, "orig_name.txt").writeText(orig)
        }
        if (title.isBlank()) File(dir, "title.txt").delete() else saveTitle(dir, title.trim())
        File(dir, "title_src.txt").writeText(src)
    }

    /** 导入的原文件名(还没被 AI 标题换掉的话就是当前标题)。 */
    fun origNameOf(dir: File): String? =
        File(dir, "orig_name.txt").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
            ?: titleOf(dir).takeIf { titleSrc(dir) == "file" }

    /**
     * 原文件名里的录音时间(录音机导出的名字常带,比导入时间准):返回 (yyyy-MM-dd, HH:mm 或 null)。
     * 认得 20261027_121812、2026-10-27 12-18、2026年10月27日 这几类。
     */
    fun recordedOf(dir: File): Pair<String, String?>? {
        val n = origNameOf(dir) ?: return null
        val m = Regex("""(20\d{2})[-_./年](\d{1,2})[-_./月](\d{1,2})日?(?:\D{1,3}(\d{1,2})[-_:.时](\d{2}))?""").find(n)
            ?: Regex("""(20\d{2})(\d{2})(\d{2})(?:[_\- T]?(\d{2})(\d{2}))?""").find(n) ?: return null
        val (y, mo, d) = m.groupValues.drop(1).take(3).map { it.toInt() }
        if (mo !in 1..12 || d !in 1..31) return null
        val h = m.groupValues[4].toIntOrNull()
        val mi = m.groupValues[5].toIntOrNull()
        val time = if (h != null && mi != null && h < 24 && mi < 60) "%02d:%02d".format(h, mi) else null
        return "%04d-%02d-%02d".format(y, mo, d) to time
    }

    // ---- 个人词库:一行一个词 ----
    /**
     * 初始小词库:只放识别模型容易写错的——中英夹杂的品牌、产品、缩写。「估值」「赛道」这种常见中文词
     * 模型本来就认得,放进来不会更准,只会拖慢(每句都要带着词库一起算)。
     * 公开版只放 5 个当样例,让人一看就知道该填什么(Suj 2026-10-28)。
     */
    val STARTER = if (BuildConfig.PUBLIC) listOf("DeepSeek", "ChatGPT", "GitHub", "KPI", "小红书") else listOf(
        "DeepSeek", "ChatGPT", "OpenAI", "Claude", "Anthropic", "Gemini", "Qwen", "通义千问", "Kimi", "豆包",
        "英伟达", "Transformer", "Agent", "AIGC", "AGI", "GPU", "SaaS", "GitHub",
        "LP", "GP", "IRR", "DPI", "Pre-IPO", "EBITDA", "ESG", "尽调", "投后",
        "KPI", "OKR", "ROI", "B端", "C端", "飞书", "小红书",
    )

    private fun vocabFile() = File(root, "vocab.txt")
    fun vocab(): List<String> =
        if (vocabFile().isFile) vocabFile().readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        else emptyList()
    fun saveVocab(words: List<String>) =
        writeAtomic(vocabFile(), words.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"))

    // ---- 精修稿(公开版:手机上调 AI 校对 + 按语义分说话人)----
    fun polishedOf(dir: File): String? = File(dir, "polished.txt").takeIf { it.isFile }?.readText()

    // ---- 逐句字幕 ----
    fun saveLines(dir: File, lines: List<Line>) = writeAtomic(File(dir, "lines.json"), linesJson(lines).toString())

    /** 坏了(写到一半被杀,老版本不是原子写)就当没有:看稿时提示「重新整理」,不至于一点开就崩。 */
    fun loadLines(dir: File): List<Line> {
        val f = File(dir, "lines.json")
        if (!f.isFile) return emptyList()
        return try { linesOf(JSONArray(f.readText())) } catch (_: Exception) { emptyList() }
    }

    fun linesJson(lines: List<Line>) = JSONArray().apply {
        for (l in lines) put(JSONObject().put("t0", l.t0.toDouble()).put("t1", l.t1.toDouble())
            .put("text", l.text).put("spk", l.spk).put("tr", l.tr))
    }

    fun linesOf(a: JSONArray): List<Line> = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Line(o.getDouble("t0").toFloat(), o.getDouble("t1").toFloat(), o.getString("text"),
            o.optInt("spk"), o.optString("tr"))
    }
}

/** 边录边写 16k 单声道 PCM16 WAV;关闭时回填长度。中途崩了也只丢头部长度,数据还在。 */
class WavWriter(file: File) {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var bytes = 0L
    private val bb = ByteBuffer.allocate(4096 * 2).order(ByteOrder.LITTLE_ENDIAN)

    fun write(buf: ShortArray, n: Int) {
        bb.clear()
        for (i in 0 until n) bb.putShort(buf[i])
        raf.write(bb.array(), 0, n * 2)
        bytes += n * 2
    }

    fun close() {
        raf.seek(0); raf.write(header(bytes)); raf.close()
    }

    companion object {
        fun header(bytes: Long): ByteArray {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            h.putInt(Engine.SR).putInt(Engine.SR * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(bytes.toInt())
            return h.array()
        }
    }
}

/** 录音服务 → 界面的消息。Activity 重建(折叠屏开合)时从 lines/status 重画。 */
object Live {
    sealed class Ev {
        data class Caption(val line: Line) : Ev()
        data class Translated(val line: Line, val tr: String) : Ev()   // 在主线程填进 line.tr 再重画
        data class Status(val text: String) : Ev()
        data class Elapsed(val sec: Int) : Ev()
        // stopped = 停下 / 出错了(进度存着):只刷新正看着的那场,别把人从首页拽过去
        data class Done(val dir: File, val fromRecord: Boolean = false, val stopped: Boolean = false) : Ev()
        object ModelsReady : Ev()                                    // 公开版:模型下载/导入完了
    }

    private val main = Handler(Looper.getMainLooper())
    var listener: ((Ev) -> Unit)? = null
    val lines = mutableListOf<Line>()       // 只在主线程读写
    var status = ""
    var elapsed = 0

    fun post(e: Ev) = main.post {
        when (e) {
            is Ev.Caption -> lines += e.line
            is Ev.Translated -> e.line.tr = e.tr
            is Ev.Status -> status = e.text
            is Ev.Elapsed -> elapsed = e.sec
            is Ev.Done, is Ev.ModelsReady -> {}
        }
        listener?.invoke(e)
    }
}
