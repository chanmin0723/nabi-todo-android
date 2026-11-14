package com.nabi.todo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.YearMonth

const val ACT_W_PREV = "com.nabi.todo.W_PREV"
const val ACT_W_NEXT = "com.nabi.todo.W_NEXT"
const val ACT_W_TODAY = "com.nabi.todo.W_TODAY"
const val CH_LOCK = "nabi_lock2" // 중요도를 바꾸려면 새 채널 id가 필요

internal fun activeTasks(ctx: Context): Pair<CalRef?, List<Task>> {
    val cals = Store.cals(ctx)
    val a = Store.active(ctx)
    val cal = cals.find { it.cid == a } ?: cals.firstOrNull()
    return cal to (cal?.let { Store.loadTasks(ctx, it.cid) } ?: emptyList())
}

/** 잠금 화면 표시 담당: 배경 그림 갱신(설정에서 끌 수 있고 기본은 켜짐). 예전 알림 방식은 걷어냅니다. */
object LockScreen {
    private const val OLD_ID = 7_000_020
    private const val OLD_ID_LATER = 7_000_021

    fun update(ctx: Context) {
        try {
            val nm = NotificationManagerCompat.from(ctx)
            nm.cancel(OLD_ID); nm.cancel(OLD_ID_LATER) // 이전 버전의 잠금 화면 알림 정리
        } catch (e: Throwable) {
        }
        LockWallpaper.update(ctx)
    }

    /** 설정 화면에 보여 줄 점검 결과 */
    fun diagnose(ctx: Context): String {
        val st = Store.lockStatus(ctx)
        return "마지막 결과: " + if (st.isEmpty()) "아직 실행 전" else st
    }
}

/** 홈 화면 월간 달력 위젯 (RemoteViews) */
class NabiWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val rv = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, rv) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACT_W_PREV -> { Store.setWidgetMonth(ctx, Store.widgetMonth(ctx) - 1); refresh(ctx) }
            ACT_W_NEXT -> { Store.setWidgetMonth(ctx, Store.widgetMonth(ctx) + 1); refresh(ctx) }
            ACT_W_TODAY -> { Store.setWidgetMonth(ctx, 0); refresh(ctx) }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> { Store.setWidgetMonth(ctx, 0); refresh(ctx) }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val cn = ComponentName(ctx, NabiWidget::class.java)
            val ids = mgr.getAppWidgetIds(cn)
            if (ids.isEmpty()) return
            val rv = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, rv) }
        }

        private fun selfPi(ctx: Context, action: String, rc: Int): PendingIntent {
            val i = Intent(ctx, NabiWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(ctx, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun dayPi(ctx: Context, ds: String, rc: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .setData(Uri.parse("nabi://day/$ds"))
                .putExtra("date", ds)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(ctx, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun build(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_calendar)
            val today = LocalDate.now()
            val ym = YearMonth.from(today).plusMonths(Store.widgetMonth(ctx).toLong())
            val (cal, tasks) = activeTasks(ctx)

            rv.setTextViewText(R.id.w_title, "${ym.year}년 ${ym.monthValue}월")
            rv.setOnClickPendingIntent(R.id.w_prev, selfPi(ctx, ACT_W_PREV, 1))
            rv.setOnClickPendingIntent(R.id.w_next, selfPi(ctx, ACT_W_NEXT, 2))
            rv.setOnClickPendingIntent(R.id.w_today, selfPi(ctx, ACT_W_TODAY, 3))
            rv.setOnClickPendingIntent(R.id.w_title, dayPi(ctx, today.toString(), 4))

            val first = ym.atDay(1)
            val lead = first.dayOfWeek.value % 7 // 일요일 시작
            val start = first.minusDays(lead.toLong())
            for (i in 0 until 42) {
                val d = start.plusDays(i.toLong())
                val inMonth = d.monthValue == ym.monthValue
                val color = when {
                    !inMonth -> 0xFFB5B7CC.toInt()
                    d.dayOfWeek.value == 7 -> 0xFFD9534F.toInt()
                    d.dayOfWeek.value == 6 -> 0xFF5B84D6.toInt()
                    else -> 0xFF2B2A4C.toInt()
                }
                rv.setTextViewText(NUM_IDS[i], d.dayOfMonth.toString())
                rv.setTextColor(NUM_IDS[i], color)
                val onDay = tasks.filter { it.occursOn(d) }
                val undone = onDay.count { !it.isDone(d) }
                val dots = when {
                    onDay.isEmpty() -> ""
                    undone == 0 -> "✓"
                    else -> "●".repeat(minOf(undone, 3))
                }
                rv.setTextViewText(DOT_IDS[i], dots)
                rv.setTextColor(DOT_IDS[i], if (onDay.isNotEmpty() && undone == 0) 0xFF4FB38A.toInt() else 0xFFF4A259.toInt())
                rv.setInt(CELL_IDS[i], "setBackgroundResource", if (d == today) R.drawable.cell_today else 0)
                rv.setOnClickPendingIntent(CELL_IDS[i], dayPi(ctx, d.toString(), 100 + i))
            }
            val left = tasksOnDay(tasks, today).count { !it.isDone(today) }
            rv.setTextViewText(
                R.id.w_foot,
                (cal?.name ?: "달력을 앱에서 선택하세요") + if (cal != null) " · 오늘 남은 일 ${left}개" else ""
            )
            return rv
        }
    }
}
