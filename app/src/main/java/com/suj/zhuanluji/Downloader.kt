package com.suj.zhuanluji

import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 从电脑(转录机服务)拉模型:先取 /models/manifest.json(相对路径 + 字节数),再逐个文件下。
 * 断点续传:大文件经代理/跨境常被掐断(电脑上下 879MB 时实测断过),所以每个文件按 Range 续,最多重试 30 次。
 */
object Downloader {
    private fun url(rel: String) =
        Store.server + "/models/" + rel.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun run(progress: (String) -> Unit) {
        val man = JSONArray(fetchText(url("manifest.json")))
        val items = (0 until man.length()).map { man.getJSONObject(it) }.map { it.getString("path") to it.getLong("size") }
        val all = items.sumOf { it.second }
        var doneBefore = 0L
        for ((rel, size) in items) {
            val dst = File(Engine.modelDir, rel)
            dst.parentFile?.mkdirs()
            if (dst.length() == size) { doneBefore += size; continue }
            if (dst.length() > size) dst.delete()
            var tries = 0
            while (dst.length() < size) {
                try {
                    fetchRange(url(rel), dst) { have ->
                        progress(t("下载模型 ${pct(doneBefore + have, all)} · ${rel.substringAfterLast('/')}", "Downloading models ${pct(doneBefore + have, all)} · ${rel.substringAfterLast('/')}"))
                    }
                } catch (e: Exception) {
                    if (++tries >= 30) throw e
                    progress(t("下载断了,第 $tries 次续传… (${e.javaClass.simpleName})", "Download interrupted, retry #$tries… (${e.javaClass.simpleName})"))
                    Thread.sleep(2000)
                }
            }
            doneBefore += size
        }
        progress(t("模型下载完成", "Model download complete"))
    }

    private fun pct(a: Long, b: Long) = if (b > 0) "${a * 100 / b}%" else ""

    private fun fetchText(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) error(t("服务器回 ${c.responseCode}:$u", "Server returned ${c.responseCode}: $u"))
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun fetchRange(u: String, dst: File, onProgress: (Long) -> Unit) {
        val have = dst.length()
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            val append = code == 206
            if (code != 200 && code != 206) error(t("服务器回 $code", "Server returned $code"))
            FileOutputStream(dst, append).use { out ->
                var got = if (append) have else 0L
                val buf = ByteArray(1 shl 16)
                var last = 0L
                c.inputStream.use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - last > (4 shl 20)) { last = got; onProgress(got) }
                    }
                }
            }
        } finally { c.disconnect() }
    }
}
