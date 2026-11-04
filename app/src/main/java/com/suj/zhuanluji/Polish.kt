package com.suj.zhuanluji

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 精修(2026-10-28 大修,两个版本都在手机上做,电脑只管备份和远程查看):
 *  ① 通读(一次调用):一句话概要 + 谁是谁 + 逐句说话人 + 错词表 + 话题提纲;
 *  ② 重建(按话题约 10 分钟一段,顺着来):逐句的可读对话稿,段尾列出要回听核对的地方。
 * 全用快模型,prompt 写短(当对面是个小模型:规则少、说人话)。45 分钟三人对话实测约 1 分钟。
 * 以前那套「逐句校对 + 窗口分人」出来的稿子边际效用太小,Suj 更喜欢 GPT 那种逐句整理好的对话稿。
 * 在 WorkService 里跑;每一步做完存进度(polish_state.json),断了再点接着做。
 * 通读出的错词表只给逐段校对用(以前还推进「推荐词」待选,2026-10-29 撤了)。
 */
object Polish {
    private const val STATE_V = 2

    private val READ_SYS get() = t("""这是一份语音识别出的对话稿,错字很多。每句前有编号 [i] 和时间。请通读全文,输出一个 JSON:

{"title": "标题,16 字内:聊了什么",
 "summary": "一句话:谁和谁聊了什么",
 "speakers": [{"id": "甲", "name": "名字或称呼,不知道就空着", "who": "一句话身份"}],
 "runs": [[句号, "甲"], [句号, "乙"]],
 "glossary": [{"right": "正确写法", "wrong": ["稿里的错写法"]}],
 "outline": [{"t0": "mm:ss", "topic": "话题,10 字内"}]}

说明:
- title:像文件名那样短,写具体内容(比如「e-SAF 圆桌:欧盟掺混要求与绿氢订单」)。不写日期,不写「关于…的讨论」。
- speakers:有几个人、各是谁。用户给的「参与者」「录音的人」最可靠。只有一个人说话就只写一个。
- runs:从这句起换成这个人说。从 0 开始。看不出是谁就写 "?"。
- glossary:稿里听错的词,比如英文被听成谐音。没把握就不写。
- 用常识整体检查一遍。""", """This is a speech-recognition transcript, with many mistakes. Each line starts with a number [i] and a time. Read it all and output JSON:

{"title": "title, under 8 words: what it was about",
 "summary": "one line: who talked with whom about what",
 "speakers": [{"id": "A", "name": "name or how they're addressed, leave blank if unknown", "who": "one line: who they are"}],
 "runs": [[line_no, "A"], [line_no, "B"]],
 "glossary": [{"right": "correct spelling", "wrong": ["misheard spelling in the transcript"]}],
 "outline": [{"t0": "mm:ss", "topic": "topic in English, under 6 words"}]}

Notes:
- title: short like a file name, about the actual content (e.g. "e-SAF panel: EU blending mandate and green hydrogen orders"). No date, no "Discussion about…".
- speakers: how many people, and who they are. The user's "Participants" / "Recorded by" are most reliable. If only one person speaks, write just one.
- runs: from this line on, it switches to this person. Starts at 0. Write "?" if you can't tell.
- glossary: words the transcript misheard, e.g. English heard as similar-sounding words. Skip if unsure.
- Do a general sanity check with common sense.""")

    private val REBUILD_SYS get() = t("""把下面这段语音识别稿整理成好读的对话稿。识别稿错字很多。

- 每句前的 [甲] 是谁说的,一般照用;明显不对再改。[?] 看上下文定。
- 同一个人连着说的合成一段。保持原来的顺序。
- 按错词表和上下文改错字。数字、名字、专业结论没把握就照原文,标(存疑)。
- 能看懂的乱句,用他自己的口气说通顺;看不懂的碎片、口头禅、重复就删掉。
- 不加原稿没有的内容。不要写成总结。

格式:
### mm:ss｜话题

**甲:** 说的话。

最后,如果有要紧的数字细节、关键名词、核心结论没听清,写出来请人回听(最多 3 条,没有就不写):
<待核对>
mm:ss 什么事
</待核对>""", """Turn this speech-recognition transcript into a readable dialogue. The transcript has many mistakes.

- The [A] before each line is who said it; use it as-is unless clearly wrong. [?] — infer from context.
- Merge consecutive lines from the same speaker into one paragraph. Keep the original order.
- Fix mistakes using the glossary and context. If unsure about a number, name, or key conclusion, keep the original and mark it (unconfirmed).
- Smooth out garbled lines you can understand, in the speaker's own voice; drop fragments, filler, and repeats you can't make sense of.
- Don't add anything not in the original. Don't turn it into a summary.
- Keep what people said in the language they said it. Write topics and check notes in English.

Format:
### mm:ss | Topic

**A:** What they said.

Finally, if any important number, key term, or core conclusion wasn't clear, list it for the person to check (at most 3, skip if none):
<check>
mm:ss what it's about
</check>""")

    /**
     * 各段的待核对只是候选:每段只看得到自己,小模型会把「最多 3 条」写满(45 分钟 6 段 = 18 条,
     * 大多是「这句语义不明」)。最后再调一次快模型横着比,全篇挑最多 5 条(Suj 2026-10-28)。
     * 措辞是 Suj 定的三类,括号里的例子给小模型当锚点。
     */
    private val PICK_SYS get() = t("""下面是整理一场录音稿时记下的「没听清」的地方。挑出听错了会出事的,请人回听。

要紧的:数字细节(金额、日期、比例)、关键名词(人名、公司、产品)、核心结论。
一句话没听懂、口头禅、不影响意思的词,都不要。

最多 5 条,没有要紧的就什么都不写。每条一行,照原样抄:mm:ss 什么事""", """Below are the "unclear" spots noted while organizing a recording. Pick the ones that would matter if misheard, for the person to check.

Important: numeric details (amounts, dates, ratios), key terms (names, companies, products), core conclusions.
Skip anything that's just an unclear sentence, filler, or doesn't affect the meaning.

At most 5 items, write nothing if none matter. One per line, copied as-is: mm:ss what it's about""")
    private const val MAX_TODO = 5

    /** 从候选里挑全篇最要紧的几条,按时间排。挑的那次调用失败就退回前几条候选,不耽误出稿。 */
    private fun pickTodo(ctx: String, summary: String, cands: List<String>): List<String> {
        if (cands.isEmpty()) return cands
        val ts = Regex("""^(\d{1,2}:\d{2}(?::\d{2})?)""")
        val picked = try {
            Llm.call(PICK_SYS, ctx + t("概要:$summary\n\n", "Summary: $summary\n\n") + cands.joinToString("\n"), Store.fastModel,
                maxTokens = 800, temperature = 0.2).lines().map { it.trim().trimStart('-', '•', ' ') }
                .filter { ts.containsMatchIn(it) }
        } catch (_: Exception) { cands }
        return picked.take(MAX_TODO).sortedBy { sec(ts.find(it)!!.value) }
    }

    /** 精修起的标题换上去:用户自己改过的名字不动;导入的原文件名收进 orig_name.txt(Store.setTitle)。 */
    fun applyTitle(dir: File, title: String) {
        var tt = title.trim().trim('"', '「', '」', '《', '》')
        // 太长才截(英文标题天生比中文长),截在空格处,别把单词切成半个
        if (tt.length > 60) tt = tt.take(60).let { s -> s.substringBeforeLast(' ').takeIf { it.length > 30 } ?: s }.trimEnd(',', ':', ';', '，', '：')
        if (tt.isBlank() || Store.titleSrc(dir) == "user") return
        Store.setTitle(dir, tt, "ai")
    }

    private val TITLE_SYS get() = t(
        "给这场录音起个标题,16 字内,像文件名那样短,写具体内容。不写日期,不写「关于…的讨论」。只输出标题。",
        "Give this recording a title, under 8 words, short like a file name, about the actual content. No date, no \"Discussion about…\". Output only the title.")

    /** 以前精修过、还没有 AI 标题的:拿精修稿里的概要和各段话题起一个(一次很小的调用)。 */
    fun titleFromPolished(dir: File) {
        val pol = Store.polishedOf(dir) ?: error(t("这场还没精修", "Not polished yet"))
        if (!Llm.ready()) error(t("还没填 AI 的 key:设置 → AI 设置", "AI key not set yet: Settings → AI settings"))
        val gist = pol.lines().filter { it.startsWith("> ") || it.startsWith("### ") }.joinToString("\n").take(3000)
        applyTitle(dir, Llm.call(TITLE_SYS, gist.ifBlank { pol.take(3000) }, Store.fastModel, maxTokens = 60, temperature = 0.3).lines().first())
    }

    fun peopleOf(dir: File): String = File(dir, "people.txt").takeIf { it.isFile }?.readText()?.trim() ?: ""
    fun savePeople(dir: File, p: String) = File(dir, "people.txt").writeText(p.trim())

    /**
     * 背景:原文件名、录音日期、这场有谁、录音的人、词库。日期只当普通背景给,不教它怎么推理。
     * 只给原文件名(导入的才有),不给 AI 自己上次起的标题——免得它照着旧标题理解。
     * 日期优先用原文件名里的(录音那天),没有才用场次目录名(导入那天)。
     */
    private fun context(dir: File): String {
        val n = dir.name
        val date = Store.recordedOf(dir)?.first ?: if (n.length >= 8) "${n.substring(0, 4)}-${n.substring(4, 6)}-${n.substring(6, 8)}" else ""
        val vocab = Store.vocab()
        val name = Store.origNameOf(dir) ?: Store.titleOf(dir).takeIf { Store.titleSrc(dir) == "user" }
        return (name?.let { t("录音标题:$it\n", "Title: $it\n") } ?: "") +
            (if (date.isNotEmpty()) t("录音日期:$date\n", "Recording date: $date\n") else "") +
            peopleOf(dir).takeIf { it.isNotBlank() }?.let { t("参与者:$it\n", "Participants: $it\n") }.orEmpty() +
            (if (Store.me.isNotBlank()) t("录音的人:${Store.me}\n", "Recorded by: ${Store.me}\n") else "") +
            (if (vocab.isNotEmpty()) t("用户词库:", "User vocabulary: ") + vocab.joinToString(t("、", ", ")) + "\n" else "")
    }

    private fun sec(s: String): Int = try {
        val p = s.trim().split(":").map { it.toInt() }
        if (p.size == 2) p[0] * 60 + p[1] else p[0] * 3600 + p[1] * 60 + p[2]
    } catch (_: Exception) { -1 }

    fun run(dir: File, progress: (String) -> Unit) {
        val lines = Store.loadLines(dir).filter { it.text.isNotBlank() }
        if (lines.isEmpty()) error(t("这场还没有文字:先「重新整理」在手机上转出初稿", "No text yet for this session: run \"Re-process\" on the phone first"))
        if (!Llm.ready()) error(t("还没填 AI 的 key:设置 → AI 设置", "AI key not set yet: Settings → AI settings"))
        val ctx = context(dir)
        val stFile = File(dir, "polish_state.json")
        val sig = "$STATE_V:${lines.size}:${lines.first().text.hashCode()}:${lines.last().text.hashCode()}:${ctx.hashCode()}"
        val st = try { JSONObject(stFile.readText()).takeIf { it.optString("sig") == sig } } catch (_: Exception) { null }
            ?: JSONObject().put("sig", sig).put("chunks", JSONObject())
        fun save() { File(dir, "polish_state.json.tmp").apply { writeText(st.toString()) }.renameTo(stFile) }

        // ---- ① 通读 ----
        if (!st.has("read")) {
            progress(t("精修 · 通读全文…", "Polishing · reading full text…"))
            val user = ctx + t("\n转写稿:\n", "\nTranscript:\n") + lines.mapIndexed { i, l -> "[$i] ${Engine.ts(l.t0)} ${l.text}" }.joinToString("\n")
            var g: JSONObject? = null
            for (attempt in 0 until 2) {
                g = try { Llm.json(Llm.call(READ_SYS, user, Store.fastModel, maxTokens = 12000, temperature = 0.2)) }
                    catch (e: Llm.BadJson) { null }
                if (g?.optJSONArray("speakers") != null) break
            }
            st.put("read", g ?: JSONObject())
            save()
            // 错词表只给下面逐段校对用。以前还顺手推进「推荐词」让人勾,Suj 2026-10-29 撤了:
            // 做风投话题换得太快,热词复用率低,几十个词人自己写比模型挑更有效率
        }
        val g = st.getJSONObject("read")

        // 逐句说话人:游程展开
        val lab = Array(lines.size) { "?" }
        val runs = g.optJSONArray("runs") ?: JSONArray()
        val rl = (0 until runs.length()).mapNotNull { runs.optJSONArray(it) }
            .mapNotNull { a -> a.optInt(0, -1).takeIf { it >= 0 }?.let { it to a.optString(1, "?") } }.sortedBy { it.first }
        rl.forEachIndexed { k, (i0, s) ->
            val i1 = if (k + 1 < rl.size) rl[k + 1].first else lines.size
            for (i in i0 until minOf(i1, lines.size)) lab[i] = s
        }
        val sp = g.optJSONArray("speakers") ?: JSONArray()
        val speakers = (0 until sp.length()).mapNotNull { sp.optJSONObject(it) }
        val roster = speakers.joinToString("\n") { s ->
            s.optString("id") + s.optString("name").takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty() + ":" + s.optString("who")
        }
        val gl = g.optJSONArray("glossary") ?: JSONArray()
        val gloss = (0 until gl.length()).mapNotNull { gl.optJSONObject(it) }.joinToString("\n") { o ->
            val w = o.optJSONArray("wrong")?.let { a -> (0 until a.length()).joinToString("/") { a.optString(it) } }.orEmpty()
            "$w → ${o.optString("right")}"
        }.ifBlank { t("(无)", "(none)") }

        // ---- 分段:按提纲,每段至少 8 分钟 ----
        val ol = g.optJSONArray("outline") ?: JSONArray()
        val bounds = ((0 until ol.length()).mapNotNull { ol.optJSONObject(it)?.optString("t0") }.map(::sec).filter { it > 0 } + 0)
            .distinct().sorted()
        val cuts = mutableListOf(0)
        for (b in bounds.drop(1)) if (b - cuts.last() >= 8 * 60) cuts += b
        val chunks = cuts.mapIndexed { k, c ->
            val end = if (k + 1 < cuts.size) cuts[k + 1] else Int.MAX_VALUE
            lines.indices.filter { lines[it].t0 >= c && lines[it].t0 < end }
        }.filter { it.isNotEmpty() }

        // ---- ② 重建(顺着来,带上一段结尾衔接)----
        val done = st.getJSONObject("chunks")
        for ((k, idx) in chunks.withIndex()) {
            if (done.has(k.toString())) continue
            progress(t("精修 · 整理对话 ${k + 1}/${chunks.size} 段", "Polishing · dialogue ${k + 1}/${chunks.size}"))
            val prev = if (k > 0) done.optString((k - 1).toString()).takeLast(500) else ""
            val user = ctx + t("\n人物:\n$roster\n\n错词表:\n$gloss\n\n", "\nPeople:\n$roster\n\nGlossary:\n$gloss\n\n") +
                (if (prev.isNotBlank()) t("上一段的结尾(接着写,不要重复):\n$prev\n\n", "End of previous section (continue from here, don't repeat):\n$prev\n\n") else "") +
                t("这一段识别稿:\n", "This section's transcript:\n") + idx.joinToString("\n") { "[${lab[it]}] ${Engine.ts(lines[it].t0)} ${lines[it].text}" }
            done.put(k.toString(), Llm.call(REBUILD_SYS, user, Store.fastModel, maxTokens = 10000, temperature = 0.2).trim())
            save()
        }

        // ---- 出稿 ----
        val cands = mutableListOf<String>()
        val body = chunks.indices.map { k ->
            var t = done.optString(k.toString())
            Regex("""<(待核对|check)>(.*?)</(待核对|check)>""", RegexOption.DOT_MATCHES_ALL).find(t)?.let { m ->
                cands += m.groupValues[2].lines().map { it.trim().trimStart('-', '•', ' ') }.filter { it.isNotBlank() }
                t = t.removeRange(m.range)
            }
            t.trim()
        }
        if (cands.isNotEmpty()) progress(t("精修 · 挑出要回听的地方", "Polishing · flagging what to re-listen to"))
        val todo = pickTodo(ctx, g.optString("summary"), cands)
        val sb = StringBuilder()
        g.optString("summary").takeIf { it.isNotBlank() }?.let { sb.append("> ").append(it).append("\n\n") }
        if (speakers.isNotEmpty()) {
            sb.append(t("**说话人**(按文字推断)\n", "**Speakers** (inferred from text)\n"))
            speakers.forEach { s ->
                sb.append("- ").append(s.optString("id"))
                s.optString("name").takeIf { it.isNotBlank() }?.let { sb.append("($it)") }
                sb.append(t(":", ": ")).append(s.optString("who")).append('\n')
            }
            sb.append('\n')
        }
        sb.append(body.joinToString("\n\n"))
        if (todo.isNotEmpty()) sb.append(t("\n\n---\n**请回听核对**\n", "\n\n---\n**Please re-listen**\n")).append(todo.joinToString("\n") { "- $it" })
        sb.append(t("\n\n*AI 根据语音识别稿整理,关键信息请以录音为准。*\n", "\n\n*Organized by AI from a speech-recognition transcript; check key facts against the recording.*\n"))
        File(dir, "polished.txt").writeText(sb.toString())
        stFile.delete()
        applyTitle(dir, g.optString("title"))
        progress(t("精修完成 · ${speakers.size} 位说话人" + if (todo.isNotEmpty()) " · ${todo.size} 处请回听核对" else "",
            "Polishing done · ${speakers.size} speaker(s)" + if (todo.isNotEmpty()) " · ${todo.size} to re-listen" else ""))
    }
}
