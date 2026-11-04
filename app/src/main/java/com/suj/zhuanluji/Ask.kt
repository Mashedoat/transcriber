package com.suj.zhuanluji

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「问 AI」:录音时(或回看一场时)旁边放个 AI,拿到目前为止的稿子回答问题,必要时联网搜 1~2 次
 * (DeepSeek 自家的搜索接口)。问答存在这场的 ask.json 里,单独一栏显示、一起备份;
 * 不混进精修稿——那份是「这场会说了什么」,掺进 AI 的话以后分不清哪些是人说的(2026-10-28)。
 * prompt 照「对面是个小模型」写:规则少、说人话。给今天的日期:不给的话它以为还是 2025 年,搜索词都带着 2025。
 */
object Ask {
    class QA(val at: Int, val q: String, val a: String, val sources: List<Pair<String, String>>, val searched: List<String>)

    private val SYS get() = t("""你在会议现场帮用户。给你到目前为止的会议语音识别稿(错字多,没分说话人),用户会随时问你。
- 用中文简短回答,先说结论,几句话就够,是在手机上看。
- 问会上说了什么:照稿子答。
- 问外面的事(政策、数字、公司、人、产品、新闻):稿子里没有具体内容就 search,别复述稿子里含糊的说法,也别凭印象答。最多搜 2 次。
- 普通常识直接答。
- 不知道就说不知道,不要编。""", """You're helping the user live, during a meeting. You get the meeting's speech-recognition transcript so far (lots of typos, no speaker labels); the user may ask anytime.
- Answer in English, short, lead with the conclusion, a few sentences is enough — they're reading this on their phone.
- Questions about what was said: answer from the transcript.
- Questions about the outside world (a policy, figure, company, person, product, recent event): if the transcript doesn't give the specifics, search instead of repeating the transcript's vague version or answering from memory. At most 2 searches.
- Plain common knowledge: just answer.
- Say you don't know rather than making things up.""")
    private const val MAX_SEARCH = 2

    /** 快捷问题(会上打字不方便,点一下就问)。 */
    val QUICK get() = listOf(
        t("刚才在说什么", "What was just said") to t("用两三句话说说最近五分钟在聊什么。", "Summarize the last five minutes in two or three sentences."),
        t("提到的名词是啥", "What were those terms") to t("最近几分钟提到的人名、公司、产品、术语,各是什么?不熟的可以搜一下。", "What are the names, companies, products, or terms mentioned in the last few minutes? Search if unsure."),
        t("我可以问什么", "What can I ask") to t("根据到目前为止的内容,我接下来可以问对方哪 3 个好问题?每个一句话。", "Based on what's been said so far, what 3 good questions could I ask next? One line each."),
    )

    private val TOOLS get() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "search")
        .put("description", t("联网查稿子里没有的事实:政策、数字、日期、公司、人、新闻。query 写成搜索关键词,128 字以内。",
            "Search the web for facts the transcript doesn't give: policies, numbers, dates, companies, people, news. Write query as search keywords, under 128 characters."))
        .put("parameters", JSONObject().put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("query")))))

    fun load(dir: File): List<QA> = try {
        val a = JSONArray(File(dir, "ask.json").readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val s = o.optJSONArray("sources") ?: JSONArray()
            val q = o.optJSONArray("searched") ?: JSONArray()
            QA(o.optInt("at"), o.optString("q"), o.optString("a"),
                (0 until s.length()).map { s.getJSONObject(it).let { x -> x.optString("title") to x.optString("url") } },
                (0 until q.length()).map { q.optString(it) })
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized private fun append(dir: File, qa: QA) {
        val a = try { JSONArray(File(dir, "ask.json").readText()) } catch (_: Exception) { JSONArray() }
        val src = JSONArray().also { arr -> qa.sources.forEach { arr.put(JSONObject().put("title", it.first).put("url", it.second)) } }
        a.put(JSONObject().put("at", qa.at).put("q", qa.q).put("a", qa.a).put("sources", src)
            .put("searched", JSONArray(qa.searched)))
        File(dir, "ask.json.tmp").apply { writeText(a.toString()) }.renameTo(File(dir, "ask.json"))
    }

    /**
     * 问一句。lines = 录音中就是目前的字幕,回看时是整场;回看且精修过就用精修稿(更好读)。
     * at = 问的时候录到第几秒(回看时为 -1)。progress 报「正在想 / 联网搜:xxx」。
     */
    fun ask(dir: File, lines: List<Line>, at: Int, q: String, progress: (String) -> Unit): QA {
        val pol = if (at < 0) Store.polishedOf(dir) else null
        val tx = (pol ?: lines.joinToString("\n") { "${Engine.ts(it.t0)} ${it.text}" }).takeLast(60_000)
        val upto = if (at >= 0) t("(录到 ${Engine.ts(at.toFloat())})", "(recorded up to ${Engine.ts(at.toFloat())})")
            else if (pol != null) t("(整理过的整场)", "(the whole thing, polished)") else t("(整场)", "(the whole thing)")
        val prev = load(dir).takeLast(4)
        val ctx = t("今天:", "Today: ") + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + "\n" +
            (Store.titleOf(dir)?.let { t("录音标题:$it\n", "Title: $it\n") } ?: "") +
            Polish.peopleOf(dir).takeIf { it.isNotBlank() }?.let { t("参与者:$it\n", "Participants: $it\n") }.orEmpty() +
            (if (Store.me.isNotBlank()) t("提问的人:${Store.me}\n", "Asked by: ${Store.me}\n") else "")
        // 稿子在前、问题在后:稿子只往后长,前面那截每次都一样,DeepSeek 按缓存价算
        val user = ctx + t("\n会议稿$upto:\n", "\nTranscript$upto:\n") + tx.ifBlank { t("(还没有字幕)", "(no captions yet)") } + "\n\n" +
            (if (prev.isNotEmpty()) t("之前问过:\n", "Previously asked:\n") + prev.joinToString("\n") { t("问:${it.q}\n答:${it.a.take(300)}", "Q: ${it.q}\nA: ${it.a.take(300)}") } + "\n\n" else "") +
            t("问题:$q", "Question: $q")
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYS))
            .put(JSONObject().put("role", "user").put("content", user))
        val profile = Store.aiProfile()
        if (profile.provider == Store.OPENAI) {
            progress(t("正在想,需要时联网搜索…", "Thinking, with web search when needed…"))
            val response = Llm.response(msgs, profile, limit = 1800, retries = 1, timeoutSec = 90, search = true)
            Llm.responseText(response)    // Reject incomplete replies before saving a partial answer.
            val output = response.optJSONArray("output") ?: JSONArray()
            val sources = linkedMapOf<String, String>()
            val searched = mutableListOf<String>()
            val parts = mutableListOf<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") == "web_search_call") {
                    val action = item.optJSONObject("action") ?: continue
                    val queries = action.optJSONArray("queries")
                    if (queries != null) for (j in 0 until queries.length()) searched += queries.optString(j)
                    else action.optString("query").takeIf { it.isNotBlank() }?.let { searched += it }
                    continue
                }
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") != "output_text") continue
                    val raw = part.optString("text")
                    var text = raw
                    val citations = part.optJSONArray("annotations") ?: JSONArray()
                    val replacements = mutableListOf<Triple<Int, Int, Int>>()
                    for (k in 0 until citations.length()) {
                        val citation = citations.optJSONObject(k) ?: continue
                        if (citation.optString("type") != "url_citation") continue
                        val url = citation.optString("url")
                        if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                        sources.putIfAbsent(url, citation.optString("title"))
                        val start = citation.optInt("start_index", -1)
                        val end = citation.optInt("end_index", -1)
                        if (start >= 0 && end > start && end <= raw.codePointCount(0, raw.length)) {
                            replacements += Triple(raw.offsetByCodePoints(0, start), raw.offsetByCodePoints(0, end), sources.keys.indexOf(url) + 1)
                        }
                    }
                    for ((start, end, number) in replacements.distinctBy { it.first to it.second }.sortedByDescending { it.first }) {
                        text = text.replaceRange(start, end, "【$number】")
                    }
                    parts += text
                }
            }
            val qa = QA(at, q, parts.joinToString("\n").trim(), sources.map { it.value to it.key }, searched)
            append(dir, qa)
            return qa
        }
        val canSearch = Llm.isDeepSeek(profile)
        val searched = mutableListOf<String>()
        val sources = linkedMapOf<String, String>()      // url → 标题
        progress(t("正在想…", "Thinking…"))
        for (round in 0..MAX_SEARCH) {
            val m = Llm.chat(msgs, profile.model, tools = if (canSearch && searched.size < MAX_SEARCH) TOOLS else null,
                maxTokens = 1200, temperature = 0.3, retries = 1, timeoutSec = 60, profile = profile)
            val calls = m.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val qa = QA(at, q, m.optString("content").trim(), sources.map { it.value to it.key }, searched)
                append(dir, qa)
                return qa
            }
            msgs.put(JSONObject().put("role", "assistant").put("content", m.optString("content")).put("tool_calls", calls))
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val query = try { JSONObject(c.getJSONObject("function").optString("arguments")).optString("query") }
                            catch (_: Exception) { "" }
                val result = if (query.isBlank() || searched.size >= MAX_SEARCH) t("搜索次数用完了,请直接回答。", "Out of searches, please just answer directly.")
                else {
                    searched += query
                    progress(t("联网搜:$query", "Searching: $query"))
                    try {
                        val r = Llm.search(query, profile = profile)
                        for (k in 0 until minOf(3, r.length())) r.getJSONObject(k).let { sources.putIfAbsent(it.optString("url"), it.optString("title")) }
                        r.toString()
                    } catch (e: Exception) { t("搜索失败:${e.message}", "Search failed: ${e.message}") }
                }
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", result))
            }
            progress(t("正在整理答案…", "Putting together an answer…"))
        }
        error(t("AI 一直要搜索,没给出答案", "AI kept trying to search and never answered"))
    }
}
