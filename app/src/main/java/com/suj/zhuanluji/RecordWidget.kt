package com.suj.zhuanluji

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * 桌面小组件「一键录音」:点一下默默开录(经透明跳板 QuickRecord,不翻出界面)。
 * 不直接从小组件拉录音服务——Android 14 起麦克风前台服务要有可见界面才让开,各家桌面还不一样。
 */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(c, 1, QuickRecord.intent(c).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val v = RemoteViews(c.packageName, R.layout.widget_record)
        v.setOnClickPendingIntent(R.id.widget_root, pi)
        m.updateAppWidget(ids, v)
    }
}
