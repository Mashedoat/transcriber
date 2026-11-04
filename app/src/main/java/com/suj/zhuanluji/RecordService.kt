package com.suj.zhuanluji

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 前台录音服务:锁屏、切 App 都不停。
 * 录音线程:麦克风 → 写 WAV → VAD 切句 → 扔进队列(录音线程绝不等识别)。
 * 识别线程:逐句转写 → 实时字幕。
 * 停止后:等队列清空 → 「mm:ss 文本」初稿(说话人在「精修」时由 AI 按语义分)。
 */
class RecordService : Service() {
    companion object {
        const val START = "start"
        const val STOP = "stop"
        @Volatile var running = false
            private set
        @Volatile var dir: File? = null           // 正在录的这场(删历史时跳过它)
            private set

        /**
         * 十分钟没听到说话就当误触,自动停(Suj 2026-10-28)。一句都没听到的也留着录音(2026-10-29 改):
         * 说话的人离得远、声音小时 VAD 会漏,整场删掉就找不回来了。
         */
        const val IDLE_STOP_SEC = 600

        /** 在主线程调(界面或桌面跳板)。 */
        fun start(c: Context) {
            Live.lines.clear(); Live.elapsed = 0
            c.startForegroundService(Intent(c, RecordService::class.java).setAction(START))
        }
        fun stop(c: Context) = c.startService(Intent(c, RecordService::class.java).setAction(STOP))
    }

    @Volatile private var stopFlag = false
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)                         // 桌面一键录音冷启动时界面没起来过
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!running) {
                running = true
                stopFlag = false
                startForeground(1, note(t("录音中", "Recording")), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                wake = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zlj:rec").apply { acquire(6 * 3600_000L) }
                thread(name = "rec") { runSession() }
            }
            STOP -> stopFlag = true
        }
        return START_NOT_STICKY
    }

    private fun note(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", t("录音", "Recording"), NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        // 从桌面默默开录时没有界面:通知栏上直接能停
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "rec").setContentTitle(t("转录机", "Transcriber")).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).setOngoing(true)
            .addAction(Notification.Action.Builder(null, t("停止录音", "Stop recording"), stop).build()).build()
    }

    /** 录音结束后留一条能划掉的通知(自动停的时候人可能不在屏幕前)。 */
    private fun notifyAfter(text: String) {
        val pi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(2, Notification.Builder(this, "rec")
            .setContentTitle(t("转录机", "Transcriber")).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).setAutoCancel(true).build())
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, note(text))
        Live.post(Live.Ev.Status(text))
    }

    private class Seg(val start: Int, val samples: FloatArray)
    private val POISON = Seg(-1, FloatArray(0))

    @SuppressLint("MissingPermission")   // 进来前 MainActivity 已拿到录音权限
    private fun runSession() {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = Store.newSession(id)
        RecordService.dir = dir
        val lines = mutableListOf<Line>()
        try {
            // 桌面「一键录音」冷启动时模型还在载(十来秒):先录着,切好的句子在队列里等,载好后补进字幕
            val loader = if (Engine.asr == null && Engine.modelsReady()) thread(name = "load") {
                try { Engine.ensureAsr(Store.vocab()) } catch (_: Throwable) {}
            } else null
            val withAsr = Engine.asr != null || Engine.modelsReady()
            val wav = WavWriter(File(dir, "audio.wav"))
            // VAD 打包在 APK 里,没有识别模型也照样跑:用来判断「多久没人说话」
            val vad = try { Engine.newVad() } catch (_: Throwable) { null }
            var spoke = false                        // 听到过说话没有
            var lastSpeech = 0L                      // 最后一次听到说话的位置(采样点)
            val queue = LinkedBlockingQueue<Seg>()
            // 翻译单独一条线程排队发:网慢时字幕不等翻译;连续失败就安静地停,不刷屏。
            // 每句看当时的翻译方向(录音中途能切);切过方向就再给一次机会
            val tr = if (withAsr) Executors.newSingleThreadExecutor() else null
            var trFails = 0
            var trMode = Store.trMode
            var lastPart = System.currentTimeMillis()
            val asrThread = thread(name = "asr") {
                var done = false
                while (!done) {
                    var s = queue.take()
                    if (s === POISON) break
                    // 积压了(手机转得比说得慢,比如英文短句一句接一句):把排队的几句拼成一段一起转,
                    // 字幕就不会越拖越久(真机反馈「说完会卡」)。拼接处垫 0.3s 静音,最长 20s。
                    while (true) {
                        val nx = queue.peek() ?: break
                        if (nx === POISON) { queue.poll(); done = true; break }
                        val gap = nx.start - (s.start + s.samples.size)
                        if ((nx.start + nx.samples.size - s.start) > Engine.SR * 20 || gap > Engine.SR * 3) break
                        queue.poll()
                        val pad = FloatArray(Engine.SR * 3 / 10)
                        s = Seg(s.start, s.samples + pad + nx.samples)
                    }
                    // 模型还在载:等它(句子在队列里排着,载好后一口气补上);载失败就只留在录音里
                    while (Engine.asr == null && loader?.isAlive == true) Thread.sleep(100)
                    if (Engine.asr == null) continue
                    val txt = Engine.decode(s.samples)
                    if (txt.isEmpty()) continue
                    val ln = Line(s.start.toFloat() / Engine.SR, (s.start + s.samples.size).toFloat() / Engine.SR, txt)
                    synchronized(lines) { lines += ln }
                    // 录到一半被杀(过热、没电、强制停止):字幕不白出,「重新整理」从最后一句接着转剩下的录音
                    if (System.currentTimeMillis() - lastPart > 30_000) {
                        lastPart = System.currentTimeMillis()
                        try { Post.savePart(dir, s.start + s.samples.size, synchronized(lines) { lines.toList() }) } catch (_: Exception) {}
                    }
                    Live.post(Live.Ev.Caption(ln))
                    val mode = Store.trMode
                    if (mode != trMode) { trMode = mode; trFails = 0 }
                    if (Ai.wantTranslate(txt, mode) && (!BuildConfig.PUBLIC || Llm.ready())) tr?.execute {
                        if (trFails >= 3) return@execute
                        try {
                            Live.post(Live.Ev.Translated(ln, Ai.translate(txt)))
                            trFails = 0
                        } catch (e: Exception) {
                            if (++trFails == 3) Live.post(Live.Ev.Status(
                                if (BuildConfig.PUBLIC || Llm.ready()) t("翻译连不上(${e.message?.take(40)}),先只出原文字幕", "Translation unreachable (${e.message?.take(40)}), showing original text only")
                                else t("翻译连不上电脑,先只出原文字幕", "Can't reach PC for translation, showing original text only")))
                        }
                    }
                }
            }
            fun drain() {
                val v = vad ?: return
                while (!v.empty()) {
                    val seg = v.front()
                    spoke = true
                    lastSpeech = maxOf(lastSpeech, seg.start.toLong() + seg.samples.size)
                    if (withAsr) queue.put(Seg(seg.start, seg.samples))
                    v.pop()
                }
            }

            // 模拟器包专用:files/demo.wav(16k 单声道)在就拿它当麦克风、按真实速度喂,读完补静音
            val demo = if (BuildConfig.EMU) File(filesDir, "demo.wav").takeIf { it.exists() }
                ?.let { java.io.RandomAccessFile(it, "r").apply { seek(44) } } else null
            val minBuf = AudioRecord.getMinBufferSize(Engine.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            fun newRec() = AudioRecord(MediaRecorder.AudioSource.MIC, Engine.SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, Engine.SR * 2))
            var rec = if (demo != null) null else newRec()
            fun recLine() = when {
                Engine.asr != null -> t("录音中 · 实时字幕开", "Recording · live captions on")
                withAsr -> t("录音中 · 模型载入中,字幕马上来", "Recording · model loading, captions coming soon")
                else -> t("录音中 · 模型未就绪,只录音", "Recording · no model yet, audio only")
            }
            var errSince = 0L                        // 麦克风从什么时候开始一直报错
            var retries = 0
            var micDead = false
            var silenced = false                     // 麦克风被别的 App 占着(比如来电),录到的是静音
            var lastCheck = 0L
            val buf = ShortArray(512)
            val fb = FloatArray(512)
            var total = 0L
            var lastSec = -1
            update(recLine())
            if (loader != null) thread(name = "load-wait") {
                loader.join()
                if (Engine.asr != null && !stopFlag) update(t("录音中 · 实时字幕开", "Recording · live captions on"))
            }
            var idleStop = false
            rec?.startRecording()
            val t0 = System.nanoTime()
            while (!stopFlag) {
                val n = if (demo != null) demoRead(demo, buf, total, t0) else rec!!.read(buf, 0, buf.size)
                if (n < 0) {
                    // 麦克风报错(音频服务重启之类):以前在这里空转,看着在录、其实一秒都没存。现在过 1.5 秒重开,
                    // 重开 5 次还不行就收尾,前面录的照常存好
                    if (errSince == 0L) errSince = System.currentTimeMillis()
                    Thread.sleep(20)
                    if (System.currentTimeMillis() - errSince > 1500) {
                        if (++retries > 5) { micDead = true; break }
                        update(t("麦克风断了,正在重连…", "Mic dropped, reconnecting…"))
                        try { rec?.stop() } catch (_: Exception) {}
                        rec?.release()
                        rec = newRec().apply { startRecording() }
                        errSince = 0L
                    }
                    continue
                }
                errSince = 0L
                if (n == 0) continue
                if (retries > 0) { retries = 0; update(recLine()) }   // 重连上了
                val now = System.currentTimeMillis()
                if (rec != null && now - lastCheck > 5000) {
                    lastCheck = now
                    val s = rec?.activeRecordingConfiguration?.isClientSilenced == true
                    if (s != silenced) {
                        silenced = s
                        update(if (s) t("麦克风被别的应用占着(比如通话),这会儿录到的是静音", "Another app has the mic (a call?), recording silence for now") else recLine())
                    }
                }
                wav.write(buf, n)
                total += n
                val v = vad
                if (v != null) {
                    for (i in 0 until n) fb[i] = buf[i] / 32768f
                    v.acceptWaveform(if (n == fb.size) fb else fb.copyOf(n))
                    if (v.isSpeechDetected()) lastSpeech = total
                    drain()
                    if (total - lastSpeech > Engine.SR.toLong() * IDLE_STOP_SEC) { idleStop = true; break }
                }
                val sec = (total / Engine.SR).toInt()
                if (sec != lastSec) { lastSec = sec; Live.post(Live.Ev.Elapsed(sec)) }
            }
            try { rec?.stop() } catch (_: Exception) {}       // 麦克风坏掉时 stop 会抛,别因此丢了收尾
            rec?.release(); demo?.close()
            wav.close()
            vad?.flush(); drain(); vad?.release()
            update(t("录音已存好,正在补完最后几句字幕…", "Audio saved, finishing the last few captions…"))
            queue.put(POISON)
            asrThread.join()
            tr?.shutdown(); tr?.awaitTermination(20, TimeUnit.SECONDS)
            Thread.sleep(200)                 // 让最后几条译文在主线程落进 line.tr
            Store.saveLines(dir, lines)
            File(dir, Post.PART).delete()

            if (lines.isNotEmpty()) postprocess(dir, lines)
            if (micDead) notifyAfter(t("麦克风一直打不开,录音停在 ${Engine.ts(total.toFloat() / Engine.SR)}。前面录的都存好了",
                "The mic kept failing, so recording stopped at ${Engine.ts(total.toFloat() / Engine.SR)}. Everything before that is saved"))
            if (idleStop) notifyAfter(
                if (spoke) t("${IDLE_STOP_SEC / 60} 分钟没听到说话,自动停了。录音已保存", "No speech for ${IDLE_STOP_SEC / 60} minutes, stopped automatically. Recording saved")
                else t("${IDLE_STOP_SEC / 60} 分钟没听到说话,自动停了。录音留着:要是其实有人说话(离得远、声音小),打开这场点「重新整理」再转一遍,用不着就删掉",
                    "No speech for ${IDLE_STOP_SEC / 60} minutes, stopped automatically. The audio is kept: if someone was actually talking (far away, quiet), open it and tap \"Re-process\"; otherwise delete it"))
            if (lines.isEmpty()) update(when {
                Engine.asr != null -> t("录音已保存(没听到说话)", "Recording saved (no speech heard)")
                withAsr -> t("录音已保存。模型没来得及载好,可以「重新整理」在手机上转", "Recording saved. Model didn't finish loading in time — \"Re-process\" to transcribe on the phone")
                else -> t("录音已保存。下载模型后点「重新整理」就能转写", "Recording saved. Download a model, then \"Re-process\" to transcribe")
            })
        } catch (e: Throwable) {
            // 录音中途出错:字幕记成断点,「重新整理」会从最后一句往后把剩下的录音转完(而不是只留半场字幕当成整场)
            try {
                val got = synchronized(lines) { lines.toList() }
                if (File(dir, "lines.json").isFile) Store.saveLines(dir, got)
                else Post.savePart(dir, ((got.lastOrNull()?.t1 ?: 0f) * Engine.SR).toInt(), got)
                Store.fixWavHeader(File(dir, "audio.wav"))
            } catch (_: Exception) {}
            update(t("出错了:${e.javaClass.simpleName}: ${e.message}", "Error: ${e.javaClass.simpleName}: ${e.message}"))
        } finally {
            running = false
            RecordService.dir = null
            wake?.release()
            Live.post(Live.Ev.Done(dir, fromRecord = true))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Engine.scheduleRelease()                 // 桌面默默录完、界面不在前台:闲 3 分钟放下模型
        }
    }

    /** 录完:写手机初稿。 */
    private fun postprocess(dir: File, lines: List<Line>) = Post.run(dir, lines) { update(it) }

    /** 演示音频按真实时间读一块(和麦克风一样快),读完填静音。 */
    private fun demoRead(f: java.io.RandomAccessFile, buf: ShortArray, done: Long, t0: Long): Int {
        val ahead = done * 1_000_000_000L / Engine.SR - (System.nanoTime() - t0)
        if (ahead > 0) Thread.sleep(ahead / 1_000_000)
        val b = ByteArray(buf.size * 2)
        val got = maxOf(0, f.read(b))
        java.util.Arrays.fill(buf, 0)
        for (i in 0 until got / 2) buf[i] = ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort()
        return buf.size
    }
}

object Post {
    /**
     * 整段录音(录时没模型、或导入的外来音频)切句转写。**边读边转**:每次只从文件读 0.5 秒,
     * 内存跟录音长短无关(以前整段读进内存,2 小时的 m4a 直接 OOM,真机 2026-10-27)。
     * 相邻句间隔 <1s 且合起来 ≤12s 的拼成一段转,少些碎句。
     * **断点续转**:每 20 秒把转好的句子和转到哪存进 [PART];中途被强制停止 / 杀掉,再点「重新整理」
     * 从断处接着转(Suj 2026-10-29 两小时实测:强制停止后发现白转最掉 SAN)。转完由调用方删掉 [PART]。
     * 录音时的实时字幕也往同一个文件里存(见 RecordService),录到一半被杀也能从字幕断处接着转。
     * **太烫就歇**:电池 ≥[HOT]°C 先停下,凉到 [COOL]°C 再接着转——烫着硬转系统也会降频,慢得像爬。
     * stop() 返回 true(用户点了「停下」)就存好进度退出。
     */
    const val PART = "lines_part.json"
    const val HOT = 42f
    const val COOL = 38f

    class Stopped : IllegalStateException(t("停下了,进度存好了。再点「重新整理」从断处接着转", "Stopped, progress saved. Tap \"Re-process\" to continue from there"))

    /** at = 转到第几个样本(之前的都转完了),lines = 这之前的句子。 */
    fun savePart(dir: File, at: Int, lines: List<Line>) =
        Store.writeAtomic(File(dir, PART), org.json.JSONObject().put("at", at).put("lines", Store.linesJson(lines)).toString())

    /** 断点:转到哪(秒),没有就 -1。 */
    fun partAt(dir: File): Int = try {
        org.json.JSONObject(File(dir, PART).readText()).getInt("at") / Engine.SR
    } catch (_: Exception) { -1 }

    fun transcribeWhole(wav: File, stop: () -> Boolean = { false }, progress: (String) -> Unit): List<Line> {
        val dir = wav.parentFile!!
        val part = File(dir, PART)
        val out = mutableListOf<Line>()
        var base = 0                                  // 从第几个样本接着转
        try {
            if (part.isFile) org.json.JSONObject(part.readText()).let { base = it.getInt("at"); out += Store.linesOf(it.getJSONArray("lines")) }
        } catch (_: Exception) { base = 0; out.clear() }
        var lastSave = System.currentTimeMillis()
        var done = base                               // 这之前的都转完、记进 out 了
        val vad = Engine.newVad()
        var pendStart = -1                            // 都是整段录音里的绝对位置(VAD 给的是从 base 起算)
        var pend = FloatArray(0)
        fun flushPend() {
            if (pendStart < 0) return
            // 模型被放下了还接着转,会把后面全记成「没说话」:宁可停下(进度在)
            if (Engine.asr == null) error(t("识别模型不在内存里,停下了。进度存好了,再点「重新整理」接着转", "Speech model isn't loaded, stopped. Progress saved; tap \"Re-process\" to continue"))
            val t = Engine.decode(pend)
            val end = pendStart + pend.size
            if (t.isNotEmpty()) out += Line(pendStart.toFloat() / Engine.SR, end.toFloat() / Engine.SR, t)
            pendStart = -1; pend = FloatArray(0)
            // 存在一句的句尾:之后的声音还在 VAD 里没出来,续转时从这里重读,不会漏
            done = end
            if (System.currentTimeMillis() - lastSave > 20_000) {
                lastSave = System.currentTimeMillis()
                savePart(dir, done, out)
            }
        }
        var lastTemp = 0L
        fun coolDown() {
            if (System.currentTimeMillis() - lastTemp < 10_000) return
            lastTemp = System.currentTimeMillis()
            var c = Store.batteryTemp()
            if (c < HOT) return
            savePart(dir, done, out)
            while (c > COOL && !stop()) {
                progress(t("手机有点烫(${"%.1f".format(c)}°C),先歇一会儿,凉到 ${COOL.toInt()}°C 自动接着转。进度存好了",
                    "Phone is warm (${"%.1f".format(c)}°C), pausing until it cools to ${COOL.toInt()}°C, then it continues. Progress saved"))
                Thread.sleep(15_000)
                c = Store.batteryTemp()
            }
        }
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front(); vad.pop()
                val st = base + s.start
                val gap = st - (pendStart + pend.size)
                if (pendStart >= 0 && gap < Engine.SR && st + s.samples.size - pendStart <= Engine.SR * 12) {
                    pend = pend + FloatArray(gap.coerceAtLeast(0)) + s.samples
                } else {
                    flushPend()
                    pendStart = st; pend = s.samples
                }
            }
        }
        val raf = java.io.RandomAccessFile(wav, "r")
        val total = ((raf.length() - 44) / 2).toInt()
        if (base > total) { base = 0; done = 0; out.clear() }
        if (base > 0) progress(t("接着上次转到的 ${Engine.ts(base.toFloat() / Engine.SR)} 往下转…",
            "Resuming from ${Engine.ts(base.toFloat() / Engine.SR)}, where it stopped last time…"))
        raf.seek(44 + base * 2L)
        val bytes = ByteArray(Engine.SR)              // 0.5 秒
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var read = base
        var lastPct = -1
        try {
            while (read < total) {
                if (stop()) throw Stopped()
                coolDown()
                val n = minOf(bytes.size / 2, total - read)
                raf.readFully(bytes, 0, n * 2)
                bb.rewind()
                vad.acceptWaveform(FloatArray(n) { bb.short / 32768f })
                drain()
                read += n
                val pct = (read * 100L / total).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    progress(t("手机上转写… $pct%(${Engine.ts(read.toFloat() / Engine.SR)} / ${Engine.ts(total.toFloat() / Engine.SR)})",
                        "Transcribing on phone… $pct% (${Engine.ts(read.toFloat() / Engine.SR)} / ${Engine.ts(total.toFloat() / Engine.SR)})"))
                }
            }
            vad.flush(); drain(); flushPend()
        } catch (e: Throwable) {
            try { savePart(dir, done, out) } catch (_: Exception) {}      // 出什么错都先把进度落盘
            throw e
        } finally {
            raf.close(); vad.release()
        }
        return out
    }

    /** 手机初稿 = 一句一行带时间,不分说话人(「精修」时由 AI 按语义分)。 */
    fun run(dir: File, lines: List<Line>, progress: (String) -> Unit) {
        Store.saveLines(dir, lines)
        File(dir, "draft.txt").writeText(Engine.toDraft(lines))
        progress(t("手机初稿好了 · ${lines.size} 句。点「精修」整理成对话稿", "Phone draft ready · ${lines.size} lines. Tap \"Polish\" to organize into a dialogue"))
    }
}
