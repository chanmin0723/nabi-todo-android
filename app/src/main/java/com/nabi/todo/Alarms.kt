package com.nabi.todo

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

const val ACT_ALARM = "com.nabi.todo.ALARM"
const val ACT_NAG = "com.nabi.todo.NAG"
const val ACT_TEST = "com.nabi.todo.TEST"
const val ACT_MIDNIGHT = "com.nabi.todo.MIDNIGHT"
const val ACT_DONE = "com.nabi.todo.DONE"
const val ACT_SNOOZE = "com.nabi.todo.SNOOZE"
const val CH_ALARM = "nabi_alarm"
const val CH_NAG = "nabi_nag"
const val CH_UPDATE = "nabi_update"
const val CH_NUDGE = "nabi_nudge"
const val ID_UPDATE = 7_000_010
const val NAG_CODE = 7_000_001
const val MIDNIGHT_CODE = 7_000_030
const val SNOOZE_MINUTES = 10

object Scheduler {
    private const val HORIZON_DAYS = 14

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarm = NotificationChannel(CH_ALARM, "할 일 알람", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "정해 둔 시간에 울리는 알람"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
        }
        val nag = NotificationChannel(CH_NAG, "못 끝낸 일 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "하루가 끝나기 전에 못 끝낸 일을 알려 줘요"
        }
        val upd = NotificationChannel(CH_UPDATE, "앱 업데이트", NotificationManager.IMPORTANCE_LOW).apply {
            description = "새 버전 설치 안내"
        }
        val nudge = NotificationChannel(CH_NUDGE, "친구가 보낸 콕", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "친구가 할 일을 하라고 보낸 알림"
        }
        nm.createNotificationChannel(nudge)
        nm.createNotificationChannel(alarm)
        nm.createNotificationChannel(nag)
        nm.createNotificationChannel(upd)
    }

    fun codeOf(cid: String, taskId: String, date: String): Int = "$cid|$taskId|$date".hashCode()

    private fun am(ctx: Context): AlarmManager = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()

    private fun pending(ctx: Context, action: String, code: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action)
        i.extras()
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAt(ctx: Context, millis: Long, pi: PendingIntent) {
        val a = am(ctx)
        try {
            if (canExact(ctx)) a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (e: SecurityException) {
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun cancel(ctx: Context, code: Int) {
        val pi = pending(ctx, ACT_ALARM, code)
        am(ctx).cancel(pi)
    }

    /** 캐시된 모든 달력의 앞으로 14일 알람을 다시 걸고, 사라진 알람은 취소합니다 */
    @Synchronized
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val wanted = HashSet<String>() // "code|cid|taskId|date"
        for (cal in Store.cals(ctx)) {
            val tasks = Store.loadTasks(ctx, cal.cid)
            for (off in 0 until HORIZON_DAYS) {
                val d = today.plusDays(off.toLong())
                for (t in tasks) {
                    if (t.remind < 0 || t.time.isEmpty() || !t.occursOn(d) || t.isDone(d)) continue
                    val lt = try {
                        LocalTime.parse(t.time)
                    } catch (e: Exception) {
                        continue
                    }
                    val at = LocalDateTime.of(d, lt).minusMinutes(t.remind.toLong())
                        .atZone(zone).toInstant().toEpochMilli()
                    if (at <= now) continue
                    val code = codeOf(cal.cid, t.id, d.toString())
                    wanted.add("$code|${cal.cid}|${t.id}|$d")
                    val cid = cal.cid
                    val tid = t.id
                    val ds = d.toString()
                    val pi = pending(ctx, ACT_ALARM, code) {
                        putExtra("cid", cid); putExtra("taskId", tid); putExtra("date", ds)
                    }
                    setAt(ctx, at, pi)
                }
            }
        }
        for (old in Store.scheduled(ctx)) {
            if (old !in wanted) old.substringBefore('|').toIntOrNull()?.let { cancel(ctx, it) }
        }
        Store.setScheduled(ctx, wanted)
        scheduleNag(ctx)
        scheduleMidnight(ctx)
        // 할 일이 바뀔 때마다 홈 위젯과 잠금 화면 알림도 같이 갱신
        NabiWidget.refresh(ctx)
        LockScreen.update(ctx)
    }

    /** 자정 직후에 날짜가 넘어간 것을 위젯·잠금 화면에 반영 */
    fun scheduleMidnight(ctx: Context) {
        val pi = pending(ctx, ACT_MIDNIGHT, MIDNIGHT_CODE)
        val at = LocalDate.now().plusDays(1).atTime(0, 0, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setAt(ctx, at, pi)
    }

    fun scheduleNag(ctx: Context) {
        val pi = pending(ctx, ACT_NAG, NAG_CODE)
        if (!Store.nagEnabled(ctx)) {
            am(ctx).cancel(pi)
            return
        }
        val mins = Store.nagMinutes(ctx)
        var at = LocalDate.now().atTime(mins / 60, mins % 60)
        if (!at.isAfter(LocalDateTime.now().plusSeconds(5))) at = at.plusDays(1)
        setAt(ctx, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    fun snooze(ctx: Context, cid: String, taskId: String, date: String) {
        val code = codeOf(cid, taskId, date) + 7
        val pi = pending(ctx, ACT_ALARM, code) {
            putExtra("cid", cid); putExtra("taskId", taskId); putExtra("date", date)
        }
        setAt(ctx, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L, pi)
    }

    fun testIn(ctx: Context, seconds: Int) {
        val pi = pending(ctx, ACT_TEST, NAG_CODE + 1)
        setAt(ctx, System.currentTimeMillis() + seconds * 1000L, pi)
    }
}

object Notifier {
    @SuppressLint("MissingPermission")
    fun show(ctx: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // 알림 권한이 없으면 표시하지 않습니다
        }
    }

    fun post(ctx: Context, channel: String, id: Int, title: String, text: String, pi: PendingIntent? = null) {
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi ?: openApp(ctx))
        show(ctx, id, b.build())
    }

    fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.ensureChannels(ctx)
        when (intent.action) {
            ACT_ALARM -> onAlarm(ctx, intent)
            ACT_NAG -> onNag(ctx)
            ACT_MIDNIGHT -> Scheduler.rescheduleAll(ctx)
            ACT_TEST -> {
                val n = NotificationCompat.Builder(ctx, CH_ALARM)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("테스트 알람")
                    .setContentText("이 알림이 소리와 함께 보이면 알람이 정상이에요.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(Notifier.openApp(ctx))
                    .build()
                Notifier.show(ctx, NAG_CODE + 1, n)
            }
        }
    }

    private fun onAlarm(ctx: Context, intent: Intent) {
        val cid = intent.getStringExtra("cid") ?: return
        val taskId = intent.getStringExtra("taskId") ?: return
        val ds = intent.getStringExtra("date") ?: return
        val d = try {
            LocalDate.parse(ds)
        } catch (e: Exception) {
            return
        }
        val t = Store.loadTasks(ctx, cid).find { it.id == taskId } ?: return
        if (!t.occursOn(d) || t.isDone(d)) return // 그 사이 끝냈거나 바뀐 일정은 울리지 않음
        val code = Scheduler.codeOf(cid, taskId, ds)
        fun act(action: String, rc: Int): PendingIntent {
            val i = Intent(ctx, ActionReceiver::class.java).setAction(action)
                .putExtra("cid", cid).putExtra("taskId", taskId).putExtra("date", ds).putExtra("notifId", code)
            return PendingIntent.getBroadcast(ctx, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val text = if (t.time.isNotEmpty()) "${t.time} · ${t.title}" else t.title
        val n = NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("할 시간이에요")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + if (t.note.isNotEmpty()) "\n" + t.note else ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(Notifier.openApp(ctx))
            .addAction(0, "완료", act(ACT_DONE, code + 1))
            .addAction(0, "10분 뒤 다시", act(ACT_SNOOZE, code + 2))
            .build()
        Notifier.show(ctx, code, n)
    }

    private fun onNag(ctx: Context) {
        try {
            val today = LocalDate.now()
            val names = ArrayList<String>()
            for (cal in Store.cals(ctx)) {
                for (t in Store.loadTasks(ctx, cal.cid)) {
                    if ((t.occursOn(today) && !t.isDone(today)) || t.isOverdue(today)) names.add(t.title)
                }
            }
            if (names.isNotEmpty() && Store.nagEnabled(ctx)) {
                val text = names.take(4).joinToString(", ") + if (names.size > 4) " 외 ${names.size - 4}개" else ""
                val n = NotificationCompat.Builder(ctx, CH_NAG)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("아직 못 끝낸 일 ${names.size}개")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(Notifier.openApp(ctx))
                    .build()
                Notifier.show(ctx, NAG_CODE, n)
            }
        } finally {
            Scheduler.scheduleNag(ctx) // 내일 같은 시각으로 다시 걸기
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val cid = intent.getStringExtra("cid") ?: return
        val taskId = intent.getStringExtra("taskId") ?: return
        val ds = intent.getStringExtra("date") ?: return
        val notifId = intent.getIntExtra("notifId", 0)
        NotificationManagerCompat.from(ctx).cancel(notifId)

        if (intent.action == ACT_SNOOZE) {
            Scheduler.snooze(ctx, cid, taskId, ds)
            return
        }
        if (intent.action != ACT_DONE) return

        val d = try {
            LocalDate.parse(ds)
        } catch (e: Exception) {
            return
        }
        val tasks = Store.loadTasks(ctx, cid)
        val t = tasks.find { it.id == taskId } ?: return
        val key = t.occKey(d)
        val old = t.state[key]
        val occ = Occ(true, old?.subs ?: emptyMap())
        // 기기 안 캐시도 바로 반영(알람이 다시 울리지 않도록)
        val updated = tasks.map { if (it.id == taskId) it.copy(state = it.state + (key to occ)) else it }
        Store.saveTasks(ctx, cid, updated)
        Scheduler.rescheduleAll(ctx)

        // Firestore에는 상태 한 칸만 병합 쓰기. 오프라인이면 기기에 대기했다가 연결되면 올라갑니다.
        val pr = goAsync()
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) pr.finish() }
        Repo.patch(cid, taskId, mapOf("state" to mapOf(key to occToMap(occ))))
            .addOnCompleteListener { finish() }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 8000)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.ensureChannels(ctx)
        Scheduler.rescheduleAll(ctx)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Notifier.post(ctx, CH_UPDATE, ID_UPDATE, "🐾 나비 할 일이 업데이트됐다냥", "v${BuildConfig.VERSION_NAME} 으로 바뀌었어요")
        }
    }
}

/** 주기적으로 서버의 최신 일정을 받아 알람을 다시 맞춥니다(웹에서 고친 내용 반영) */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        for (cal in Store.cals(ctx)) {
            val list = Repo.fetchTasksBlocking(cal.cid)
            if (list != null) Store.saveTasks(ctx, cal.cid, list)
        }
        Scheduler.rescheduleAll(ctx)
        return Result.success()
    }
}
