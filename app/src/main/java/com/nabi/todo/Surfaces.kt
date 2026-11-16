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

/**
 * 잠금 화면 표시 담당 (설정에서 끌 수 있고 기본은 켜짐)
 * - 아이콘·점 스타일 폰: 잠금 화면 배경을 그림으로 바꿈(LockWallpaper)
 * - 카드 스타일 폰: 알림 카드 목록의 맨 아래에 크게 나오는 알림(배경은 바꾸지 않음)
 */
object LockScreen {
    const val ID = 7_000_020
    const val ID_LATER = 7_000_021
    private const val MAX_LINES = 8
    private const val CH = "nabi_lock3" // 중요도 변경 때문에 새 채널 id

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH, "잠금 화면 할 일", NotificationManager.IMPORTANCE_LOW).apply {
            description = "카드 스타일 잠금 화면 맨 아래에 오늘 할 일과 언젠가 할 일을 보여 줘요"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private var catBmp: Bitmap? = null

    private fun cat(ctx: Context): Bitmap? {
        catBmp?.let { return it }
        return try {
            val src = BitmapFactory.decodeResource(ctx.resources, R.drawable.nabi_cat) ?: return null
            val size = 256
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val w = size * 0.94f
            val h = w * src.height / src.width
            Canvas(out).drawBitmap(src, null, android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            catBmp = out
            out
        } catch (e: Throwable) {
            null
        }
    }

    private fun mark(p: Int) = when { p >= 2 -> "🔴"; p == 1 -> "🟠"; else -> "🔵" }
    private fun line(t: Task) = mark(t.priority) + " " + (if (t.time.isNotEmpty()) t.time + " " else "") + t.title

    private fun build(ctx: Context, title: String, lines: List<String>, sub: String?, sortKey: String): Notification {
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(MAX_LINES).forEach { style.addLine(it) }
        if (lines.size > MAX_LINES) style.addLine("… 외 ${lines.size - MAX_LINES}개")
        val b = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "수고했어요")
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(1L) // 아주 오래된 시각 → 같은 중요도 알림 중 맨 아래로
            .setShowWhen(false)
            .setSortKey(sortKey)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(Notifier.openApp(ctx))
        if (sub != null) b.setSubText(sub)
        cat(ctx)?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun stamp(): String = java.time.LocalTime.now().let { String.format("%02d:%02d", it.hour, it.minute) }

    private fun cancelNotifs(ctx: Context) {
        try {
            val nm = NotificationManagerCompat.from(ctx)
            nm.cancel(ID); nm.cancel(ID_LATER)
        } catch (e: Throwable) {
        }
    }

    /** 앱 안 어디서나 호출: 설정/스타일에 맞게 갱신 */
    fun update(ctx: Context) {
        if (!Store.lockOn(ctx)) {
            cancelNotifs(ctx)
            LockWallpaper.restoreDefault(ctx)
            return
        }
        if (Store.lockStyle(ctx) == 1) {
            LockWallpaper.restoreDefault(ctx) // 배경 방식에서 카드 방식으로 바꾼 경우 배경을 되돌림
            try {
                notifyNow(ctx)
            } catch (e: Throwable) {
                Store.setLockStatus(ctx, "오류로 표시하지 못했어요: " + (e.message ?: e.javaClass.simpleName))
            }
        } else {
            cancelNotifs(ctx)
            LockWallpaper.update(ctx)
        }
    }

    /** 설정 화면 버튼용(백그라운드 스레드에서 호출) */
    fun applyNow(ctx: Context) {
        if (!Store.lockOn(ctx)) { update(ctx); return }
        if (Store.lockStyle(ctx) == 1) update(ctx) else { cancelNotifs(ctx); LockWallpaper.applyNow(ctx, true) }
    }

    private fun notifyNow(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        ensureChannel(ctx)
        val (cal, tasks) = activeTasks(ctx)
        val today = LocalDate.now()
        val list = tasksOnDay(tasks, today)
        if (list.isEmpty()) {
            nm.cancel(ID)
        } else {
            val undone = list.filter { !it.isDone(today) }
            val title = if (undone.isEmpty()) "오늘 할 일을 모두 끝냈어요 🎉" else "오늘 할 일 ${undone.size}개 남음 (전체 ${list.size})"
            Notifier.show(ctx, ID, build(ctx, title, undone.map { line(it) }, cal?.name, "y"))
        }
        val later = tasks.filter { it.date == null && !it.isDone(today) }
            .sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.order })
        if (later.isEmpty()) {
            nm.cancel(ID_LATER)
        } else {
            Notifier.show(ctx, ID_LATER, build(ctx, "언젠가 할 일 ${later.size}개", later.map { mark(it.priority) + " " + it.title }, null, "z"))
        }
        Store.setLockStatus(
            ctx,
            when {
                cal == null -> "참여한 달력이 없어요 (${stamp()})"
                list.isEmpty() && later.isEmpty() -> "'${cal.name}' 달력에 오늘 할 일과 언젠가 할 일이 없어서 알림을 띄우지 않았어요 (${stamp()})"
                else -> "'${cal.name}' 달력 기준으로 알림 카드를 올렸어요: 오늘 ${list.size}개, 언젠가 ${later.size}개 (${stamp()})"
            }
        )
    }

    /** 설정 화면에 보여 줄 점검 결과 */
    fun diagnose(ctx: Context): String {
        val sb = StringBuilder()
        if (Store.lockStyle(ctx) == 1) {
            val nmc = NotificationManagerCompat.from(ctx)
            sb.append("앱 알림 권한: ").append(if (nmc.areNotificationsEnabled()) "허용" else "꺼짐(폰 설정에서 허용 필요)").append('\n')
            val ch = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(CH)
            sb.append("잠금 화면 할 일 채널: ")
                .append(when {
                    ch == null -> "아직 없음"
                    ch.importance == NotificationManager.IMPORTANCE_NONE -> "꺼짐(폰 설정에서 켜야 해요)"
                    else -> "켜짐"
                }).append('\n')
        }
        val st = Store.lockStatus(ctx)
        sb.append("마지막 결과: ").append(if (st.isEmpty()) "아직 실행 전" else st)
        return sb.toString()
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
