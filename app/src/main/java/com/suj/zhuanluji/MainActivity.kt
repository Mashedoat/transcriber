package com.suj.zhuanluji

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * 三种界面:
 *  首页   —— 中间一个大圆录音键 + 实时翻译开关;底栏 导入 / 历史 / 词库 / 电脑
 *  录音中 —— 红点计时 + 滚动字幕;底部圆形停止键
 *  看稿子 —— 标题 + 正文;底栏 精修 / 复制 / 分享 / 重新整理(左上角返回首页,右上角下载录音)
 * 不常用的(AI 设置、我是谁、备份到电脑…)收在右上角「更多」。
 */
class MainActivity : AppCompatActivity() {
    companion object {
        /** 桌面「一键录音」(长按图标的快捷方式 / 钉在桌面的图标 / 小组件)都发这个。 */
        const val ACTION_RECORD = "com.suj.zhuanluji.RECORD"
        /**
         * 主界面是导出的(桌面图标、别的 App 分享音频要用),别的 App 能指名道姓地发 ACTION_RECORD 过来。
         * 所以开录要带这个暗号:每次进程启动随机生成,只有本 App 里的 QuickRecord 知道(2026-10-31)。
         */
        private val RECORD_TOKEN = java.util.UUID.randomUUID().toString()
        fun recordIntent(c: Context) = Intent(c, MainActivity::class.java).setAction(ACTION_RECORD).putExtra("token", RECORD_TOKEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private enum class Mode { IDLE, REC, SESSION }
    private var mode = Mode.IDLE
    private val PUB = BuildConfig.PUBLIC     // 公开版:不连电脑,AI 用用户自己的 key

    private lateinit var status: TextView
    private lateinit var hero: LinearLayout
    private lateinit var heroBox: ScrollView
    private lateinit var dlBtn: Button
    private val trBars = mutableListOf<LinearLayout>()   // 首页和录音界面各一条翻译方向
    private lateinit var recHeader: LinearLayout
    private lateinit var recTools: LinearLayout
    private lateinit var textCol: LinearLayout
    private lateinit var toLatest: TextView
    private var followTail = true            // 字幕是否自动跟到最新一句
    private lateinit var askPanel: LinearLayout
    private lateinit var askScroll: ScrollView
    private lateinit var askText: TextView
    private lateinit var askInput: EditText
    private lateinit var askState: TextView
    @Volatile private var asking = false
    private lateinit var timer: TextView
    private lateinit var scroll: ScrollView
    private lateinit var body: TextView
    private lateinit var navBar: LinearLayout
    private lateinit var sessionBar: LinearLayout
    private lateinit var sendLabel: TextView
    private lateinit var stopBar: FrameLayout
    private lateinit var stopBtn: FrameLayout

    private var shown: File? = null          // 正在看的那场录音
    @Volatile private var busy = false       // 载模型/下载/重新整理中

    private fun c(id: Int) = getColor(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        buildUi()
        askPermissions()
        // 长按桌面图标出「一键录音」。用动态快捷方式而不是 xml:两个版本包名不同,xml 里得写死包名
        // 以前钉在桌面的那个也改指向透明跳板(默默开录,不翻出界面)
        republishShortcuts()
        Live.listener = ::onEvent
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == Mode.SESSION) goHome() else finish()
            }
        })
        if (RecordService.running) redrawLive() else { goHome(); refreshModelState() }
        if (WorkService.running && WorkService.current.isNotEmpty()) setStatus(WorkService.current)
        // 上次被杀留下的:补 WAV 头、导入到一半的重新排上
        if (savedInstanceState == null) thread {
            val n = WorkService.resumeImports(this)
            if (n > 0) runOnUiThread { toast(t("上次有 $n 个导入没做完,重新排上了", "$n import(s) didn't finish last time, queued again")) }
        }
        handleShare(intent)
        handleRecord(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        handleRecord(intent)
    }

    /**
     * 从桌面「一键录音」进来(一般走透明跳板 QuickRecord 默默开录;没录音权限、或者已经在录时才到这里):
     * 直接开录;已经在录就只把录音界面翻出来(绝不当成"停")。
     */
    private var recordAfterGrant = false
    private fun handleRecord(i: Intent?) {
        if (i?.action != ACTION_RECORD) return
        i.action = null                          // 开合屏 / 从最近任务回来时别再录一场
        if (i.getStringExtra("token") != RECORD_TOKEN) return    // 不是自己发的:只打开 App,不开麦
        if (RecordService.running) return redrawLive()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) recordAfterGrant = true
        toggleRecord()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        // 一键录音进来时 onCreate 和 handleRecord 前后各要一次权限,系统把后一次当「取消」立刻回空数组;
        // 别因为它把「授权后开录」清掉,等真正的那次回来(没权限时点一键录音,授权后不开录,2026-10-31 查到)
        if (perms.isEmpty()) return
        if (recordAfterGrant && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            && !RecordService.running) toggleRecord()
        recordAfterGrant = false
    }

    /** 在桌面默默开录后再点开 App:翻到录音界面。 */
    override fun onResume() {
        super.onResume()
        if (RecordService.running && mode != Mode.REC) redrawLive()
    }

    /** 界面回到前台:取消「放下模型」;闲着时被放下过就重新读进来(本地文件,不联网)。 */
    override fun onStart() {
        super.onStart()
        Engine.uiVisible = true
        Engine.cancelRelease()
        if (::status.isInitialized && mode == Mode.IDLE && Engine.asr == null && Engine.modelsReady() && !busy) loadModel()
    }

    /** 界面退到后台:没在录音、没在转写的话,闲 3 分钟就把识别模型从内存放下。 */
    override fun onStop() {
        Engine.uiVisible = false
        Engine.scheduleRelease()
        super.onStop()
    }

    private fun toggleSaver() {
        Store.saver = !Store.saver
        val now = Engine.asr != null && Engine.loadedThreads != Engine.threads()
        if (now && !RecordService.running && !busy) loadModel(reload = true)
        toast(if (Store.saver) t("省电模式开了", "Power saver on") + (if (RecordService.running) t(",下一场生效", " — applies from the next recording") else "")
              else t("省电模式关了", "Power saver off") + (if (RecordService.running) t(",下一场生效", " — applies from the next recording") else ""))
    }

    private fun bgAllowed() = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun bgItemTitle() = if (bgAllowed()) t("锁屏也接着转 · 已允许", "Keep working when locked · Allowed") else t("锁屏也接着转 · 未允许", "Keep working when locked · Not allowed")
    private fun bgItemDesc() = t("转写、精修长录音时锁屏不停", "Long transcriptions keep going with the screen off")

    /**
     * 长活开始前:没进系统「电池优化」白名单就问一次(force = 从菜单点进来,每次都弹)。
     * vivo 真机 2026-10-29:锁屏后后台转写几乎不动——系统和 vivo 自己的省电各管一层,两处都要放行。
     */
    private fun askBackground(force: Boolean = false) {
        if (!force && (bgAllowed() || Store.askedBg)) return
        Store.askedBg = true
        val msg = t("锁屏后想让它接着转写 / 精修,手机要放行两处:\n\n" +
                "① 点「去允许」,系统会问「让转录机一直在后台运行?」,选允许。\n\n" +
                "② vivo / OPPO / 小米等还有自己的省电:设置里搜「后台耗电」,把转录机设成「允许后台高耗电」(或「无限制」)。" +
                (if (bgAllowed()) "\n\n①已经允许过了。想收回:点「去设置」,把转录机改回「优化」。转录机只在录音、转写、精修时才用电,闲着不占。" else ""),
            "To keep transcribing / polishing with the screen locked, allow two things:\n\n" +
                "① Tap \"Allow\" — the system will ask whether Transcriber may always run in the background. Say yes.\n\n" +
                "② Some phones (vivo, OPPO, Xiaomi…) have their own battery manager: search Settings for \"background\" / \"battery\" and set Transcriber to unrestricted." +
                (if (bgAllowed()) "\n\n① is already allowed. To take it back, tap \"Settings\" and set Transcriber to \"Optimized\". It only uses power while recording, transcribing or polishing." else ""))
        val b = AlertDialog.Builder(this).setTitle(t("锁屏也接着转", "Keep working when locked")).setMessage(msg)
            .setNegativeButton(t("知道了", "Got it"), null)
        if (!bgAllowed()) b.setPositiveButton(t("去允许", "Allow")) { _, _ ->
            try {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } else b.setPositiveButton(t("去设置", "Settings")) { _, _ ->
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        b.show()
    }

    private fun saverItemTitle() = if (Store.saver) t("省电模式 · 开", "Power saver · On") else t("省电模式 · 关", "Power saver · Off")
    private fun saverItemDesc() = t("识别少用两个核:发热小,字幕可能慢一点", "Uses 2 fewer CPU cores: cooler phone, captions may lag a bit")

    override fun onDestroy() {
        if (Live.listener == ::onEvent) Live.listener = null
        super.onDestroy()
    }

    // ================= 界面搭建 =================
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c(R.color.bg)) }

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(c(R.color.muted))
            setPadding(dp(20), dp(10), dp(20), dp(6))
            // 后台在转 / 排着队时,点顶上这行可以全部停下(进度都存着)
            setOnClickListener { if (WorkService.working) confirmStopWork() }
        }
        root.addView(status)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ---- 首页:大圆录音键 ----
        hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(16)) }
        // 圆键大小跟着屏幕走:外屏矮,别把它顶出去(真机截图里上沿被切了)
        val h = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val bigSize = dp(minOf(148, maxOf(104, (h * 0.2f).toInt())))
        val big = roundButton(bigSize, R.color.clay, R.color.clay_dark, R.drawable.ic_mic, bigSize * 43 / 100) { toggleRecord() }
        hero.addView(big, LinearLayout.LayoutParams(bigSize, bigSize))
        // 文字都 WRAP_CONTENT + 居中:默认宽度是撑满,字就靠左了(真机截图)
        val centered = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL } }
        hero.addView(TextView(this).apply {
            text = t("点一下开始录音", "Tap to start recording")
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(c(R.color.ink))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(24), 0, dp(4))
        }, centered())
        hero.addView(TextView(this).apply {
            text = t("离线出字幕 · 精修时按语义分说话人", "Offline captions · polishing splits speakers by meaning")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(c(R.color.muted))
        }, centered())
        hero.addView(TextView(this).apply {
            text = t("实时翻译(要联网)", "Live translation (needs network)")
            textSize = 13f
            setTextColor(c(R.color.muted))
        }, centered().apply { topMargin = dp(28) })
        hero.addView(trBar(), centered().apply { topMargin = dp(6) })
        dlBtn = pill(if (PUB) t("下载模型(约 880 MB)", "Download models (~880 MB)") else t("下载模型(约 1 GB)", "Download models (~1 GB)")) { downloadModels() }
        hero.addView(dlBtn, centered().apply { topMargin = dp(20) })
        // 外套一层可滑动:放得下就居中;小手机横屏/大字体放不下时能往下滑,不会切掉圆键和翻译胶囊
        heroBox = ScrollView(this).apply {
            isFillViewport = true
            addView(hero, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        content.addView(heroBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- 录音中 / 看稿子:正文 ----
        textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c(R.color.rec)) }
            }, LinearLayout.LayoutParams(dp(12), dp(12)))
            timer = TextView(context).apply {
                capSize(26f)
                typeface = Typeface.MONOSPACE
                setTextColor(c(R.color.ink))
                setPadding(dp(12), 0, 0, 0)
            }
            addView(timer)
            // 「问 AI」放计时这行右边:和四个翻译胶囊挤一排的话,小屏/大字体会被挤出屏外
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(chip(t("问 AI", "Ask AI"), on = true) { openAsk() })
        }
        textCol.addView(recHeader)
        // 录音时:翻译方向随时切
        recTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
            addView(trBar(small = true))
        }
        textCol.addView(recTools)
        scroll = ScrollView(this).apply { isFillViewport = true }
        body = TextView(this).apply {
            textSize = 17f
            setTextColor(c(R.color.ink))
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.35f)
            setPadding(dp(20), dp(4), dp(20), dp(24))
        }
        scroll.addView(body)
        // 录音时往上翻旧字幕,右下角浮出「↓ 最新」;回到底部就消失、接着自动跟着滚(Suj 2026-10-28 真机反馈)
        toLatest = chip(t("↓ 最新", "↓ Latest"), on = true) { followTail = true; scrollToEnd() }.apply {
            elevation = dp(4).toFloat(); visibility = View.GONE
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (mode != Mode.REC) return@setOnScrollChangeListener
            followTail = !scroll.canScrollVertically(1)
            toLatest.visibility = if (followTail) View.GONE else View.VISIBLE
        }
        textCol.addView(FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(toLatest, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, dp(16), dp(12)) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        textCol.addView(buildAskPanel(), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.15f))
        content.addView(textCol, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- 底栏 ----
        navBar = bar(
            Triple(R.drawable.ic_import, t("导入音频", "Import audio")) { pickAudio.launch(arrayOf("audio/*")) },
            Triple(R.drawable.ic_history, t("历史", "History")) { showHistory() },
            Triple(R.drawable.ic_book, t("词库", "Vocabulary")) { editVocab() },
            if (PUB) Triple(R.drawable.ic_settings, t("设置", "Settings")) { showSettings() }
            else Triple(R.drawable.ic_home, t("电脑稿库", "PC library")) { openWeb(null) },
        )
        root.addView(navBar)
        sessionBar = bar(
            Triple(R.drawable.ic_polish, t("精修", "Polish")) { polish() },
            Triple(R.drawable.ic_copy, t("复制", "Copy")) { copyDraft() },
            Triple(R.drawable.ic_share, t("分享", "Share")) { shareDraft() },
            Triple(R.drawable.ic_refresh, t("重新整理", "Re-process")) { reprocess() },
        )
        sendLabel = (sessionBar.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        root.addView(sessionBar)
        stopBar = FrameLayout(this).apply { setPadding(0, dp(12), 0, dp(20)) }
        stopBtn = roundButton(dp(76), R.color.rec, R.color.rec, R.drawable.ic_stop, dp(34)) { toggleRecord() }
        stopBar.addView(stopBtn, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
        root.addView(stopBar)

        setContentView(root)
        // 用返回键/手势收键盘会退出触摸模式,拿着焦点的整块正文会被系统盖一层灰(默认焦点高亮),整页像变暗了
        fun noFocusTint(v: View) {
            v.defaultFocusHighlightEnabled = false
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) noFocusTint(v.getChildAt(i))
        }
        noFocusTint(root)
    }

    /** 圆形按钮:实心底色 + 白色图标 + 点按水波纹。 */
    private fun roundButton(size: Int, color: Int, ripple: Int, icon: Int, iconSize: Int, onClick: () -> Unit) =
        FrameLayout(this).apply {
            val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c(color)) }
            background = RippleDrawable(ColorStateList.valueOf(c(ripple)), shape, null)
            elevation = dp(6).toFloat()
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(c(R.color.on_clay))
            }, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            minimumWidth = size; minimumHeight = size
        }

    /** 胶囊形的次要按钮(描边)。 */
    private fun pill(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(c(R.color.forest).let { if (isNight()) c(R.color.clay) else it })
        stateListAnimator = null
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), c(R.color.clay))
            setColor(c(R.color.surface))
        }
        setPadding(dp(24), 0, dp(24), 0)
        setOnClickListener { onClick() }
    }

    private fun isNight() =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** 底栏:等宽四格,图标在上、字在下,不用横着滑。 */
    private fun bar(vararg items: Triple<Int, String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(c(R.color.surface))
        setPadding(0, dp(8), 0, dp(10))
        elevation = dp(8).toFloat()
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }
        for ((icon, label, onClick) in items) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(ripple.resourceId)
                isClickable = true
                setOnClickListener { onClick() }
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(c(if (isNight()) R.color.clay else R.color.forest))
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(TextView(context).apply {
                    text = label
                    capSize(12f)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(c(R.color.ink))
                    setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    /** 内屏很宽:正文一行别拉满屏,限在 ~680dp 居中(像看书);开合屏时重算。 */
    private fun applyReadableWidth() {
        val wDp = resources.configuration.screenWidthDp
        val side = if (wDp > 720) dp((wDp - 680) / 2) else dp(20)
        body.setPadding(side, dp(4), side, dp(24))
        recHeader.setPadding(side, dp(4), side, dp(8))
        recTools.setPadding(side - dp(4), 0, side - dp(4), dp(8))
        status.setPadding(side, dp(10), side, dp(6))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyReadableWidth()
    }

    private fun setMode(m: Mode) {
        applyReadableWidth()
        mode = m
        heroBox.visibility = if (m == Mode.IDLE) View.VISIBLE else View.GONE
        textCol.visibility = if (m == Mode.IDLE) View.GONE else View.VISIBLE
        if (m != Mode.REC) toLatest.visibility = View.GONE
        recHeader.visibility = if (m == Mode.REC) View.VISIBLE else View.GONE
        recTools.visibility = if (m == Mode.REC) View.VISIBLE else View.GONE
        navBar.visibility = if (m == Mode.IDLE) View.VISIBLE else View.GONE
        sessionBar.visibility = if (m == Mode.SESSION) View.VISIBLE else View.GONE
        stopBar.visibility = if (m == Mode.REC) View.VISIBLE else View.GONE
        supportActionBar?.setDisplayHomeAsUpEnabled(m == Mode.SESSION)
        supportActionBar?.title = if (m == Mode.REC) t("录音中", "Recording") else t("转录机", "Transcriber")
        refreshTr()
        if (m == Mode.IDLE && askPanel.visibility == View.VISIBLE) closeAsk()
        invalidateOptionsMenu()
    }

    private fun goHome() {
        shown = null
        setMode(Mode.IDLE)
    }

    override fun onSupportNavigateUp(): Boolean { goHome(); return true }

    // ---- 右上角:看稿子时多一个「下载录音」;「更多」点开是卡片菜单(系统那种只是几行字,看不出能点)----
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 10, 0, t("下载录音", "Download audio")).setIcon(R.drawable.ic_download).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 99, 1, t("更多", "More")).setIcon(R.drawable.ic_more).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(10)?.isVisible = mode == Mode.SESSION
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            10 -> shown?.let { exportAudio(it) }
            99 -> showMenu()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private class Item(val title: String, val desc: String = "", val danger: Boolean = false, val run: () -> Unit)

    /** 右上角菜单:分几组卡片,米白底圆角,从右上角落下来。 */
    private fun showMenu() {
        val dir = shown.takeIf { mode == Mode.SESSION }
        val groups = listOf(
            if (dir != null) listOfNotNull(
                if (Store.polishedOf(dir) == null) null
                else if (rawView) Item(t("看精修稿", "Show polished"), t("切回整理好的对话稿", "Back to the organized dialogue")) { rawView = false; showSession(dir) }
                else Item(t("看原稿", "Show original"), t("精修前的逐句稿(带时间),复制、分享也跟着换", "The line-by-line draft from before polishing (timestamped); Copy/Share follow it")) { rawView = true; showSession(dir) },
                Item(t("改名", "Rename"), Store.titleOf(dir) ?: "") { renameSession(dir) },
                // 以前精修过、还没 AI 标题的(包括 10-29 那场两小时的):拿精修稿的概要起一个
                if (Store.polishedOf(dir) != null && Store.titleSrc(dir).let { it == null || it == "file" })
                    Item(t("起标题", "Suggest a title"), t("AI 按精修稿起个名字", "AI names it from the polished transcript")) { suggestTitle(dir) }
                else null,
                Item(t("问 AI", "Ask AI"), t("关于这场随便问,必要时联网查", "Ask anything about this session, searches the web if needed")) { openAsk(dir) },
                Item(t("删除这场录音", "Delete this recording"), danger = true) { confirmDelete(listOf(dir)) },
            ) else emptyList(),
            listOfNotNull(
                Item(t("AI 设置", "AI settings"), if (Llm.ready()) t("翻译、精修、问 AI · 已填 key", "Translation, polish, Ask AI · key set") else t("翻译、精修、问 AI · 还没填 key", "Translation, polish, Ask AI · no key yet")) { editAi() },
                Item(t("我是谁", "Who am I"), Store.me.take(24).ifBlank { t("告诉 AI 录音的人是谁,分说话人更准", "Tell the AI who's recording, for better speaker splits") }) { editMe() },
                Item(t("语言 · 中文", "Language · English"), t("切换成英文界面和英文 AI", "Switch to Chinese UI & AI")) { pickLanguage() },
                Item(t("在桌面放「一键录音」", "Add \"Quick record\" to home screen"), t("点一下就默默开录", "Tap it to start recording silently")) { pinRecordShortcut() },
                Item(saverItemTitle(), saverItemDesc()) { toggleSaver() },
                Item(bgItemTitle(), bgItemDesc()) { askBackground(force = true) },
                if (Engine.modelsReady()) null
                else Item(t("下载识别模型", "Download recognition models"), if (PUB) t("约 880 MB,建议连 Wi-Fi", "~880 MB, Wi-Fi recommended") else t("从电脑拉,约 1 GB", "From PC, ~1 GB")) { downloadModels() },
            ),
            if (PUB) listOf(Item(t("关于与隐私", "About & privacy")) { showAbout() })
            else listOfNotNull(
                if (dir != null) Item(t("备份到电脑", "Back up to PC"), if (Uploader.backedUp(dir)) t("电脑上已经是最新的", "Already up to date on PC") else t("录音压缩后连同稿子传过去", "Compresses the audio and sends it with the transcript")) { backupToPc(listOf(dir)) }
                else Item(t("全部备份到电脑", "Back up everything to PC"), t("没备份过、或稿子改过的都传过去", "Sends anything not backed up or since changed")) { backupToPc(Store.sessions()) },
                Item(t("电脑上的稿库", "PC library"), t("回听录音、看稿", "Replay recordings, read transcripts")) { openWeb(dir) },
                Item(t("电脑地址", "PC address"), Store.server.removePrefix("http://")) { editServer() },
            ),
        ).filter { it.isNotEmpty() }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        val pop = android.widget.PopupWindow(ScrollView(this).apply { addView(box) }, dp(272), WRAP_CONTENT, true).apply {
            setBackgroundDrawable(getDrawable(R.drawable.popup_bg))
            elevation = dp(12).toFloat()
        }
        groups.forEachIndexed { gi, items ->
            if (gi > 0) box.addView(View(this).apply { setBackgroundColor(c(R.color.line)) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { setMargins(dp(10), dp(2), dp(10), dp(8)) })
            for (m in items) box.addView(card(m.title, m.desc, danger = m.danger) { pop.dismiss(); m.run() },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        // 从标题栏下沿、靠右落下来
        val loc = IntArray(2).also { status.getLocationInWindow(it) }
        pop.showAtLocation(window.decorView, Gravity.TOP or Gravity.END, dp(8), loc[1])
    }

    /**
     * 一张卡片按钮:粗体标题 + 灰色说明,圆角、有水波纹。对话框选项、右上角菜单、历史列表都用它
     * (Suj 2026-10-28:选项要有「按钮感」,背景柔和)。primary = 陶土金实心,是推荐的那个。
     */
    private fun card(title: String, desc: String = "", primary: Boolean = false, danger: Boolean = false,
                     onLong: (() -> Unit)? = null, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(11), dp(16), dp(11))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (primary) setColor(c(R.color.clay)) else { setColor(c(R.color.surface)); setStroke(dp(1), c(R.color.line)) }
        }
        val ripple = (c(R.color.clay) and 0x00FFFFFF) or 0x66000000
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
        isClickable = true
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
        addView(TextView(context).apply {
            text = title
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(when { primary -> R.color.on_clay; danger -> R.color.rec; else -> R.color.ink }))
        })
        if (desc.isNotEmpty()) addView(TextView(context).apply {
            text = desc
            textSize = 12.5f
            setTextColor(c(if (primary) R.color.on_clay else R.color.muted))
            setPadding(0, dp(2), 0, 0)
        })
    }

    /** 小胶囊:on = 陶土金实心(选中 / 主按钮),否则描边。 */
    private fun chip(text: String, on: Boolean = false, small: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        capSize(if (small) 13f else 14f)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(if (small) 10 else 14), dp(5), dp(if (small) 10 else 14), dp(5))
        styleChip(this, on)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 胶囊、底栏这种一排并列的小控件:跟着系统字体放大,但最多 1.3 倍,不然小屏一排挤不下(正文照常放大)。 */
    private fun TextView.capSize(sp: Float, max: Float = 1.3f) =
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp * minOf(resources.configuration.fontScale, max))

    private fun styleChip(v: TextView, on: Boolean) {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            if (on) setColor(c(R.color.clay)) else { setColor(c(R.color.surface)); setStroke(dp(1), c(R.color.line)) }
        }
        v.background = RippleDrawable(ColorStateList.valueOf((c(R.color.clay) and 0x00FFFFFF) or 0x66000000), shape, null)
        v.setTextColor(c(if (on) R.color.on_clay else R.color.ink))
        v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // ---- 实时翻译方向:不翻 / 英→中 / 中→英 / 双向,录音中途随时切(只管之后的句子)----
    private val TR_LABELS get() = listOf(Store.TR_OFF to t("不翻", "Off"), Store.TR_EN to t("英→中", "EN→ZH"), Store.TR_ZH to t("中→英", "ZH→EN"), Store.TR_BOTH to t("双向", "Both"))

    private fun trBar(small: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((mode, label) in TR_LABELS) addView(chip(label, small = small) { setTr(mode) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(if (small) 4 else 6) })
        trBars += this
        refreshTr()
    }

    private fun setTr(mode: Int) {
        if (mode != Store.TR_OFF && PUB && !Llm.ready()) {   // 公开版翻译要用户自己的 key
            toast(t("实时翻译要先填 AI 的 key", "Live translation needs an AI key first")); return editAi()
        }
        Store.trMode = mode
        refreshTr()
        if (RecordService.running) toast(when (mode) {
            Store.TR_EN -> t("之后只翻英文句子", "From now on, only English sentences translate")
            Store.TR_ZH -> t("之后只翻中文句子", "From now on, only Chinese sentences translate")
            Store.TR_BOTH -> t("之后的句子都翻", "From now on, every sentence translates")
            else -> t("不翻了", "Translation off") })
    }

    private fun refreshTr() {
        val cur = Store.trMode
        for (bar in trBars) for (i in 0 until bar.childCount) styleChip(bar.getChildAt(i) as TextView, TR_LABELS[i].first == cur)
    }

    // ---- 问 AI:录音时 / 回看时在稿子下面拉出半屏,拿目前为止的稿子回答,必要时联网搜 1~2 次 ----
    private var askDir: File? = null

    private fun buildAskPanel(): LinearLayout {
        askPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.surface))
            elevation = dp(6).toFloat()
            visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        askPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = t("问 AI", "Ask AI"); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c(R.color.accent))
            })
            askState = TextView(context).apply {
                textSize = 12.5f; setTextColor(c(R.color.muted)); setPadding(dp(10), 0, 0, 0); maxLines = 1
            }
            addView(askState, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = t("收起", "Close"); textSize = 13f; setTextColor(c(R.color.muted)); setPadding(dp(10), dp(4), 0, dp(4))
                setOnClickListener { closeAsk() }
            })
        })
        askText = TextView(this).apply {
            textSize = 15f
            setTextColor(c(R.color.ink))
            setLineSpacing(0f, 1.3f)
            setTextIsSelectable(true)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        askScroll = ScrollView(this).apply { addView(askText) }
        askPanel.addView(askScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        askPanel.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                for ((label, q) in Ask.QUICK) addView(chip(label, small = true) { sendAsk(q, label) },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
            })
        })
        askInput = EditText(this).apply {
            hint = t("问点什么…(要查最新消息它会自己联网)", "Ask anything… (it'll search for current info on its own)")
            textSize = 15f
            maxLines = 3
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setOnEditorActionListener { _, id, _ ->
                if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { sendAsk(text.toString()); true } else false
            }
        }
        askPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(askInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(chip(t("问", "Ask"), on = true) { sendAsk(askInput.text.toString()) })
        })
        return askPanel
    }

    private fun openAsk(dir: File? = null) {
        val d = dir ?: RecordService.dir ?: shown ?: return toast(t("先开始录音,或打开一场", "Start recording first, or open a session"))
        if (!Llm.ready()) { toast(t("问 AI 要先在手机上填 AI 的 key", "Ask AI needs an AI key set on the phone first")); return editAi() }
        askDir = d
        askPanel.visibility = View.VISIBLE
        drawAsk()
    }

    private fun closeAsk() {
        askPanel.visibility = View.GONE
        askDir = null
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(askInput.windowToken, 0)
    }

    /** 问答记录:问题加粗带时间,答案按简单 Markdown 排,下面列搜过的网页(能点开)。 */
    private fun drawAsk(pending: String? = null) {
        val d = askDir ?: return
        val sb = SpannableStringBuilder()
        val list = Ask.load(d)
        if (list.isEmpty() && pending == null) {
            val hint = t("拿目前为止的稿子回答;要查最新消息、核实公司或人名时会自己联网(最多搜 2 次)。\n问答单独存在这场里,不会混进精修稿。",
                "Answers using the transcript so far; it'll search the web for current info or to check a company or name (at most 2 times).\nQ&A is stored separately in this session, never mixed into the polished transcript.")
            sb.append(hint)
            sb.setSpan(ForegroundColorSpan(c(R.color.muted)), 0, hint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        var last = 0                                   // 最新那条问答从哪个字开始
        for (qa in list) { last = sb.length; appendQa(sb, qa.q, qa.at, qa.a, qa.sources, qa.searched) }
        if (pending != null) { last = sb.length; appendQa(sb, pending, -1, null, emptyList(), emptyList()) }
        askText.text = sb
        // 停在最新问题的开头,不滚到底:答案长时滚到底只看得见最后半句和出处
        askScroll.post {
            val l = askText.layout ?: return@post
            askScroll.scrollTo(0, l.getLineTop(l.getLineForOffset(last)) + askText.paddingTop)
        }
    }

    private fun appendQa(sb: SpannableStringBuilder, q: String, at: Int, a: String?,
                         sources: List<Pair<String, String>>, searched: List<String>) {
        var s = sb.length
        sb.append("▸ ").append(q)
        sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(c(R.color.accent)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (at >= 0) {
            s = sb.length
            sb.append("  ").append(Engine.ts(at.toFloat()))
            sb.setSpan(ForegroundColorSpan(c(R.color.muted)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append('\n')
        if (a == null) {
            s = sb.length
            sb.append("…\n\n")
            sb.setSpan(ForegroundColorSpan(c(R.color.muted)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }
        val answerStart = sb.length
        sb.append(renderMd(a))
        for (citation in Regex("【(\\d+)】").findAll(sb.subSequence(answerStart, sb.length))) {
            val index = citation.groupValues[1].toIntOrNull()?.minus(1) ?: continue
            val url = sources.getOrNull(index)?.second ?: continue
            sb.setSpan(android.text.style.URLSpan(url), answerStart + citation.range.first,
                answerStart + citation.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (searched.isNotEmpty() || sources.isNotEmpty()) {
            s = sb.length
            sb.append(t("联网搜了:", "Searched: ")).append(searched.joinToString(t("、", ", "))).append('\n')
            for ((title, url) in sources) {
                val b = sb.length
                sb.append("↗ ").append(title.ifBlank { url }.take(40))
                sb.setSpan(android.text.style.URLSpan(url), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append('\n')
            }
            sb.setSpan(RelativeSizeSpan(0.85f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(c(R.color.muted)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append('\n')
    }

    private fun sendAsk(raw: String, label: String? = null) {
        val q = raw.trim()
        val d = askDir ?: return
        if (q.isEmpty()) return
        if (asking) return toast(t("上一个问题还在答", "Still answering the last question"))
        asking = true
        askInput.setText("")
        // 收起键盘:不然答案只剩键盘上面一小条
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(askInput.windowToken, 0)
        askInput.clearFocus()
        val recording = RecordService.running && RecordService.dir == d
        val lines = if (recording) Live.lines.toList() else Store.loadLines(d)
        val at = if (recording) Live.elapsed else -1
        drawAsk(pending = label ?: q)
        askState.text = t("正在想…", "Thinking…")
        thread {
            val err = try {
                Ask.ask(d, lines, at, q) { m -> runOnUiThread { askState.text = m } }; null
            } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            asking = false
            runOnUiThread {
                askState.text = if (err != null) t("没答上来:$err", "Couldn't answer: $err") else ""
                if (askDir == d) drawAsk()
            }
        }
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    // ================= 导入外来音频 =================
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        // 长期读权限:转一小时的途中界面被系统回收,后台服务也还读得到这个文件
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        importAudio(uri)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri) ?: return
        i.action = null                          // 折叠屏开合 / 回到前台时别再弹一次
        importAudio(uri)
    }

    /** 一个选项 = 一张卡片按钮(粗体标题 + 灰色说明)。以前用 setItems 只是几行字,真机上看不出能点。 */
    private class Choice(val title: String, val desc: String, val primary: Boolean = false, val run: () -> Unit)

    private fun choose(title: String, choices: List<Choice>, message: String? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val b = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(t("取消", "Cancel"), null)
        if (message != null) b.setMessage(message)
        val dlg = b.create()
        for (ch in choices) box.addView(card(ch.title, ch.desc, primary = ch.primary) { dlg.dismiss(); ch.run() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        dlg.show()
    }

    /** 导入外来音频:都在手机上转(电脑那条 1.7B 精转的路 2026-10-28 撤了),转完可以「精修」。 */
    private fun importAudio(uri: Uri) {
        val name = Importer.displayName(this, uri)
        setStatus(t("正在读取「$name」…", "Reading \"$name\"…"))
        askBackground()
        thread {
            try {
                val f = Importer.copyIn(this, uri, name)
                WorkService.import(this, f, name.substringBeforeLast('.'))
                runOnUiThread { toast(if (WorkService.pending() > 0) t("已排队", "Queued") else t("开始导入,进度看顶上", "Importing, check progress at the top")) }
            } catch (e: Exception) {
                runOnUiThread { setStatus(t("读不了这个文件:${e.message}", "Can't read this file: ${e.message}")) }
            }
        }
    }

    // ================= 模型 =================
    private fun refreshModelState() {
        val ready = Engine.modelsReady()
        dlBtn.visibility = if (ready) View.GONE else View.VISIBLE
        when {
            !ready -> setStatus(if (PUB) t("第一次用:先点「下载模型」(约 880 MB,建议连 Wi-Fi)。没模型也能先只录音", "First time here: tap \"Download models\" (~880 MB, Wi-Fi recommended). You can also just record without it")
                                else t("还没有模型:点「下载模型」从电脑拉,或先只录音", "No models yet: tap \"Download models\" to pull from PC, or just record for now"))
            Engine.asr == null && !busy -> loadModel()
            Engine.asr != null -> setStatus(t("模型就绪 · 词库 ${Store.vocab().size} 个词", "Model ready · ${Store.vocab().size} vocabulary words"))
        }
    }

    /** reload=true:词库改了,带新词库重载;否则只在还没载时载(录音服务可能已经在载了,会等它)。 */
    private fun loadModel(reload: Boolean = false) {
        busy = true
        setStatus(t("正在载入模型(首次十来秒)…", "Loading model (takes ~10s the first time)…"))
        thread {
            val msg = try {
                if (reload) Engine.loadAsr(Store.vocab()) else Engine.ensureAsr(Store.vocab())
                t("模型就绪 · 词库 ${Store.vocab().size} 个词", "Model ready · ${Store.vocab().size} vocabulary words")
            } catch (e: Throwable) { t("模型载入失败:${e.message}", "Model failed to load: ${e.message}") }
            busy = false
            runOnUiThread { if (mode != Mode.REC) setStatus(msg) }   // 录音中顶上显示录音状态
        }
    }

    private val pickModelPkg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        WorkService.models(this, uri)
        toast(t("开始解模型包,进度看顶上(锁屏也会接着做)", "Unpacking model package, check progress at the top (keeps going with screen locked)"))
    }

    private fun downloadModels() {
        if (busy) return
        if (Engine.modelsReady()) return toast(t("模型已经在手机上了", "Models are already on the phone"))
        if (PUB) {
            if (WorkService.queued(WorkService.Task.Models(null))) return toast(t("已经在下了,进度看顶上", "Already downloading, check progress at the top"))
            AlertDialog.Builder(this).setTitle(t("下载识别模型", "Download recognition models"))
                .setMessage(t("Qwen3-ASR 0.6B(阿里开源),装好后录音、字幕全离线。\n" +
                    "约 880 MB,解开后占 1 GB 左右,建议连 Wi-Fi。断了再点会接着下。\n\n" +
                    "国内连 GitHub 慢的话:先用别的办法把\n${Engine.QWEN}.tar.bz2\n下到手机上,再选「我已经有模型包」。",
                    "Qwen3-ASR 0.6B (open-sourced by Alibaba); once installed, recording and captions work fully offline.\n" +
                    "~880 MB, ~1 GB unpacked, Wi-Fi recommended. Tap again after an interruption to resume.\n\n" +
                    "If GitHub is slow: get\n${Engine.QWEN}.tar.bz2\nonto the phone some other way, then pick \"I already have the package\"."))
                .setPositiveButton(t("从 GitHub 下载", "Download from GitHub")) { _, _ ->
                    WorkService.models(this, null); toast(t("开始下载,锁屏也会接着下", "Downloading, keeps going with screen locked"))
                }
                .setNeutralButton(t("我已经有模型包", "I already have the package")) { _, _ -> pickModelPkg.launch(arrayOf("*/*")) }
                .setNegativeButton(t("取消", "Cancel"), null).show()
            return
        }
        busy = true
        dlBtn.isEnabled = false
        thread {
            val err = try { Downloader.run { m -> runOnUiThread { setStatus(m) } }; null }
                      catch (e: Throwable) { t("下载失败:${e.message}\n检查右上角「电脑地址」,手机要连着 Tailscale", "Download failed: ${e.message}\nCheck \"PC address\" top right, phone needs Tailscale connected") }
            busy = false
            runOnUiThread {
                dlBtn.isEnabled = true
                if (err != null) setStatus(err) else refreshModelState()
            }
        }
    }

    private fun setStatus(s: String) { status.text = s }

    private fun confirmStopWork() {
        val q = WorkService.pending()
        AlertDialog.Builder(this).setTitle(t("停下后台的活?", "Stop background work?"))
            .setMessage(t("正在做:${WorkService.current.lineSequence().first()}", "Working on: ${WorkService.current.lineSequence().first()}") +
                (if (q > 0) t("\n后面还排着 $q 件,会一起清掉。", "\n$q more queued — those get cleared too.") else "") +
                t("\n\n转好的部分都存着,之后点「重新整理」/「精修」从断处接着做。",
                  "\n\nWhatever's done is saved; tap \"Re-process\" / \"Polish\" later to continue from there."))
            .setPositiveButton(t("停下", "Stop")) { _, _ ->
                WorkService.stopAll()
                toast(t("在下一句话处停", "Stopping at the next sentence"))
            }
            .setNegativeButton(t("接着做", "Keep going"), null).show()
    }

    // ================= 录音 =================
    private fun toggleRecord() {
        if (RecordService.running) {
            RecordService.stop(this)
            stopBtn.isEnabled = false
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); return
        }
        RecordService.start(this)                // 会清掉上一场的 Live 字幕
        shown = null
        timer.text = Engine.ts(0f)
        setMode(Mode.REC)
        followTail = true
        redrawCaptions()                         // 还没字幕时显示一句提示,不是一片空白
        stopBtn.isEnabled = true
    }

    private fun onEvent(e: Live.Ev) {
        when (e) {
            is Live.Ev.Caption -> { if (mode == Mode.REC) appendCaption(e.line) }
            is Live.Ev.Translated -> if (mode == Mode.REC) redrawCaptions()
            is Live.Ev.Status -> {
                setStatus(e.text)
                if (mode == Mode.REC && Live.lines.isEmpty()) redrawCaptions()   // 模型载好了:换掉"还在载入"的提示
            }
            is Live.Ev.Elapsed -> timer.text = Engine.ts(e.sec.toFloat())
            is Live.Ev.Done -> when {
                // 录音收尾完了:翻到这场的稿子
                // (十分钟没人说话当误触、整场删了的,回首页)
                e.fromRecord -> { stopBtn.isEnabled = true; if (e.dir.isDirectory) showSession(e.dir) else goHome() }
                // 后台排队的活做完了:正在录音就别打扰;在首页或正看着这场就刷新
                shown == e.dir || mode == Mode.IDLE && !e.stopped -> showSession(e.dir)
            }
            is Live.Ev.ModelsReady -> refreshModelState()
        }
    }

    private fun redrawLive() {
        setMode(Mode.REC)
        stopBtn.isEnabled = true
        setStatus(Live.status)
        timer.text = Engine.ts(Live.elapsed.toFloat())
        followTail = true                         // 从别处切回来:直接停在最新一句
        redrawCaptions()
    }

    private fun scrollToEnd() = scroll.post {
        scroll.fullScroll(View.FOCUS_DOWN)
        toLatest.visibility = View.GONE
    }

    /** 一句字幕:灰色时间 + 原文;有译文就在下面跟一行灰色小字。 */
    private fun captionSpan(l: Line): CharSequence {
        val sb = SpannableStringBuilder()
        val t = Engine.ts(l.t0)
        sb.append(t).append("  ")
        sb.setSpan(ForegroundColorSpan(c(R.color.muted)), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(l.text).append('\n')
        if (l.tr.isNotEmpty()) {
            val s = sb.length
            sb.append(l.tr).append('\n')
            sb.setSpan(ForegroundColorSpan(c(R.color.muted)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.88f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append('\n')
        return sb
    }

    private fun appendCaption(l: Line) {
        if (Live.lines.size <= 1) return redrawCaptions()   // 第一句:把占位提示换掉
        body.append(captionSpan(l))
        if (followTail) scrollToEnd()             // 正在往上翻旧字幕时不抢,「↓ 最新」还在
    }

    private fun redrawCaptions() {
        val sb = SpannableStringBuilder()
        Live.lines.forEach { sb.append(captionSpan(it)) }
        if (sb.isEmpty()) {
            val hint = when {
                Engine.asr != null -> t("说话吧,字幕会一句一句出现在这里。", "Start talking — captions will appear here line by line.")
                Engine.modelsReady() -> t("已经在录了。模型还在载入,十来秒后字幕出来,这几秒说的也会补上。", "Already recording. Model is still loading; captions appear in ~10s, including what's said in the meantime.")
                else -> t("正在录音。手机上还没有模型,这次只存录音;下载好模型后可以「重新整理」转写。", "Recording. No model on the phone yet, so this just saves audio; \"Re-process\" once a model is downloaded.")
            }
            sb.append(hint)
            sb.setSpan(ForegroundColorSpan(c(R.color.muted)), 0, hint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        body.text = sb
        if (followTail) scrollToEnd()
    }

    // ================= 稿子 =================
    /** 看 / 复制 / 分享的稿子:精修过就用精修稿。 */
    /** 看稿时在看原稿(精修过的场次可以切回逐句原稿,Suj 2026-10-29:「精修完原稿就没了吗?」)。换一场就复位。 */
    private var rawView = false

    private fun draftOf(dir: File): String {
        if (!(rawView && dir == shown)) Store.polishedOf(dir)?.let { return plainOf(it) }
        val lines = Store.loadLines(dir)
        if (lines.any { it.tr.isNotEmpty() })      // 录时开了实时翻译:原文下面跟译文
            return lines.joinToString("\n") { "${Engine.ts(it.t0)}  ${it.text}" + if (it.tr.isNotEmpty()) "\n        ${it.tr}" else "" }
        val d = File(dir, "draft.txt")
        if (d.isFile) return d.readText()
        // 没整理过(比如录时没模型):退回逐句字幕
        return lines.joinToString("\n") { "${Engine.ts(it.t0)}  ${it.text}" }
    }

    /** 复制 / 分享用的纯文本:去掉 Markdown 记号(贴进微信不会满屏星号)。 */
    private fun plainOf(md: String) = md.lines().joinToString("\n") { ln ->
        ln.replace("**", "").replace(Regex("""^#{1,6}\s*"""), "").replace(Regex("""^>\s*"""), "")
            .replace(Regex("""^\*(.+)\*$"""), "$1").let { if (it.trim() == "---") "" else it }
    }

    /** 精修稿(简单 Markdown)→ 带样式的正文:话题标题、说话人加粗上色、概要斜体、待核对清单。 */
    private fun renderMd(md: String): CharSequence {
        val sb = SpannableStringBuilder()
        val accent = c(if (isNight()) R.color.clay else R.color.forest)
        for (raw in md.lines()) {
            val ln = raw.trimEnd()
            val s = sb.length
            when {
                ln.startsWith("#") -> {
                    sb.append(ln.replace(Regex("""^#{1,6}\s*"""), "")).append('\n')
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.08f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(accent), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    continue
                }
                ln.startsWith(">") -> {
                    sb.append(ln.removePrefix(">").trim()).append('\n')
                    sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(c(R.color.muted)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    continue
                }
                ln.trim() == "---" -> { sb.append('\n'); continue }
                ln.matches(Regex("""^\*[^*].*\*$""")) -> {      // 整行斜体:结尾的 AI 说明
                    sb.append(ln.trim('*')).append('\n')
                    sb.setSpan(ForegroundColorSpan(c(R.color.muted)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.8f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    continue
                }
            }
            val text = if (ln.startsWith("- ")) "• " + ln.substring(2) else ln
            // 行内 **粗体**(说话人「**甲:**」上色)
            var i = 0
            val m = Regex("""\*\*(.+?)\*\*""")
            for (hit in m.findAll(text)) {
                sb.append(text, i, hit.range.first)
                val b = sb.length
                sb.append(hit.groupValues[1])
                sb.setSpan(StyleSpan(Typeface.BOLD), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (hit.range.first == 0) sb.setSpan(ForegroundColorSpan(accent), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = hit.range.last + 1
            }
            sb.append(text, i, text.length).append('\n')
        }
        return sb
    }

    private fun sessionLabel(d: File): String {
        val n = d.name   // yyyyMMdd_HHmmss
        return "${n.substring(4, 6)}-${n.substring(6, 8)} ${n.substring(9, 11)}:${n.substring(11, 13)}"
    }

    /** 列表和看稿时显示的时间:导入的文件名里认得出录音日期就用它(比导入那天准),认不出就用场次时间。 */
    private fun whenOf(d: File): String = Store.recordedOf(d)?.let { (date, time) ->
        date.substring(5) + (time?.let { " $it" } ?: "")
    } ?: sessionLabel(d)

    private fun sessionTag(d: File) = when {
        File(d, "polished.txt").isFile -> t("已精修", "Polished")
        File(d, "polish_state.json").isFile -> t("精修到一半", "Polishing partway")
        File(d, "draft.txt").isFile -> t("手机初稿", "Phone draft")
        else -> t("未整理", "Not processed")
    } + if (!PUB && Uploader.backedUp(d)) t(" · 已备份", " · backed up") else ""

    private fun showSession(dir: File) {
        if (dir != shown) rawView = false
        shown = dir
        setMode(Mode.SESSION)
        sendLabel.text = when {
            Store.polishedOf(dir) != null -> t("重新精修", "Re-polish")
            else -> t("精修", "Polish")
        }
        val sb = SpannableStringBuilder()
        val title = Store.titleOf(dir) ?: t("录音 ${sessionLabel(dir)}", "Recording ${sessionLabel(dir)}")
        sb.append(title).append('\n')
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.2f), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val pol = Store.polishedOf(dir)
        val meta = "${whenOf(dir)} · ${sessionTag(dir)}" + when {
            pol == null -> ""
            rawView -> t("\n正在看原稿 · 右上角「更多」里切回精修稿", "\nShowing the original · switch back to polished under \"More\"")
            else -> t("\n原稿还在 · 右上角「更多」→「看原稿」", "\nOriginal is kept · \"More\" → \"Show original\"")
        } + "\n\n"
        val s = sb.length
        sb.append(meta)
        sb.setSpan(ForegroundColorSpan(c(R.color.muted)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val lines = Store.loadLines(dir)
        when {
            pol != null && !rawView -> sb.append(renderMd(pol))
            lines.any { it.tr.isNotEmpty() } -> lines.forEach { sb.append(captionSpan(it)) }   // 实时翻译留在历史里
            else -> sb.append(draftOf(dir).ifEmpty {
                val at = Post.partAt(dir)
                if (at >= 0 && !WorkService.transcribing(dir)) t("上次转到 ${Engine.ts(at.toFloat())} 停了。点下面「重新整理」从这里接着转。",
                    "Transcription stopped at ${Engine.ts(at.toFloat())} last time. Tap \"Re-process\" below to pick up from there.")
                else if (Engine.asr != null) t("这场还没有文字。点下面「重新整理」在手机上转。", "No text yet for this session. Tap \"Re-process\" below to transcribe on the phone.")
                else t("这场只有录音。等模型就绪后点「重新整理」。", "This session only has audio. Tap \"Re-process\" once a model is ready.")
            })
        }
        // 会上问过 AI 的:单独一栏放在最后(不混进稿子,复制、分享也不带)
        val qas = Ask.load(dir)
        if (qas.isNotEmpty()) {
            val h = sb.length
            sb.append(t("\n\n会上问 AI(${qas.size})\n", "\n\nAsked AI during the session (${qas.size})\n"))
            sb.setSpan(StyleSpan(Typeface.BOLD), h, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(c(R.color.accent)), h, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for (qa in qas) appendQa(sb, qa.q, qa.at, qa.a, emptyList(), qa.searched)
        }
        body.text = sb
        scroll.post { scroll.scrollTo(0, 0) }
        if (askPanel.visibility == View.VISIBLE) { if (askDir == dir) drawAsk() else closeAsk() }
    }

    private fun currentDraft(): String? {
        val dir = shown ?: Store.sessions().firstOrNull() ?: return null
        return draftOf(dir).ifEmpty { null }
    }

    private fun copyDraft() {
        val draft = currentDraft() ?: return toast(t("还没有稿子", "No transcript yet"))
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(t("稿子", "Transcript"), draft))
        toast(t("稿子已复制", "Transcript copied"))
    }

    /** 长稿直接发文字,很多 App 有字数上限会截断(Suj 2026-10-29),所以默认发成 .txt 文件。 */
    private fun shareDraft() {
        val dir = shown ?: Store.sessions().firstOrNull() ?: return toast(t("还没有稿子", "No transcript yet"))
        val draft = draftOf(dir).ifEmpty { null } ?: return toast(t("还没有稿子", "No transcript yet"))
        choose(t("分享稿子", "Share transcript"), listOf(
            Choice(t("以 txt 文件发", "As a .txt file"), t("整篇一个文件,对方 App 有字数限制也不会截断", "The whole transcript as one file, so apps with a length limit won't cut it off"), primary = true) {
                val name = (Store.titleOf(dir) ?: t("稿子_${dir.name}", "Transcript_${dir.name}")).replace(Regex("""[\\/:*?"<>|]"""), "_")
                // 带 BOM:老版本记事本 / WPS 没 BOM 会把中文当 GBK 读成乱码
                val out = File(cacheDir, "export").apply { deleteRecursively(); mkdirs() }.let { File(it, "$name.txt") }
                out.writeText("\uFEFF" + draft)
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", out)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("分享稿子", "Share transcript")))
            },
            Choice(t("直接发文字", "As text"), t("短稿方便;太长的话有的 App 会截断", "Handy for short ones; some apps cut off long text")) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, draft), t("分享稿子", "Share transcript")))
            },
        ))
    }

    /** 历史:一场一张卡片,点开看稿,长按删这一场;一次删好几场走「删除几场…」。 */
    private fun showHistory() {
        val all = Store.sessions()
        if (all.isEmpty()) return toast(t("还没有录过", "No recordings yet"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(4)) }
        val dlg = AlertDialog.Builder(this).setTitle(t("历史录音", "Recording history"))
            .setView(ScrollView(this).apply { addView(box) })
            .setNeutralButton(t("删除几场…", "Delete several…")) { _, _ -> pickToDelete() }
            .setNegativeButton(t("关闭", "Close"), null).create()
        box.addView(TextView(this).apply {
            text = t("点开看稿 · 长按一场可以删掉", "Tap to open · long-press to delete")
            textSize = 12.5f
            setTextColor(c(R.color.muted))
            setPadding(dp(2), 0, 0, dp(10))
        })
        val busyDirs = WorkService.busyDirs() + listOfNotNull(RecordService.dir)
        for (d in all) {
            val title = Store.titleOf(d) ?: t("录音 ${sessionLabel(d)}", "Recording ${sessionLabel(d)}")
            val meta = (if (Store.titleOf(d) != null) "${whenOf(d)} · " else "") + "${sessionTag(d)} · ${mb(Store.sizeOf(d))}"
            box.addView(card(title, meta, onLong = {
                if (d in busyDirs) toast(t("这场正在录 / 排着队转写或上传,等做完再删", "This session is recording / queued to transcribe or upload — delete once it's done"))
                else confirmDelete(listOf(d)) { dlg.dismiss(); if (Store.sessions().isNotEmpty()) showHistory() }
            }) { dlg.dismiss(); showSession(d) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        dlg.show()
    }

    private fun mb(bytes: Long) = if (bytes < 1_000_000) "<1 MB" else "${bytes / 1_000_000} MB"

    /** 勾选要删的场次。正在录的、排着队要转/要传的不列出来。 */
    private fun pickToDelete() {
        val busyDirs = WorkService.busyDirs() + listOfNotNull(RecordService.dir)
        val all = Store.sessions().filter { it !in busyDirs }
        if (all.isEmpty()) return toast(t("没有能删的(正在录或排着队的不能删)", "Nothing to delete (recording or queued sessions can't be deleted)"))
        val names = all.map { d ->
            val title = Store.titleOf(d)?.let { " $it" } ?: ""
            "${whenOf(d)}$title · ${sessionTag(d)} · ${mb(Store.sizeOf(d))}"
        }
        val checked = BooleanArray(all.size)
        AlertDialog.Builder(this).setTitle(t("勾选要删的", "Check the ones to delete"))
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(t("删除", "Delete")) { _, _ ->
                val pick = all.filterIndexed { i, _ -> checked[i] }
                if (pick.isEmpty()) toast(t("没勾", "Nothing checked")) else confirmDelete(pick)
            }
            .setNegativeButton(t("取消", "Cancel"), null).show()
    }

    private fun confirmDelete(dirs: List<File>, after: (() -> Unit)? = null) {
        val busyDirs = WorkService.busyDirs() + listOfNotNull(RecordService.dir)
        if (dirs.any { it in busyDirs }) return toast(t("这场正在录 / 排着队转写或上传,等做完再删", "This session is recording / queued to transcribe or upload — delete once it's done"))
        val total = dirs.sumOf { Store.sizeOf(it) }
        val onPc = if (PUB) 0 else dirs.count { Uploader.backedUp(it) }
        val msg = t("手机上的录音和稿子都会删掉。共 ${mb(total)}。", "This deletes the audio and transcript on the phone. ${mb(total)} total.") + when {
            onPc == dirs.size -> t("\n电脑上有备份,那边不受影响。", "\nBacked up on PC — unaffected there.")
            onPc > 0 -> t("\n其中 $onPc 场电脑上有备份,那边不受影响;其余的找不回来。", "\n$onPc of these are backed up on PC — unaffected there; the rest can't be recovered.")
            else -> t("\n找不回来。", "\nCan't be recovered.")
        }
        AlertDialog.Builder(this).setTitle(t("删掉 ${dirs.size} 场?", "Delete ${dirs.size} session(s)?")).setMessage(msg)
            .setPositiveButton(t("删除", "Delete")) { _, _ ->
                dirs.forEach { Store.deleteSession(it) }
                if (shown in dirs) goHome()
                toast(t("已删 ${dirs.size} 场,腾出 ${mb(total)}", "Deleted ${dirs.size}, freed ${mb(total)}"))
                after?.invoke()
            }
            .setNegativeButton(t("取消", "Cancel"), null).show()
    }

    /** 在桌面钉一个「一键录音」图标。vivo 默认可能不给建桌面快捷方式,另外还有小组件可用。 */
    private fun pinRecordShortcut() {
        val sm = getSystemService(ShortcutManager::class.java)
        val tip = t("点它就默默开录,不打开界面;下拉通知栏能停,点通知能看字幕。" +
            "${RecordService.IDLE_STOP_SEC / 60} 分钟没听到说话会当误触自动停。\n\n" +
            "要是桌面上没出现:\n· 到手机设置里找到转录机的权限,打开「桌面快捷方式」再试一次;\n" +
            "· 或者长按桌面空白处 → 添加小组件 → 转录机「一键录音」;\n· 也可以长按转录机图标,把「一键录音」拖到桌面。",
            "Tap it to start recording silently, without opening the app; pull down notifications to stop, tap the notification to see captions. " +
            "Stops automatically after ${RecordService.IDLE_STOP_SEC / 60} minutes of silence (likely an accidental tap).\n\n" +
            "If it doesn't show up on the home screen:\n· Go to Transcriber's permissions in phone settings, enable \"home screen shortcuts\" and try again;\n" +
            "· Or long-press empty space on the home screen → Add widget → Transcriber's \"Quick record\";\n· Or long-press the Transcriber icon and drag \"Quick record\" to the home screen.")
        if (sm?.isRequestPinShortcutSupported == true) sm.requestPinShortcut(recordShortcut("record_pin"), null)
        AlertDialog.Builder(this).setTitle(t("一键录音", "Quick record")).setMessage(tip).setPositiveButton(t("好", "OK"), null).show()
    }

    private fun recordShortcut(id: String) = ShortcutInfo.Builder(this, id)
        .setShortLabel(t("一键录音", "Quick record"))
        .setLongLabel(t("开始录音(带实时字幕)", "Start recording (live captions)"))
        .setIcon(Icon.createWithResource(this, R.mipmap.ic_shortcut_rec))
        .setIntent(QuickRecord.intent(this))
        .build()

    /** 手动改名。改过的名字精修不会再换掉;清空 = 回到默认的「录音 日期」。 */
    private fun renameSession(dir: File) {
        val ed = EditText(this).apply {
            setText(Store.titleOf(dir) ?: "")
            hint = t("录音 ${sessionLabel(dir)}", "Recording ${sessionLabel(dir)}")
            textSize = 15f
            setSingleLine()
            selectAll()
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(ed) }
        AlertDialog.Builder(this).setTitle(t("改名", "Rename")).setView(box)
            .setPositiveButton(t("保存", "Save")) { _, _ ->
                Store.setTitle(dir, ed.text.toString(), "user")
                showSession(dir)
            }
            .setNegativeButton(t("取消", "Cancel"), null).show()
    }

    private fun suggestTitle(dir: File) {
        if (!Llm.ready()) { toast(t("起标题要用 AI,先填 key", "Needs AI, add a key first")); return editAi() }
        setStatus(t("正在起标题…", "Suggesting a title…"))
        thread {
            try {
                Polish.titleFromPolished(dir)
                runOnUiThread { setStatus(t("标题起好了,不满意可以「改名」", "Title set — use \"Rename\" if you'd like something else")); if (shown == dir) showSession(dir) }
            } catch (e: Throwable) {
                runOnUiThread { setStatus(t("起标题失败:${e.message}", "Couldn't suggest a title: ${e.message}")) }
            }
        }
    }

    private fun reprocess() {
        val dir = shown ?: return toast(t("先在「历史」里打开一场", "Open a session from \"History\" first"))
        if (Engine.asr == null) return toast(t("模型还没就绪", "Model isn't ready yet"))
        val lines = Store.loadLines(dir)
        if (lines.isEmpty()) {                   // 录时没模型 / 导入后选了传录音:整段在手机上转(排队)
            if (WorkService.transcribing(dir)) return toast(t("这场已经在转了,进度看顶上", "Already transcribing this one, check progress at the top"))
            WorkService.local(this, dir)
            askBackground()
            toast(if (File(dir, Post.PART).isFile) t("从上次停下的地方接着转", "Picking up where it stopped") else t("已排队在手机上转", "Queued to transcribe on the phone"))
            return
        }
        if (busy) return toast(t("正在整理,稍等", "Already processing, hang on"))
        busy = true
        thread {
            try {
                Post.run(dir, lines) { m -> runOnUiThread { setStatus(m) } }
                runOnUiThread { showSession(dir) }
            } catch (e: Throwable) {
                runOnUiThread { setStatus(t("整理失败:${e.message}", "Processing failed: ${e.message}")) }
            } finally { busy = false }
        }
    }

    // ================= 备份到电脑(自用版)=================
    /**
     * 2026-10-28 起电脑只管备份和远程查看(精修在手机上做,1.7B 精转那条路撤了)。
     * 录音压成 Opus 连同稿子分块续传,在 WorkService 里排队:锁屏、切 App 不断,也不挡录音。
     * 传过、没变的文件跳过,所以「全部备份」点多少次都只传新的。
     */
    private fun backupToPc(dirs: List<File>) {
        val busyDirs = listOfNotNull(RecordService.dir)
        val todo = dirs.filter { it !in busyDirs && !Uploader.backedUp(it) }
        if (todo.isEmpty()) return toast(if (dirs.size == 1) t("电脑上已经是最新的了", "Already up to date on PC") else t("都备份过了", "All already backed up"))
        todo.forEach { WorkService.backup(this, it) }
        toast(if (todo.size == 1) t("开始备份,进度看顶上", "Backing up, check progress at the top") else t("排了 ${todo.size} 场备份,锁屏也会接着传", "Queued ${todo.size} to back up, keeps going with screen locked"))
    }

    // ================= 导出录音 =================
    /** 把这场的录音分享出去(存到文件、发微信、传电脑)。压缩版是 Opus(.ogg,1 小时约 11MB);原始是 16k WAV(1 小时约 115MB)。 */
    private fun exportAudio(dir: File) {
        val wav = File(dir, "audio.wav")
        if (!wav.isFile) return toast(t("这场没有录音文件", "No audio file for this session"))
        val name = (Store.titleOf(dir) ?: t("录音_${dir.name}", "Recording_${dir.name}")).replace(Regex("""[\\/:*?"<>|]"""), "_")
        /** 后台线程调:复制成带名字的副本(WAV 上百 MB,别在界面线程拷),再回界面线程弹分享。 */
        fun share(f: File, ext: String, mime: String) {
            val out = File(cacheDir, "export").apply { deleteRecursively(); mkdirs() }.let { File(it, "$name.$ext") }
            f.copyTo(out, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", out)
            runOnUiThread {
                setStatus("")
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("导出录音", "Export audio")))
            }
        }
        val mins = maxOf(1, ((wav.length() - 44) / 2 / Engine.SR / 60).toInt())
        choose(t("下载录音", "Download audio"), listOf(
            Choice(t("压缩版(.ogg)", "Compressed (.ogg)"), t("约 ${maxOf(1, mins * 11 / 60)} MB,音质够听清说话;压缩要一会儿", "~${maxOf(1, mins * 11 / 60)} MB, clear enough for speech; compressing takes a moment"), primary = true) {
                setStatus(t("正在压缩录音…", "Compressing audio…"))
                thread {
                    try {
                        val (f, ext) = Uploader.compress(dir) { m -> runOnUiThread { setStatus(m) } }
                        share(f, ext, if (ext == "ogg") "audio/ogg" else "audio/wav")
                    } catch (e: Exception) { runOnUiThread { setStatus(t("压缩失败:${e.message}", "Compression failed: ${e.message}")) } }
                }
            },
            Choice(t("原始录音(.wav)", "Original (.wav)"), t("约 ${wav.length() / 1_000_000} MB,无损", "~${wav.length() / 1_000_000} MB, lossless")) {
                setStatus(t("正在准备录音文件…", "Preparing audio file…"))
                thread {
                    try { share(wav, "wav", "audio/wav") }
                    catch (e: Exception) { runOnUiThread { setStatus(t("导出失败:${e.message}", "Export failed: ${e.message}")) } }
                }
            },
        ))
    }

    // ================= 公开版:精修 =================
    private fun polish() {
        val dir = shown ?: return toast(t("先在「历史」里打开一场", "Open a session from \"History\" first"))
        if (Store.loadLines(dir).none { it.text.isNotBlank() })
            return toast(if (Engine.asr != null) t("这场还没有文字,先点「重新整理」", "No text yet for this session, tap \"Re-process\" first") else t("这场还没有文字,等模型就绪后先「重新整理」", "No text yet for this session, \"Re-process\" once the model is ready"))
        if (!Llm.ready()) { toast(t("精修要用 AI,先填 key", "Polishing needs AI, add a key first")); return editAi() }
        if (WorkService.queued(WorkService.Task.Polish(dir))) return toast(t("这场已经在精修了,进度看顶上", "Already polishing this session, check progress at the top"))
        val again = Store.polishedOf(dir) != null
        val half = File(dir, "polish_state.json").isFile
        val n = Store.loadLines(dir).size
        // 「这场有谁」:一句话就够,分说话人准很多(大模型一次直出的稿子分人分得好,一半是因为它知道谁是谁)
        val who = EditText(this).apply {
            setText(Polish.peopleOf(dir))
            hint = t("这场有谁?例:我、主持人、林博士(嘉宾)", "Who's in this session? e.g. Me, the host, Dr. Lin (guest)")
            textSize = 15f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(context).apply {
                text = (if (half) t("上次精修到一半,会从断处接着做。\n\n", "Last polish stopped partway; it'll continue from there.\n\n") else "") +
                    t("把 $n 句文字(不是录音)发给 AI,整理成逐句的对话稿,约一两分钟。锁屏、切走都会接着做。",
                      "Sends $n lines of text (not audio) to the AI, organized into a line-by-line dialogue, about a minute or two. Keeps going if you lock the screen or switch away.")
                textSize = 14f
                setTextColor(c(R.color.ink))
                setPadding(0, dp(4), 0, dp(12))
            })
            addView(who)
            if (Store.me.isBlank()) addView(TextView(context).apply {
                text = t("也可以在菜单里填「我是谁」,分说话人更准。", "You can also set \"Who am I\" in the menu, for better speaker splits.")
                textSize = 12f
                setTextColor(c(R.color.muted))
                setPadding(0, dp(6), 0, 0)
            })
        }
        AlertDialog.Builder(this).setTitle(if (again && !half) t("重新精修", "Re-polish") else t("精修这场", "Polish this session")).setView(box)
            .setPositiveButton(if (half) t("接着做", "Continue") else t("开始", "Start")) { _, _ ->
                Polish.savePeople(dir, who.text.toString())
                // 重新精修:旧稿改名留一份(新稿不如意、或精修中途失败,旧的还在),不直接删
                if (again && !half) File(dir, "polished.txt").let { File(dir, "polished.prev.txt").delete(); it.renameTo(File(dir, "polished.prev.txt")) }
                WorkService.polish(this, dir); askBackground(); toast(t("开始精修,进度看顶上", "Polishing, check progress at the top"))
            }
            .setNegativeButton(t("取消", "Cancel"), null).show()
    }

    // ================= 公开版:设置 =================
    private fun showSettings() {
        choose(t("设置", "Settings"), listOf(
            Choice(t("AI 设置", "AI settings"), if (Llm.ready()) t("翻译、精修、问 AI · 已填 key ✓", "Translation, polish, Ask AI · key set ✓") else t("翻译、精修、问 AI · 还没填 key", "Translation, polish, Ask AI · no key yet"),
                primary = !Llm.ready()) { editAi() },
            Choice(t("我是谁", "Who am I"), Store.me.take(30).ifBlank { t("告诉 AI 录音的人是谁,分说话人更准", "Tell the AI who's recording, for better speaker splits") }) { editMe() },
            Choice(if (Engine.modelsReady()) t("识别模型 ✓", "Recognition model ✓") else t("下载识别模型", "Download recognition model"),
                if (Engine.modelsReady()) t("Qwen3-ASR 0.6B,已在手机上", "Qwen3-ASR 0.6B, already on the phone") else t("约 880 MB,建议连 Wi-Fi", "~880 MB, Wi-Fi recommended")) { downloadModels() },
            Choice(t("在桌面放「一键录音」", "Add \"Quick record\" to home screen"), t("点一下就默默开录,通知栏上停", "Tap to start recording silently, stop from the notification")) { pinRecordShortcut() },
            Choice(saverItemTitle(), saverItemDesc()) { toggleSaver() },
            Choice(bgItemTitle(), bgItemDesc()) { askBackground(force = true) },
            Choice(t("语言 · 中文", "Language · English"), t("切换成英文界面和英文 AI", "Switch to Chinese UI & AI")) { pickLanguage() },
            Choice(t("关于与隐私", "About & privacy"), "") { showAbout() },
        ))
    }

    /** 用户自己的 key。任何 OpenAI 兼容的服务都行,默认 DeepSeek。 */
    private fun editAi() {
        fun field(label: String, v: String, hint: String, secret: Boolean = false) = EditText(this).apply {
            setText(v); this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or
                if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
            tag = label
        }
        val profiles = linkedMapOf(Store.DEEPSEEK to Store.aiProfile(Store.DEEPSEEK), Store.OPENAI to Store.aiProfile(Store.OPENAI))
        var selected = Store.aiProvider
        val initial = profiles.getValue(selected)
        val key = field("key", initial.auth, "API key", secret = true)
        val base = field("base", initial.base, "https://…")
        val fast = field("fast", initial.model, "model")
        fun remember() {
            profiles[selected] = Store.AiProfile(selected, key.text.toString(), base.text.toString(), fast.text.toString())
        }
        val providers = android.widget.RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val choices = listOf(Store.DEEPSEEK to "DeepSeek", Store.OPENAI to "GPT · Luna").associate { (provider, name) ->
            val button = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = name }
            providers.addView(button)
            if (provider == selected) providers.check(button.id)
            button.id to provider
        }
        providers.setOnCheckedChangeListener { _, id ->
            val provider = choices[id] ?: return@setOnCheckedChangeListener
            remember()
            selected = provider
            val profile = profiles.getValue(provider)
            key.setText(profile.auth); base.setText(profile.base); fast.setText(profile.model)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            fun label(txt: String) = addView(TextView(context).apply {
                text = txt; textSize = 12f; setTextColor(c(R.color.muted)); setPadding(0, dp(10), 0, 0)
            })
            label(t("AI 服务(分别保存设置)", "AI service (settings saved separately)")); addView(providers)
            label(t("当前服务的 API key", "API key for this service")); addView(key)
            label(t("服务地址(OpenAI 兼容接口)", "Service URL (OpenAI-compatible)")); addView(base)
            label(t("模型:翻译、精修、问 AI", "Model: translation, polish, Ask AI")); addView(fast)
        }
        fun save() {
            remember()
            Store.saveAi(profiles.values, selected)
            if (PUB && !Llm.ready()) { Store.trMode = Store.TR_OFF; refreshTr() }
        }
        val dlg = AlertDialog.Builder(this).setTitle(t("AI 设置", "AI settings"))
            .setMessage(t("选择 DeepSeek 或 GPT,填写对应服务的 key。GPT 默认用 GPT-6 Luna。" +
                "翻译、精修和问 AI 会把相关文字发给所选服务;问 AI 可能联网搜索并产生额外费用。录音不上传。",
                "Choose DeepSeek or GPT and enter that service's key. GPT defaults to GPT-6 Luna. " +
                "Translation, polish and Ask AI send relevant text to the selected service; Ask AI may search the web at an extra cost. Audio is not uploaded."))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(t("保存", "Save")) { _, _ -> save(); toast(t("已保存", "Saved")) }
            .setNeutralButton(t("测试", "Test"), null)
            .setNegativeButton(t("取消", "Cancel"), null).show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {      // 测试时不关对话框
            save()
            val profile = Store.aiProfile()
            toast(t("正在测试…", "Testing…"))
            thread {
                val msg = try {
                    val r = Llm.call(t("只回复两个字:好的", "Reply with just: OK"), t("测试", "test"), profile.model, maxTokens = 32, retries = 0, timeoutSec = 30, profile = profile)
                    t("连上了 ✓(${profile.model} 回:${r.trim().take(10)})", "Connected ✓ (${profile.model} replied: ${r.trim().take(10)})")
                } catch (e: Exception) { t("没连上:${e.message}", "Couldn't connect: ${e.message}") }
                runOnUiThread { toast(msg) }
            }
        }
    }

    private fun showAbout() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        AlertDialog.Builder(this).setTitle(t("转录机 $v", "Transcriber $v"))
            .setMessage(t("""
                录音、转写、词库都只存在这台手机上,不上传。要留底可以在一场稿子右上角下载录音。

                实时字幕在手机上离线识别:Qwen3-ASR 0.6B(阿里开源)经 sherpa-onnx 运行,断网也能用。

                只有你用「实时翻译」「精修」「问 AI」时,才会把文字稿(不是录音)发给你在「AI 设置」里填的服务,用的是你自己的 key。

                开源组件:sherpa-onnx(Apache-2.0)、Qwen3-ASR(Apache-2.0)、Silero VAD(MIT)、Apache Commons Compress(Apache-2.0)。
            """.trimIndent(), """
                Recordings, transcripts, and your vocabulary stay on this phone only — nothing is uploaded. To keep a copy, download the audio from a session's top-right menu.

                Live captions are recognized offline on the phone: Qwen3-ASR 0.6B (open-sourced by Alibaba) running via sherpa-onnx, works without network.

                Text (never audio) is only sent out when you use "Live translation", "Polish", or "Ask AI" — to the service you set in "AI settings", using your own key.

                Open-source components: sherpa-onnx (Apache-2.0), Qwen3-ASR (Apache-2.0), Silero VAD (MIT), Apache Commons Compress (Apache-2.0).
            """.trimIndent()))
            .setPositiveButton(t("好", "OK"), null).show()
    }

    /** 电脑上的稿库(备份来的录音和稿子);dir 给了就直接翻到那场。 */
    private fun openWeb(dir: File?) {
        val url = Store.server + "/" + (if (dir != null) "#" + dir.name else "")
        startActivity(Intent(this, WebActivity::class.java).putExtra("url", url))
    }

    // ================= 词库 / 设置 =================
    private fun editVocab() {
        val ed = EditText(this).apply {
            setText(Store.vocab().joinToString("\n"))
            hint = t("一行一个:人名、公司名、术语\n只放短词,不要放整句", "One per line: names, companies, terms\nShort words only, no full sentences")
            minLines = 8
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        // 已有词库的老用户:点一下把常用词(中英夹杂的品牌、缩写)补进来,保存前还能删
        val add = TextView(this).apply {
            textSize = 14f
            setTextColor(c(if (isNight()) R.color.clay else R.color.forest))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            fun refresh() {
                val have = ed.text.toString().lines().map { it.trim() }.toSet()
                val miss = Store.STARTER.filter { it !in have }
                text = if (miss.isEmpty()) t("常用词都在了", "All common words are in") else t("＋ 加入常用词(${miss.size} 个:DeepSeek、LP、尽调…)", "+ Add common words (${miss.size}: DeepSeek, LP, due diligence…)")
                isEnabled = miss.isNotEmpty()
            }
            refresh()
            setOnClickListener {
                val have = ed.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                ed.setText((have + Store.STARTER.filter { it !in have }).joinToString("\n"))
                refresh()
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            // 识别模型只带最下面 12 个自己加的词(Engine.asrWords),人得知道该把要紧的放哪儿
            val asrNote = t("离线字幕只认最下面 12 个你自己加的词(带多了会变慢),要紧的放最后;整份词库都给精修和翻译参考。",
                "Offline captions only use the last 12 words you added yourself (more slows it down) — put the important ones at the bottom. Polish and translation use the whole list.")
            // 公开版只放了 5 个样例词,不再塞整份常用词表
            addView(TextView(context).apply {
                text = if (PUB) t("一开始的几个词是样例,换成你常听到、又容易被写错的名字和术语。", "The starter words are just examples — replace them with names and terms you often hear that tend to get misheard.") + "\n" + asrNote
                    else asrNote
                textSize = 13f
                setTextColor(c(R.color.muted))
                setPadding(dp(4), dp(6), dp(4), dp(6))
            })
            if (!PUB) addView(add)
            addView(ed)
        }
        val b = AlertDialog.Builder(this).setTitle(t("个人词库", "Personal vocabulary")).setView(box)
            .setPositiveButton(t("保存", "Save")) { _, _ ->
                Store.saveVocab(ed.text.toString().lines())
                reloadForVocab()
            }
            .setNegativeButton(t("取消", "Cancel"), null)
        if (!PUB) b.setNeutralButton(t("和电脑同步", "Sync with PC")) { _, _ ->
            Store.saveVocab(ed.text.toString().lines())
            syncVocab()
        }
        b.show()
    }

    private fun reloadForVocab() {
        if (Engine.modelsReady() && !RecordService.running && !busy) loadModel(reload = true)
        else toast(t("已保存,下次载入模型时生效", "Saved, takes effect next time the model loads"))
    }

    /** 和电脑上的全局词库合并:那边还会从转录机里的「登记术语」「批量订正」自己长词。 */
    private fun syncVocab() {
        setStatus(t("正在和电脑同步词库…", "Syncing vocabulary with PC…"))
        thread {
            try {
                val before = Store.vocab().size
                val merged = Uploader.syncVocab(Store.vocab(), Store.vocabSynced)
                Store.saveVocab(merged)
                Store.vocabSynced = merged
                runOnUiThread {
                    setStatus(t("词库已同步 · ${merged.size} 个词(电脑那边多来 ${maxOf(0, merged.size - before)} 个)", "Vocabulary synced · ${merged.size} words (${maxOf(0, merged.size - before)} new from PC)"))
                    reloadForVocab()
                }
            } catch (e: Throwable) {
                runOnUiThread { setStatus(t("同步失败:${e.message}\n手机要开着 Tailscale", "Sync failed: ${e.message}\nPhone needs Tailscale connected")) }
            }
        }
    }

    /** 「我是谁」:电脑按语义分说话人时最可靠的锚点(谁在介绍自家基金、谁在提问,一下就清楚)。 */
    private fun editMe(then: (() -> Unit)? = null) {
        val ed = EditText(this).apply {
            setText(Store.me)
            hint = t("例:记者,主要在提问;或:来拜访客户的销售", "e.g. Journalist, mostly asking questions; or: Sales rep visiting a client")
            minLines = 2
        }
        AlertDialog.Builder(this).setTitle(t("我是谁", "Who am I")).setView(ed)
            .setMessage(t("精修时 AI 按语义分说话人。" +
                "告诉它录音的人是谁,分得准很多。可以留空。",
                "AI splits speakers by meaning when polishing. Telling it who's recording makes that much more accurate. You can leave this blank."))
            .setPositiveButton(t("保存", "Save")) { _, _ -> Store.me = ed.text.toString(); then?.invoke() }
            .setNegativeButton(if (then != null) t("跳过", "Skip") else t("取消", "Cancel")) { _, _ -> then?.invoke() }
            .show()
    }

    private fun editServer() {
        val ed = EditText(this).apply { setText(Store.server); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        AlertDialog.Builder(this).setTitle(t("电脑上的转录机地址", "Transcriber address on PC")).setView(ed)
            .setMessage(t("手机要开着 Tailscale。默认 ${Store.DEFAULT_SERVER}", "Phone needs Tailscale connected. Default ${Store.DEFAULT_SERVER}"))
            .setPositiveButton(t("保存", "Save")) { _, _ -> Store.server = ed.text.toString().trim() }
            .setNegativeButton(t("取消", "Cancel"), null).show()
    }

    /** 语言切换:界面和 AI 一起换。选完 recreate(),重新发一遍快捷方式(标签也要跟着换)。 */
    private fun republishShortcuts() {
        try {
            getSystemService(ShortcutManager::class.java)?.apply {
                dynamicShortcuts = listOf(recordShortcut("record"))
                if (pinnedShortcuts.any { it.id == "record_pin" }) updateShortcuts(listOf(recordShortcut("record_pin")))
            }
        } catch (_: Exception) {}
    }

    private fun pickLanguage() {
        choose("语言 / Language", listOf(
            Choice("中文", "界面和 AI 都用中文", primary = !Store.english) {
                Store.english = false; republishShortcuts(); recreate()
            },
            Choice("English", "UI and AI in English", primary = Store.english) {
                Store.english = true; republishShortcuts(); recreate()
            },
        ))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
