package com.suj.zhuanluji

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteOrder

/**
 * 外来音频(mp3 / m4a / aac / wav / ogg / flac…,系统解码器认的都行)→ 16k 单声道 WAV。
 * 边解边写:2 小时的 m4a 也不会整段进内存。
 */
object Importer {
    fun displayName(c: Context, uri: Uri): String {
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {}
        // 退路:路径最后一段;媒体库给的是一串数字 id,那种没意义,不用
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.any { ch -> !ch.isDigit() } } ?: t("导入的音频", "Imported audio")
    }

    /**
     * 趁界面还拿着读权限,把外来文件复制进 App 自己的目录。别的 App「分享」来的授权只跟着界面走,
     * 后台转一小时的途中随时会失效(模拟器上实测 SecurityException 直接崩)。复制几十 MB 也就一两秒。
     * 放 filesDir 不放 cacheDir:缓存目录空间紧时系统会自己清,排着队的副本可能被删掉。
     */
    fun copyIn(c: Context, uri: Uri, name: String): File {
        val dir = File(c.filesDir, "import").apply { mkdirs() }
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val f = File(dir, "${System.currentTimeMillis()}_$safe")
        c.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            ?: error(t("读不到这个文件", "Can't read this file"))
        return f
    }

    fun decode(src: File, out: File, progress: (Int) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error(t("这个文件里没有音轨", "This file has no audio track"))
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val durUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFmt, null, null, 0)
        codec.start()

        // 先写 .part,解完才改名:解到一半被杀,不会留下一个看着完整、其实只有半截的 audio.wav
        val tmp = File(out.path + ".part")
        val wav = WavWriter(tmp)
        val t0 = System.currentTimeMillis()
        var tWork = 0L                            // 花在声道平均 + 重采样 + 写文件上的(其余是解码器本身和等待)
        var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        var rs = Resampler(rate)
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        var lastPct = -1
        try {
            // 能喂就一直喂、有输出就一直取,两头都没进展才睡 1ms(以前每轮白等 10ms,长文件巨慢)
            while (!outDone) {
                var progressed = false
                while (!inDone) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        if (durUs > 0) {
                            val pct = (ex.sampleTime * 100 / durUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                        ex.advance()
                    }
                    progressed = true
                }
                while (!outDone) {
                    val o = codec.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        rs = Resampler(rate)
                    } else if (o >= 0) {
                        val w0 = System.nanoTime()
                        val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        // 多声道 → 取平均成单声道
                        // 整块取出再算(逐个 buffer.get(i) 慢,模拟器上导入 10 分钟要 77 秒)
                        val mono: FloatArray
                        if (isFloat) {
                            val raw = FloatArray(ob.remaining() / 4).also { ob.asFloatBuffer().get(it) }
                            val frames = raw.size / ch
                            mono = FloatArray(frames)
                            for (k in 0 until frames) { var s = 0f; val o0 = k * ch; for (j in 0 until ch) s += raw[o0 + j]; mono[k] = s / ch }
                        } else {
                            val raw = ShortArray(ob.remaining() / 2).also { ob.asShortBuffer().get(it) }
                            val frames = raw.size / ch
                            mono = FloatArray(frames)
                            val k0 = 1f / (ch * 32768f)
                            for (k in 0 until frames) { var s = 0; val o0 = k * ch; for (j in 0 until ch) s += raw[o0 + j]; mono[k] = s * k0 }
                        }
                        codec.releaseOutputBuffer(o, false)
                        val pcm = rs.feed(mono)
                        if (pcm.isNotEmpty()) wav.write(pcm, pcm.size)
                        tWork += System.nanoTime() - w0
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
                if (!progressed) Thread.sleep(1)
            }
        } finally {
            wav.close()
            codec.stop(); codec.release()
            ex.release()
        }
        out.delete()
        if (!tmp.renameTo(out)) error(t("写不进 audio.wav", "Couldn't write audio.wav"))
        android.util.Log.i("zlj", "decode ${durUs / 1_000_000}s audio in ${System.currentTimeMillis() - t0}ms, convert+resample+write ${tWork / 1_000_000}ms")
        progress(100)
    }
}

/**
 * 任意采样率 → 16k,流式。线性插值;源是 32k 以上时先做 3 点平均当低通,压一压混叠(ASR 够用)。
 */
class Resampler(inRate: Int) {
    private val step = inRate.toDouble() / Engine.SR
    private val smooth = inRate >= 32000
    private var pos = 0.0          // 下一个输出点在 [prev, x0, x1, …] 里的位置
    private var prev = 0f
    private var p1 = 0f; private var p2 = 0f   // 低通用的前两个原始样本

    fun feed(raw: FloatArray): ShortArray {
        if (raw.isEmpty()) return ShortArray(0)
        val x = if (!smooth) raw else FloatArray(raw.size) { i ->
            val a = if (i >= 2) raw[i - 2] else if (i == 1) p1 else p2
            val b = if (i >= 1) raw[i - 1] else p1
            (a + b + raw[i]) / 3f
        }
        if (smooth) {
            p2 = if (raw.size >= 2) raw[raw.size - 2] else p1
            p1 = raw[raw.size - 1]
        }
        val out = ShortArray(((x.size + 1) / step).toInt() + 2)
        var n = 0
        while (true) {
            val i = pos.toInt()
            if (i + 1 > x.size) break              // y 长度 = x.size + 1(含 prev)
            val f = (pos - i).toFloat()
            val a = if (i == 0) prev else x[i - 1]
            val b = x[i]
            val v = (a + (b - a) * f).coerceIn(-1f, 1f)
            out[n++] = (v * 32767f).toInt().toShort()
            pos += step
        }
        pos -= x.size
        prev = x[x.size - 1]
        return out.copyOf(n)
    }
}
