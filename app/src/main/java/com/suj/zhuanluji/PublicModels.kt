package com.suj.zhuanluji

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * 公开版的模型:没有电脑可拉,用 sherpa-onnx 官方在 GitHub 发布的模型包(.tar.bz2,约 880MB)。
 *  - 在 App 里下:断点续传(大文件跨境常断),下完解包,删掉压缩包;
 *  - 或者用户自己想办法下好(网盘、别人传的),从手机文件里选这个包导入——国内连 GitHub 不稳时的退路。
 * 只解出要用的几个文件,不信任包里的路径(白名单 = Engine.required())。
 */
object PublicModels {
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${Engine.QWEN}.tar.bz2"
    const val SIZE = 878_702_423L
    // Official GitHub release asset 390698077; provenance: distribution/model-package.json.
    const val SHA256 = "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96"
    private const val UNPACKED = 990_000_000L

    private fun need() = Engine.required().filter { it.startsWith(Engine.QWEN + "/") }.toSet()

    private fun checkSpace(extra: Long) {
        val free = Engine.modelDir.usableSpace
        if (free < extra) error(t("手机空间不够:还要约 ${extra / 1_000_000} MB,现在剩 ${free / 1_000_000} MB", "Not enough space: need ~${extra / 1_000_000} MB more, ${free / 1_000_000} MB free now"))
    }

    /** 从 GitHub 下(断点续传)再解包。 */
    fun download(progress: (String) -> Unit) {
        val pkg = File(Engine.modelDir, "${Engine.QWEN}.tar.bz2")
        checkSpace((SIZE - pkg.length()).coerceAtLeast(0) + UNPACKED)
        var tries = 0
        while (pkg.length() < SIZE) {
            try {
                fetchRange(pkg) { have -> progress(t("下载模型 ${have * 100 / SIZE}% · ${have / 1_000_000}/${SIZE / 1_000_000} MB", "Downloading models ${have * 100 / SIZE}% · ${have / 1_000_000}/${SIZE / 1_000_000} MB")) }
            } catch (e: Exception) {
                if (++tries >= 50) throw e
                progress(t("下载断了,第 $tries 次续传…(${e.javaClass.simpleName})", "Download interrupted, retry #$tries… (${e.javaClass.simpleName})"))
                Thread.sleep(3000)
            }
        }
        try {
            pkg.inputStream().use { verify(it, progress) }
        } catch (e: ModelPackageIntegrity.InvalidPackage) {
            pkg.delete()
            error(t("模型包大小或 SHA-256 校验失败,请重新下载", "Model package size or SHA-256 check failed; please download again"))
        }
        pkg.inputStream().use { extract(it, SIZE, progress) }
        pkg.delete()
    }

    /** 用户从手机文件里选的模型包。 */
    fun importLocal(c: Context, uri: Uri, progress: (String) -> Unit) {
        checkSpace(SIZE + UNPACKED)
        // Verify and extract the same private copy; a provider may return different bytes on reopen.
        val pkg = File.createTempFile("model-import-", ".tar.bz2", Engine.modelDir)
        try {
            (c.contentResolver.openInputStream(uri) ?: error(t("打不开这个文件", "Can't open this file"))).use { src ->
                pkg.outputStream().use { out -> verify(src, progress, out) }
            }
            pkg.inputStream().use { extract(it, SIZE, progress) }
        } catch (e: ModelPackageIntegrity.InvalidPackage) {
            error(t("模型包大小或 SHA-256 校验失败,请选择官方原始模型包", "Model package size or SHA-256 check failed; select the original official archive"))
        } finally {
            pkg.delete()
        }
    }

    private fun verify(src: InputStream, progress: (String) -> Unit, copyTo: java.io.OutputStream? = null) {
        var lastPct = -1L
        ModelPackageIntegrity.verify(src, SIZE, SHA256, copyTo) { bytes ->
            val pct = bytes * 100 / SIZE
            if (pct != lastPct) {
                lastPct = pct
                progress(t("校验模型包 $pct%", "Verifying model package $pct%"))
            }
        }
    }

    /** GitHub 的下载会 302 到一个限时签名地址:每次续传都重新解析,自己跟跳转,Range 头才不会丢。 */
    private fun fetchRange(dst: File, onProgress: (Long) -> Unit) {
        var u = URL
        var c: HttpURLConnection
        var hops = 0
        val have = dst.length()
        while (true) {
            c = java.net.URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399) {
                u = c.getHeaderField("Location") ?: error(t("跳转没给地址", "Redirect gave no address"))
                c.disconnect()
                if (++hops > 5) error(t("跳转太多次", "Too many redirects"))
                continue
            }
            if (code != 200 && code != 206) { c.disconnect(); error(t("GitHub 回 $code", "GitHub returned $code")) }
            break
        }
        try {
            val append = c.responseCode == 206
            FileOutputStream(dst, append).use { out ->
                var got = if (append) have else 0L
                var last = 0L
                val buf = ByteArray(1 shl 16)
                c.inputStream.use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - last > (8 shl 20)) { last = got; onProgress(got) }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private class Counting(i: InputStream) : FilterInputStream(i) {
        @Volatile var n = 0L
        override fun read(): Int = super.read().also { if (it >= 0) n++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) n += it }
    }

    /** 边解 bzip2 边读 tar,只写白名单里的文件(先写 .part,写完改名)。 */
    private fun extract(src: InputStream, total: Long, progress: (String) -> Unit) {
        val need = need()
        val got = mutableSetOf<String>()
        val counting = Counting(BufferedInputStream(src, 1 shl 16))
        var lastPct = -1
        TarArchiveInputStream(BZip2CompressorInputStream(counting, true)).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val name = e.name.removePrefix("./")
                if (e.isDirectory || name !in need) continue
                val dst = File(Engine.modelDir, name)
                dst.parentFile?.mkdirs()
                val part = File(dst.path + ".part")
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = tar.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        val pct = if (total > 0) (counting.n * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            progress(if (pct >= 0) t("解开模型包 $pct%", "Unpacking models $pct%") else t("解开模型包… ${counting.n / 1_000_000} MB", "Unpacking models… ${counting.n / 1_000_000} MB"))
                        }
                    }
                }
                dst.delete()
                if (!part.renameTo(dst)) error(t("写不进 $name", "Couldn't write $name"))
                got += name
            }
        }
        val miss = need - got
        if (miss.isNotEmpty()) error(t("这个包里缺 ${miss.size} 个文件(要的是 ${Engine.QWEN}.tar.bz2)", "This package is missing ${miss.size} file(s) (expected ${Engine.QWEN}.tar.bz2)"))
        progress(t("模型就位", "Models ready"))
    }
}
