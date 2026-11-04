package com.suj.zhuanluji

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * 桌面「一键录音」(快捷方式、小组件)点下来是这个透明的跳板:不翻出界面,直接开录,弹一句提示就走。
 * 停止在通知栏上;点通知回到录音界面看字幕。
 * 用跳板而不是直接从桌面拉录音服务:Android 14 起麦克风前台服务得在「有可见界面」时启动,
 * 透明 Activity 算可见,又什么都不挡。
 * 没录音权限 / 已经在录:交给主界面(要弹权限框、或把正在录的翻出来)。
 */
class QuickRecord : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted || RecordService.running) {
            startActivity(MainActivity.recordIntent(this))
            finish()
            return
        }
        RecordService.start(this)
        Toast.makeText(this, t("开始录音 · 下拉通知栏可停止", "Recording started · pull down notifications to stop"), Toast.LENGTH_SHORT).show()
        // 稍等再关:录音服务进前台那一刻,这个界面还得算「可见」
        window.decorView.postDelayed({ finish() }, 800)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        fun intent(a: android.content.Context) = Intent(a, QuickRecord::class.java).setAction(MainActivity.ACTION_RECORD)
    }
}
