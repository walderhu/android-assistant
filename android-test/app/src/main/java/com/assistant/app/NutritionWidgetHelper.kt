package com.assistant.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object NutritionWidgetHelper {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, NutritionWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = buildRemoteViews(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
    }

    fun buildRemoteViews(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_nutrition)
        val dateKey = LocalDate.now().format(DATE_FMT)
        val p = NutritionController.load(ctx)
        val t = NutritionController.dayTotals(ctx, dateKey)

        rv.setTextViewText(
            R.id.widgetStats,
            "${t.kcal}/${p.kcalNorm} · Б${fmt(t.protein)} Ж${fmt(t.fat)} У${fmt(t.carbs)}"
        )
        rv.setOnClickPendingIntent(
            R.id.widgetBtnPlus,
            mainPi(ctx, MainActivity.EXTRA_WIDGET_ADD_MEAL, 10)
        )
        rv.setOnClickPendingIntent(
            R.id.widgetBtnMic,
            mainPi(ctx, MainActivity.EXTRA_WIDGET_VOICE, 11)
        )
        rv.setOnClickPendingIntent(R.id.widgetRoot, openAppPi(ctx, 12))
        rv.setOnClickPendingIntent(R.id.widgetStats, openAppPi(ctx, 13))
        return rv
    }

    private fun fmt(v: Double): String =
        if (v % 1.0 < 0.05) v.roundToInt().toString() else "%.0f".format(v)

    private fun mainPi(ctx: Context, extra: String, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(extra, true)
        }
        return PendingIntent.getActivity(
            ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPi(ctx: Context, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_NUTRITION, true)
        }
        return PendingIntent.getActivity(
            ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
