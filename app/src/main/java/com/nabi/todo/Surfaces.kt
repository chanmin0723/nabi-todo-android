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

private fun activeTasks(ctx: Context): Pair<CalRef?, List<Task>> {
    val cals = Store.cals(ctx)
    val a = Store.active(ctx)
    val cal = cals.find { it.cid == a } ?: cals.firstOrNull()
    return cal to (cal?.let { Store.loadTasks(ctx, it.cid) } ?: emptyList())
}

/** 잠금 화면에 오늘 할 일을 보여 주는 상시 알림 (설정에서 켜고 끔) */
object LockScreen {
    const val ID = 7_000_020

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_LOCK, "잠금 화면 오늘 할 일", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "잠금 화면에 오늘 할 일을 계속 보여 줘요"
            setSound(null, null) // 소리·진동 없이 조용히
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun update(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!Store.lockOn(ctx)) {
            nm.cancel(ID)
            return
        }
        ensureChannel(ctx)
        val (cal, tasks) = activeTasks(ctx)
        val today = LocalDate.now()
        val list = tasksOnDay(tasks, today)
        if (list.isEmpty()) {
            nm.cancel(ID)
            return
        }
        val undone = list.filter { !it.isDone(today) }
        val title = if (undone.isEmpty()) "오늘 할 일을 모두 끝냈어요 🎉" else "오늘 할 일 ${undone.size}개 남음 (전체 ${list.size})"
        val lines = undone.map { (if (it.time.isNotEmpty()) it.time + " " else "") + it.title }
        val style = NotificationCompat.InboxStyle()
        lines.take(7).forEach { style.addLine("☐ $it") }
        if (lines.size > 7) style.addLine("… 외 ${lines.size - 7}개")
        val b = NotificationCompat.Builder(ctx, CH_LOCK)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "수고했어요")
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setContentIntent(Notifier.openApp(ctx))
        if (cal != null) b.setSubText(cal.name)
        Notifier.show(ctx, ID, b.build())
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
