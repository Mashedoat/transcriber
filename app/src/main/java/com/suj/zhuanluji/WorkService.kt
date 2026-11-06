package com.suj.zhuanluji

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 不用麦克风的长活:导入外来音频、整段在手机上转写、精修、备份到电脑。
 * **排队**一件件做(以前一次只收一件,其余报"正在忙"),而且和录音互不挡——传着上一场,照样能录下一场。
 * 前台服务 + 唤醒锁 + Wi-Fi 锁:锁屏、切 App 都不断。
 */
class WorkService : Service() {
    sealed class Task {
        // file 是复制进来的副本;dir 不为空 = 上次导入到一半被杀,接着用那个场次
        data class Import(val file: File, val name: String, val dir: File? = null) : Task()
        data class Local(val dir: File) : Task()
        data class Backup(val dir: File) : Task()                 // 自用版:录音 + 稿子备份到电脑
        data class Polish(val dir: File) : Task()                 // 手机上调 AI 精修
        data class Models(val local: android.net.Uri?) : Task()   // 公开版:下载 / 导入模型包
    }

    companion object {
        private val queue = LinkedBlockingQueue<Task>()
        @Volatile var running = false
            private set
        @Volatile var current = ""                // 正在做什么(给界面看)
            private set

        fun pending() = queue.size

        @Volatile private var workingDir: File? = null
        private fun dirOf(t: Task) = when (t) {
            is Task.Import -> t.dir
            is Task.Models -> null
            is Task.Local -> t.dir
            is Task.Backup -> t.dir
            is Task.Polish -> t.dir
        }
        fun queued(t: Task) = t in queue || (workingTask == t)
        @Volatile private var workingTask: Task? = null
        /** 正在做或排着队的场次:删历史时不能动。 */
        fun busyDirs(): Set<File> = (queue.mapNotNull(::dirOf) + listOfNotNull(workingDir)).toSet()

        private fun enqueue(c: Context, t: Task) {
            queue.put(t)
            c.startForegroundService(Intent(c, WorkService::class.java))
        }

        fun import(c: Context, file: File, name: String) = enqueue(c, Task.Import(file, name))

        /** 打开 App 时:上次被杀时导入到一半的,重新排上(副本还在的话)。返回排了几件。 */
        fun resumeImports(c: Context): Int {
            if (running) return 0
            val redo = Store.recover(busyDirs() + listOfNotNull(RecordService.dir))
            for ((copy, dir) in redo) enqueue(c, Task.Import(copy, dir?.let { Store.titleOf(it) } ?: Store.importName(copy), dir))
            return redo.size
        }

        /**
         * 「停下」:清掉排着的,正在做的转写在下一句话处停(进度照样存着,之后点「重新整理」接着转)。
         * 精修、备份本来就是一步一存、断了接着做,所以也只是停在当前这步做完。
         */
        @Volatile var stopRequested = false
            private set
        fun stopAll(): Int {
            val n = queue.size
            queue.clear()
            if (workingTask != null) stopRequested = true
            return n
        }
        val working get() = workingTask != null
        /** 这场是不是正在转、或已经排着要转(导入那件做到转写这步也算)。 */
        fun transcribing(dir: File) = Task.Local(dir) in queue ||
            (workingDir == dir && (workingTask is Task.Import || workingTask is Task.Local))
        fun local(c: Context, dir: File) { if (!transcribing(dir)) enqueue(c, Task.Local(dir)) }
        fun backup(c: Context, dir: File) { if (!queued(Task.Backup(dir))) enqueue(c, Task.Backup(dir)) }
        fun polish(c: Context, dir: File) = enqueue(c, Task.Polish(dir))
        fun models(c: Context, local: android.net.Uri?) = enqueue(c, Task.Models(local))
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, note(t("准备中", "Getting ready")), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (running) {
            if (queue.isNotEmpty()) Live.post(Live.Ev.Status(t("已排队,前面还有 ${queue.size} 件", "Queued, ${queue.size} ahead of it")))
            return START_NOT_STICKY
        }
        running = true
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zlj:work").apply { acquire(6 * 3600_000L) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zlj:upload").apply { acquire() }
        thread(name = "work") {
            try {
                while (true) {
                    val task = queue.poll() ?: break
                    stopRequested = false
                    var dir: File? = null
                    var failedDir: File? = null
                    workingDir = dirOf(task)
                    workingTask = task
                    try {
                        dir = when (task) {
                            is Task.Import -> importAudio(task).also { if (!stopRequested) transcribeLocal(it) }
                            is Task.Local -> task.dir.also { transcribeLocal(it) }
                            is Task.Backup -> task.dir.also { Uploader.backup(it) { m -> update(m) } }
                            is Task.Polish -> task.dir.also { polish(it) }
                            is Task.Models -> { models(task.local); null }
                        }
                    } catch (e: Throwable) {
                        failedDir = workingDir
                        val msg = if (e is IllegalStateException) e.message ?: "" else "${e.javaClass.simpleName}: ${e.message}"
                        if (e is Post.Stopped) update(msg)
                        else update(t("出错了:$msg", "Error: $msg") + when {
                            (task is Task.Local || task is Task.Import) && workingDir?.let { File(it, Post.PART).isFile } == true ->
                                t("\n转好的部分已存好,点「重新整理」从断处接着转", "\nWhat's transcribed so far is saved; tap \"Re-process\" to continue from there")
                            task is Task.Backup -> t("\n手机要开着 Tailscale;再点一次会从断点接着传", "\nPhone needs Tailscale connected; tap again to resume where it stopped")
                            task is Task.Polish -> t("\n做完的部分已存好,再点「精修」从断处接着做", "\nWhat's done so far is saved; tap \"Polish\" again to continue from there")
                            task is Task.Models && task.local == null -> t("\n再点一次「下载模型」重试", "\nTap \"Download models\" to retry")
                            else -> ""
                        })
                    }
                    workingDir = null
                    workingTask = null
                    dir?.let { Live.post(Live.Ev.Done(it)) }
                    failedDir?.let { Live.post(Live.Ev.Done(it, stopped = true)) }   // 看着这场的话刷新(显示转到哪了)
                }
            } finally {
                running = false
                current = ""
                wake?.release()
                wifi?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Engine.scheduleRelease()             // 界面不在前台的话,闲 3 分钟放下模型
                // 收尾这一瞬间又有人排进来(那次 onStartCommand 看到 running=true 就回去了):重新拉起
                if (queue.isNotEmpty()) startForegroundService(Intent(this, WorkService::class.java))
            }
        }
        return START_NOT_STICKY
    }

    private fun note(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("work", t("转写与上传", "Transcribing & uploading"), NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "work").setContentTitle(t("转录机", "Transcriber")).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(pi).setOngoing(true).build()
    }

    private var lastNote = 0L
    private fun update(text: String) {
        val q = queue.size
        val msg = if (q > 0) t("$text(后面还有 $q 件)", "$text ($q more queued)") else text
        current = msg
        Live.post(Live.Ev.Status(msg))
        val now = System.currentTimeMillis()
        if (now - lastNote > 1000) {              // 通知别刷太快
            lastNote = now
            getSystemService(NotificationManager::class.java).notify(2, note(msg.lineSequence().first()))
        }
    }

    private fun importAudio(task: Task.Import): File {
        val src = task.file
        val name = task.name
        val dir = task.dir ?: run {
            // 同一秒导入两个会撞目录名:撞了就往后挪一秒
            var ms = System.currentTimeMillis()
            var d: File
            do {
                d = File(Store.root, "sessions/" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms)))
                ms += 1000
            } while (d.exists())
            d.mkdirs()
            Store.setTitle(d, name, "file")   // 扩展名在界面那边已经去掉了
            d
        }
        workingDir = dir
        // 记下副本在哪:解到一半被杀,下次打开 App 能重新排上(Store.recover)
        File(dir, Store.IMPORT_SRC).writeText(src.absolutePath)
        if (task.dir != null) update(t("上次「$name」没导入完,重新导入…", "\"$name\" didn't finish importing last time, importing again…"))
        try {
            Importer.decode(src, File(dir, "audio.wav")) { pct -> update(t("导入「$name」… $pct%", "Importing \"$name\"… $pct%")) }
        } catch (e: Throwable) {
            // 解不了的文件(格式坏了之类)重来也没用:副本删掉,不再自动重排
            src.delete(); File(dir, Store.IMPORT_SRC).delete()
            throw e
        }
        File(dir, Store.IMPORT_SRC).delete()
        src.delete()                              // 副本用完就删,原文件不动
        return dir
    }

    private fun transcribeLocal(dir: File) {
        // 模型可能因为闲着被放下了(Engine.release):有文件就重新读进来,不用联网
        if (Engine.asr == null && Engine.modelsReady()) {
            update(t("正在载入识别模型…", "Loading the speech model…"))
            Engine.ensureAsr(Store.vocab())
        }
        if (Engine.asr == null) {
            update(t("已导入。模型还没就绪:下载好模型后打开这场点「重新整理」", "Imported. Model isn't ready yet: download a model, then open this session and tap \"Re-process\""))
            return
        }
        val lines = Post.transcribeWhole(File(dir, "audio.wav"), { stopRequested }) { update(it) }
        Store.saveLines(dir, lines)
        File(dir, Post.PART).delete()            // lines.json 存好了才删断点
        Post.run(dir, lines) { update(it) }
    }

    /** 精修:通读认人、列错词表 → 按话题逐段整理成对话稿(见 Polish)。 */
    private fun polish(dir: File) = Polish.run(dir) { update(it) }   // 最后一句「精修完成 · n 位说话人 · n 处请回听」留在状态栏

    private fun models(local: android.net.Uri?) {
        if (local != null) PublicModels.importLocal(this, local) { update(it) }
        else PublicModels.download { update(it) }
        update(t("模型就位,正在载入…", "Models ready, loading…"))
        Live.post(Live.Ev.ModelsReady)
    }
}
