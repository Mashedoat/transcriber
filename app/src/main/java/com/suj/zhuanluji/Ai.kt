package com.suj.zhuanluji

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手机上直接调大模型(公开版:用户自己的 key,OpenAI 兼容接口,默认 DeepSeek)。
 * 和电脑上 llm.py 一个路数:无状态单发、关思考(校对/分人这种活不需要,开了慢好几倍)。
 */
object Llm {
    fun ready() = Store.apiKey.isNotBlank()

    fun call(system: String, user: String, model: String, maxTokens: Int = 4000,
             temperature: Double = 0.2, retries: Int = 2, timeoutSec: Int = 180, profile: Store.AiProfile = Store.aiProfile()): String =
        chat(JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user)),
            model, maxTokens = maxTokens, temperature = temperature, retries = retries, timeoutSec = timeoutSec, profile = profile)
            .optString("content")

    /** 多轮 / 带工具的调用,返回模型回的那条 message(可能带 tool_calls)。 */
    fun chat(messages: JSONArray, model: String, tools: JSONArray? = null, maxTokens: Int = 4000,
             temperature: Double = 0.2, retries: Int = 2, timeoutSec: Int = 180, profile: Store.AiProfile = Store.aiProfile()): JSONObject {
        if (profile.auth.isBlank()) error(t("还没填 AI 的 key:设置 → AI 设置", "AI key not set yet: Settings → AI settings"))
        if (profile.provider == Store.OPENAI) {
            return JSONObject().put("content", responseText(response(messages, profile, maxTokens, retries, timeoutSec)))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .put("stream", false)
        if (tools != null) body.put("tools", tools)
        if (isDeepSeek(profile)) body.put("thinking", JSONObject().put("type", "disabled"))
        val r = post(profile.base + "/chat/completions", body, retries, timeoutSec, profile)
        return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    }

    fun isDeepSeek(profile: Store.AiProfile = Store.aiProfile()) =
        try { java.net.URI(profile.base).host.equals("api.deepseek.com", ignoreCase = true) } catch (_: Exception) { false }

    /** OpenAI Responses: avoid server-side response storage; use the selected profile for the whole request. */
    fun response(messages: JSONArray, profile: Store.AiProfile, limit: Int,
                 retries: Int = 2, timeoutSec: Int = 180, search: Boolean = false): JSONObject {
        val body = JSONObject().put("model", profile.model).put("input", messages).put("store", false)
            .put("max_output_tokens", limit)
            .put("reasoning", JSONObject().put("effort", if (search) "low" else "none"))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("type", "web_search").put("search_context_size", "low")))
            .put("max_tool_calls", 2)
        return post(profile.base + "/responses", body, retries, timeoutSec, profile)
    }

    fun responseText(response: JSONObject): String {
        if (response.optString("status") != "completed") error(t("AI 没有完成回复,请重试", "AI did not finish its reply; please retry"))
        val output = response.optJSONArray("output") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") text.append(part.optString("text"))
            }
        }
        return text.toString().trim().ifBlank { error(t("AI 没有返回文字", "AI returned no text")) }
    }

    /**
     * DeepSeek 自家的联网搜索(2026-10-17 开放,和对话同一个 key,每千次 0.75 美元,失败和空结果不收钱)。
     * 返回 [{title, url, date, summary}];只有服务地址是 DeepSeek 时能用。
     */
    fun search(query: String, count: Int = 5, profile: Store.AiProfile = Store.aiProfile()): JSONArray {
        val root = profile.base.trimEnd('/').removeSuffix("/v1")
        val r = post("$root/v1/tools/search", JSONObject().put("query", query.take(128)).put("count", count),
            retries = 1, timeoutSec = 30, profile = profile)
        val res = r.optJSONArray("results") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until res.length()) {
            val x = res.optJSONObject(i) ?: continue
            out.put(JSONObject().put("title", x.optString("title")).put("url", x.optString("url"))
                .put("date", x.optString("date").take(10))
                .put("summary", x.optString("summary").ifBlank { x.optString("snippet") }.take(500)))
        }
        return out
    }

    private fun post(url: String, body: JSONObject, retries: Int, timeoutSec: Int, profile: Store.AiProfile): JSONObject {
        if (profile.auth.isBlank()) error(t("请先填写当前服务的 API key", "Enter an API key for this service first"))
        if (!URL(url).protocol.equals("https", ignoreCase = true)) error(t("AI 服务地址必须使用 HTTPS", "AI service URL must use HTTPS"))
        val bytes = body.toString().toByteArray()
        var last: Exception? = null
        for (attempt in 0..retries) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = timeoutSec * 1000
                c.instanceFollowRedirects = false   // Never forward the user's credentials to a redirect target.
                c.requestMethod = "POST"
                c.setRequestProperty("Authorization", "Bearer " + profile.auth)
                c.setRequestProperty("Content-Type", "application/json")
                c.doOutput = true
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
                val code = c.responseCode
                val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                if (code == 401 || code == 403) error(t("key 不对或没权限($code)", "Bad key or no permission ($code)"))
                if (code == 402) error(t("账户余额不足(402)", "Account balance insufficient (402)"))
                if (code in 300..399) error(t("AI 服务发生跳转,请检查服务地址", "AI service redirected; check the service URL"))
                if (code >= 400) throw IOException(t("AI 服务回 $code,请检查模型、额度和网络", "AI service returned $code; check model access, quota and network"))
                return JSONObject(txt)
            } catch (e: IOException) {
                last = e
                if (attempt < retries) Thread.sleep(2000L shl attempt)
            } finally { c.disconnect() }
        }
        throw last ?: IOException(t("连不上 AI 服务", "Can't reach the AI service"))
    }

    /** 模型回的东西解析不了:调用方重试一次,不行就这段保持原样。和 key 错、欠费(要报给用户)分开。 */
    class BadJson(msg: String) : Exception(msg)

    /** 从模型回复里抠出第一个 {...}(偶尔会带几句话或 ```json 包着)。 */
    fun json(raw: String): JSONObject {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) throw BadJson(t("AI 回的不是 JSON", "AI didn't return JSON"))
        return try { JSONObject(raw.substring(s, e + 1)) } catch (x: org.json.JSONException) { throw BadJson(t("AI 回的 JSON 坏了", "AI's JSON was malformed")) }
    }
}

/** 翻译:自用版没填 key 时走电脑(key 在电脑上),公开版手机直连。提示词和电脑那份同步。 */
object Ai {
    private fun isHan(c: Char) = c in '一'..'鿿'

    /** 这句算中文(汉字超过三成;中英夹杂的「我们用 Transformer 做」也算中文)。 */
    fun isZh(t: String) = t.count(::isHan) > t.trim().length * 0.3

    /** 按实时翻译的方向,这句要不要翻。 */
    fun wantTranslate(t: String, mode: Int = Store.trMode) = when (mode) {
        Store.TR_EN -> !isZh(t)
        Store.TR_ZH -> isZh(t)
        Store.TR_BOTH -> true
        else -> false
    }

    fun translate(text: String): String {
        if (!BuildConfig.PUBLIC && !Llm.ready()) return Uploader.translate(text)   // 自用版没填 key 才走电脑
        val txt = text.trim().take(1500)
        if (txt.isEmpty()) return ""
        val to = if (isZh(txt)) t("英文", "English") else t("简体中文", "Simplified Chinese")
        val words = Store.vocab().take(80)
        val sys = t(
            "你是会议同传。把用户发来的一句口语转写译成$to。只输出译文,不解释、不加引号。" +
                "转写可能有错字或断句不全,按语境意译,别逐字硬译;「呃」「um」这类语气词去掉。" +
                (if (words.isNotEmpty()) "\n专有名词照这个写法:" + words.joinToString("、") else ""),
            "You're a meeting interpreter. Translate the user's spoken-transcript sentence into $to. Output only the translation, no explanation or quotes. " +
                "The transcript may have typos or broken sentences — translate the sense of it, not word-for-word; drop filler like \"呃\"/\"um\"." +
                (if (words.isNotEmpty()) "\nUse this spelling for proper nouns: " + words.joinToString(", ") else ""))
        return Llm.call(sys, txt, Store.fastModel, maxTokens = 500, retries = 1, timeoutSec = 30).trim()
    }
}
