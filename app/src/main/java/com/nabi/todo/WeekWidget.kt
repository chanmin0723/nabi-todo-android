package com.nabi.todo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import java.time.LocalDate

const val ACT_WK_PREV = "com.nabi.todo.WK_PREV"
const val ACT_WK_NEXT = "com.nabi.todo.WK_NEXT"
const val ACT_WK_TODAY = "com.nabi.todo.WK_TODAY"

/** 홈 화면 주간 위젯: 한 주의 날짜별로 할 일 제목을 바로 보여 줍니다 */
class NabiWeekWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val rv = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, rv) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACT_WK_PREV -> { Store.setWidgetWeek(ctx, Store.widgetWeek(ctx) - 1); refresh(ctx) }
            ACT_WK_NEXT -> { Store.setWidgetWeek(ctx, Store.widgetWeek(ctx) + 1); refresh(ctx) }
            ACT_WK_TODAY, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Store.setWidgetWeek(ctx, 0); refresh(ctx)
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NabiWeekWidget::class.java))
            if (ids.isEmpty()) return
            val rv = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, rv) }
        }

        private fun selfPi(ctx: Context, action: String, rc: Int): PendingIntent {
            val i = Intent(ctx, NabiWeekWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(ctx, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun dayPi(ctx: Context, ds: String, rc: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .setData(Uri.parse("nabi://weekday/$ds"))
                .putExtra("date", ds)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(ctx, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun lineOf(t: Task): String = (if (t.time.isNotEmpty()) t.time + " " else "") + t.title

        private fun build(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_week)
            val today = LocalDate.now()
            val base = today.plusWeeks(Store.widgetWeek(ctx).toLong())
            val start = base.minusDays((base.dayOfWeek.value % 7).toLong()) // 일요일 시작
            val end = start.plusDays(6)
            val (_, tasks) = activeTasks(ctx)

            rv.setTextViewText(R.id.wk_title, "${start.monthValue}월 ${start.dayOfMonth}일 – ${end.monthValue}월 ${end.dayOfMonth}일")
            rv.setOnClickPendingIntent(R.id.wk_prev, selfPi(ctx, ACT_WK_PREV, 11))
            rv.setOnClickPendingIntent(R.id.wk_next, selfPi(ctx, ACT_WK_NEXT, 12))
            rv.setOnClickPendingIntent(R.id.wk_today, selfPi(ctx, ACT_WK_TODAY, 13))

            val names = "일월화수목금토"
            for (i in 0 until 7) {
                val d = start.plusDays(i.toLong())
                val onDay = tasksOnDay(tasks, d)
                val undone = onDay.filter { !it.isDone(d) }
                val dayColor = when (i) {
                    0 -> 0xFFD9534F.toInt()
                    6 -> 0xFF5B84D6.toInt()
                    else -> 0xFF2B2A4C.toInt()
                }
                rv.setTextViewText(WDAY_IDS[i], "${d.dayOfMonth}\n${names[i]}")
                rv.setTextColor(WDAY_IDS[i], dayColor)

                val text: String
                val color: Int
                when {
                    onDay.isEmpty() -> { text = "–"; color = 0xFFB5B7CC.toInt() }
                    undone.isEmpty() -> { text = "✓ 모두 완료 (${onDay.size})"; color = 0xFF4FB38A.toInt() }
                    undone.size == 1 -> { text = lineOf(undone[0]); color = 0xFF2B2A4C.toInt() }
                    undone.size == 2 -> { text = lineOf(undone[0]) + "\n" + lineOf(undone[1]); color = 0xFF2B2A4C.toInt() }
                    else -> { text = lineOf(undone[0]) + "\n외 ${undone.size - 1}개"; color = 0xFF2B2A4C.toInt() }
                }
                rv.setTextViewText(WTXT_IDS[i], text)
                rv.setTextColor(WTXT_IDS[i], color)
                rv.setInt(WROW_IDS[i], "setBackgroundResource", if (d == today) R.drawable.cell_today else 0)
                rv.setOnClickPendingIntent(WROW_IDS[i], dayPi(ctx, d.toString(), 200 + i))
            }
            return rv
        }
    }
}
