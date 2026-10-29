package com.nabi.todo

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDate

/** 친구가 보낸 "콕" 알림을 받아 보여 주는 쪽 */
object Nudges {
    /** 앱이 열려 있을 때(실시간)와 워커(15분마다)가 같이 쓴다. 같은 알림은 한 번만 보여 준다. */
    @Synchronized
    fun processIncoming(ctx: Context, cid: String, tasks: List<Task>) {
        val today = LocalDate.now()
        val key = today.toString()
        val me = Store.deviceId(ctx)
        val seen = HashSet(Store.nudgeSeen(ctx))
        var changed = false
        for (t in tasks) {
            val n = t.nudges[key] ?: continue
            if (n.from == me) continue
            val sk = "$cid|${t.id}|$key|${n.at}"
            if (sk in seen) continue
            seen.add(sk)
            changed = true
            if (t.isDone(today)) continue
            show(ctx, cid, t, n, key)
        }
        if (changed) {
            // 이틀 지난 기록은 정리
            val cutoff = today.minusDays(2).toString()
            Store.setNudgeSeen(ctx, seen.filter { (it.split('|').getOrNull(2) ?: "") >= cutoff }.toSet())
        }
    }

    private fun show(ctx: Context, cid: String, t: Task, n: Nudge, day: String) {
        Scheduler.ensureChannels(ctx)
        val id = "nudge|$cid|${t.id}".hashCode()
        val done = Intent(ctx, ActionReceiver::class.java).setAction(ACT_DONE)
            .putExtra("cid", cid).putExtra("taskId", t.id).putExtra("date", day).putExtra("notifId", id)
        val donePi = PendingIntent.getBroadcast(ctx, id + 1, done, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val who = n.name.ifBlank { "친구" }
        val text = "${who}님이 '${t.title}' 하라고 콕 찔렀어요 🐾"
        val b = NotificationCompat.Builder(ctx, CH_NUDGE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("콕! 할 일을 해 주세요")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(Notifier.openApp(ctx))
            .addAction(0, "완료", donePi)
        Notifier.show(ctx, id, b.build())
    }
}

/** 앱이 꺼져 있어도 15분마다 달력 문서 1개만 읽어서 새 콕이 있는지 확인한다(읽기 비용 최소화) */
class NudgeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        for (cal in Store.cals(ctx)) {
            val last = Repo.lastNudgeBlocking(cal.cid) ?: continue
            if (last <= Store.lastNudgeSeen(ctx, cal.cid)) continue
            val list = Repo.fetchTasksBlocking(cal.cid) ?: continue
            Store.saveTasks(ctx, cal.cid, list)
            Nudges.processIncoming(ctx, cal.cid, list)
            Store.setLastNudgeSeen(ctx, cal.cid, last)
        }
        return Result.success()
    }
}
