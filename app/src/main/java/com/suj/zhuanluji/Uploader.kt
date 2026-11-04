package com.suj.zhuanluji

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 和电脑打交道:把一场备份过去(录音压成 Opus + 文字稿,分块续传)、字幕翻译、词库同步;
 * 「下载录音」也用这里的 Opus 压缩。
 * 德国→国内实测只有几十 KB/s,所以 ① 先压(24kbps,1 小时约 11MB,原始 WAV 是 115MB)
 * ② 分块、断了从服务器说的位置接着传。
 */
object Uploader {
    private const val CHUNK = 1024 * 1024    // 跨境往返 ~300ms,块大一点少几次握手;断了照样续传
    private const val BITRATE = 24000        // 说话 24kbps 足够清楚:1 小时约 11MB

    /** 返回 (文件, 扩展名)。手机没 Opus 编码器就退回原始 WAV。 */
    fun compress(dir: File, progress: (String) -> Unit): Pair<File, String> {
        val ogg = File(dir, "audio.ogg")
        val wav = File(dir, "audio.wav")
        if (ogg.isFile && ogg.length() > 0) {
            // 老版本直接往 audio.ogg 里写,压到一半被杀会留下半截,之后一直被当成压好的拿去备份、导出。
            // 看时长对不对得上原始 WAV,差 3 秒以上就是半截,重压
            if (!wav.isFile || oggComplete(ogg, wav)) return ogg to "ogg"
            ogg.delete()
        }
        if (!hasOpus()) return wav to "wav"
        progress(t("压缩录音…", "Compressing audio…"))
        val tmp = File(dir, "audio.ogg.part")       // 压完才改名:被杀不留半截
        try {
            encodeOpus(wav, tmp, progress)
            if (!tmp.renameTo(ogg)) error("rename")
            return ogg to "ogg"
        } catch (e: Exception) {
            tmp.delete(); ogg.delete()
            progress(t("压缩失败,改传原始录音(${e.javaClass.simpleName})", "Compression failed, sending original audio instead (${e.javaClass.simpleName})"))
            return wav to "wav"
        }
    }

    private fun oggComplete(ogg: File, wav: File): Boolean {
        val wavSec = (wav.length() - 44) / 2.0 / Engine.SR
        val oggSec = try {
            val ex = android.media.MediaExtractor()
            try {
                ex.setDataSource(ogg.absolutePath)
                val f = ex.getTrackFormat(0)
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1e6 else null
            } finally { ex.release() }
        } catch (_: Exception) { 0.0 }                       // 读不了 = 坏的
        // 拿不到时长:退回看大小(24kbps 的 Opus 约是 WAV 的 1/10,小于 1/40 肯定是半截)
        return if (oggSec != null) oggSec >= wavSec - 3 else ogg.length() * 40 > wav.length()
    }

    private fun hasOpus() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
        ci.isEncoder && ci.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, true) }
    }

    private fun encodeOpus(wav: File, out: File, progress: (String) -> Unit) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Engine.SR, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        val raf = RandomAccessFile(wav, "r")
        val totalBytes = raf.length() - 44
        raf.seek(44)
        var fed = 0L
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        var lastPct = -1
        val tmp = ByteArray(64 * 1024)
        try {
            // 编码器一次只吃 20ms;以前每轮都白等 10ms 看有没有输出,1 小时录音光等就半小时(真机实测巨慢)。
            // 现在:能喂就一直喂、有输出就一直取,两头都没进展时才睡 1ms。
            while (!outputDone) {
                var progressed = false
                while (!inputDone) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val buf = codec.getInputBuffer(i)!!
                    buf.clear()
                    val n = minOf(buf.remaining().toLong(), totalBytes - fed, tmp.size.toLong()).toInt() and 1.inv()
                    val pts = fed / 2 * 1_000_000L / Engine.SR
                    if (n <= 0) {
                        codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        raf.readFully(tmp, 0, n)
                        buf.put(tmp, 0, n)
                        codec.queueInputBuffer(i, 0, n, pts, 0)
                        fed += n
                        val pct = (fed * 100 / totalBytes).toInt()
                        if (pct / 5 != lastPct / 5) { lastPct = pct; progress(t("压缩录音… $pct%", "Compressing audio… $pct%")) }
                    }
                    progressed = true
                }
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(codec.outputFormat); mux.start()
                    } else if (o >= 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            mux.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
                    }
                }
                if (!progressed) Thread.sleep(1)
            }
        } finally {
            raf.close()
            codec.stop(); codec.release()
            if (track >= 0) mux.stop()
            mux.release()
        }
    }

    // ---------- 分块续传 ----------
    private fun http(method: String, path: String, body: ByteArray? = null, len: Int = 0): JSONObject {
        val c = URL(Store.server + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.requestMethod = method
        try {
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(len)
                c.outputStream.use { it.write(body, 0, len) }
            }
            val txt = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
            return JSONObject(txt)
        } finally { c.disconnect() }
    }

    // ---------- 备份到电脑(2026-10-28 起电脑只管备份 + 远程查看,不再精转)----------
    private val TEXTS = listOf("lines.json", "polished.txt", "title.txt", "people.txt", "draft.txt", "ask.json")

    /** 这场在电脑上的备份是不是最新的(每个文件的大小 + 修改时间都对得上)。 */
    fun backedUp(dir: File): Boolean {
        val done = doneOf(dir)
        val audioOk = !File(dir, "audio.wav").isFile || done.has("audio.ogg") || done.has("audio.wav")
        return done.length() > 0 && audioOk && texts(dir).all { done.optString(it.name) == sig(it) }
    }

    private fun sig(f: File) = "${f.length()}:${f.lastModified()}"
    private fun doneOf(dir: File) = try { JSONObject(File(dir, "backup.json").readText()) } catch (_: Exception) { JSONObject() }
    private fun texts(dir: File) = TEXTS.map { File(dir, it) }.filter { it.isFile }

    /** 压好录音,把这场有的文件都传过去;上次传过、没变的跳过。 */
    fun backup(dir: File, progress: (String) -> Unit) {
        if (File(dir, "audio.wav").isFile) compress(dir, progress)
        val done = doneOf(dir)
        val audio = File(dir, "audio.ogg").takeIf { it.isFile } ?: File(dir, "audio.wav").takeIf { it.isFile }
        val todo = (texts(dir) + listOfNotNull(audio)).filter { done.optString(it.name) != sig(it) }
        if (todo.isEmpty()) return progress(t("电脑上已经是最新的了", "Already up to date on PC"))
        val bj = File(dir, "backup.json")
        for (f in todo) {
            // 只有上次断掉时传的就是这同一个文件,才从电脑那半截接着传;文件变了(比如重压过)从头传,免得新旧拼在一起
            val resume = done.optString(f.name + "~") == sig(f)
            done.put(f.name + "~", sig(f)); Store.writeAtomic(bj, done.toString())
            send(dir.name, f, resume) { pct -> progress(if (f.name.startsWith("audio")) t("备份录音 $pct%", "Backing up audio $pct%") else t("备份稿子…", "Backing up transcript…")) }
            done.put(f.name, sig(f)); done.remove(f.name + "~")
            Store.writeAtomic(bj, done.toString())
        }
        progress(t("已备份到电脑", "Backed up to PC"))
    }

    /** 分块续传一个文件。 */
    private fun send(id: String, file: File, resume: Boolean, progress: (Int) -> Unit) {
        val name = file.name
        val total = file.length()
        var have = 0L                           // 文件变过就从头传;录音不变,断了会从电脑说的位置接着传
        if (resume && name.startsWith("audio")) have = http("GET", "/backup/have?id=$id&name=$name").getLong("have")
        val raf = RandomAccessFile(file, "r")
        val buf = ByteArray(CHUNK)
        var fails = 0
        try {
            while (true) {
                val n = minOf(CHUNK.toLong(), total - have).toInt()
                raf.seek(have)
                if (n > 0) raf.readFully(buf, 0, n)
                try {
                    val r = http("POST", "/backup/put?id=$id&name=$name&offset=$have&total=$total", buf, n)
                    if (!r.optBoolean("ok")) error(r.optString("msg"))
                    have = r.getLong("have")
                    fails = 0
                    progress(if (total > 0) (have * 100 / total).toInt() else 100)
                    if (have >= total) return
                } catch (e: Exception) {
                    if (e is IllegalStateException || ++fails > 30) throw e
                    Thread.sleep(3000)
                    have = http("GET", "/backup/have?id=$id&name=$name").getLong("have")
                }
            }
        } finally { raf.close() }
    }

    // ---------- 字幕翻译 ----------
    fun translate(text: String): String {
        val body = JSONObject().put("text", text).toString().toByteArray()
        val r = http("POST", "/translate", body, body.size)
        if (!r.optBoolean("ok")) error(r.optString("msg"))
        return r.optString("text")
    }

    // ---------- 词库同步 ----------
    /** 手机这份并进电脑那份;上次同步后在手机上删掉的词,电脑上也删。返回合并后的全量。 */
    fun syncVocab(local: List<String>, lastSynced: List<String>): List<String> {
        val removed = lastSynced.filter { it !in local }
        val body = JSONObject().put("words", JSONArray(local)).put("removed", JSONArray(removed))
            .toString().toByteArray()
        val r = http("POST", "/vocab", body, body.size)
        val a = r.getJSONArray("words")
        return (0 until a.length()).map { a.getString(it) }
    }
}
