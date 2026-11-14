package com.nabi.todo

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.Executors

/**
 * 잠금 화면 배경을 "오늘 할 일(위) + 언젠가 할 일(아래)"이 그려진 그림으로 바꿉니다.
 * 이미지라서 접고 펴는 건 못 하고, 줄이 많으면 위쪽 몇 개만 보여 주고 "… 외 N개"로 줄입니다.
 */
object LockWallpaper {
    private val exec = Executors.newSingleThreadExecutor()

    private fun stamp(): String = LocalTime.now().let { String.format("%02d:%02d", it.hour, it.minute) }

    fun update(ctx: Context, force: Boolean = false) {
        if (!Store.lockOn(ctx)) return
        val app = ctx.applicationContext
        exec.execute { applyNow(app, force) }
    }

    /** 바로 그려서 적용(백그라운드 스레드에서 호출). 결과는 설정 화면의 "마지막 결과"에 남습니다. */
    fun applyNow(ctx: Context, force: Boolean = true) {
        try {
            val wm = WallpaperManager.getInstance(ctx)
            if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) {
                Store.setLockStatus(ctx, "이 폰에서는 앱이 배경화면을 바꿀 수 없어요 (${stamp()})")
                return
            }
            render(ctx, wm, force)
        } catch (e: Throwable) {
            Store.setLockStatus(ctx, "배경 변경에 실패했어요: " + (e.message ?: e.javaClass.simpleName) + " (${stamp()})")
        }
    }

    private fun render(ctx: Context, wm: WallpaperManager, force: Boolean) {
        val (cal, tasks) = activeTasks(ctx)
        val today = LocalDate.now()
        val list = tasksOnDay(tasks, today)
        val later = tasks.filter { it.date == null && !it.isDone(today) }
            .sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.order })
        val sig = today.toString() + "|" + (cal?.name ?: "") + "|" +
            list.joinToString(";") { "${it.id}:${it.title}:${it.time}:${it.priority}:${it.isDone(today)}" } + "|" +
            later.joinToString(";") { "${it.id}:${it.title}:${it.priority}" }
        val h = sig.hashCode()
        if (!force && h == Store.lockWpHash(ctx)) return

        val dm = ctx.resources.displayMetrics
        val bmp = draw(ctx, dm.widthPixels, dm.heightPixels, cal?.name ?: "", today, list, later)
        try {
            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            Store.setLockWpHash(ctx, h)
            Store.setLockStatus(
                ctx,
                if (cal == null) "참여한 달력이 없어서 빈 배경을 그렸어요 (${stamp()})"
                else "'${cal.name}' 달력 기준으로 배경을 바꿨어요: 오늘 ${list.size}개, 언젠가 ${later.size}개 (${stamp()})"
            )
        } finally {
            bmp.recycle()
        }
    }

    private fun prio(p: Int) = when { p >= 2 -> Color.parseColor("#D9534F"); p == 1 -> Color.parseColor("#E8920F"); else -> Color.parseColor("#5B84D6") }

    private fun draw(ctx: Context, w: Int, h: Int, calName: String, today: LocalDate, list: List<Task>, later: List<Task>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = w / 1080f

        val bgP = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#DCE6FF"), Color.parseColor("#FFE6CF"), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgP)

        val undone = list.filter { !it.isDone(today) }
        val doneCount = list.size - undone.size

        val ink = Color.parseColor("#2B2A4C")
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 54 * u; typeface = Typeface.DEFAULT_BOLD }
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5B5A80"); textSize = 36 * u }
        val item = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 44 * u; typeface = Typeface.DEFAULT_BOLD }
        val boxP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * u }
        val cardP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(226, 255, 255, 255) }

        val cardL = 48 * u
        val cardR = w - 48 * u
        val padX = 52 * u
        val rowH = 70 * u
        val head1 = 150 * u
        val head2 = 96 * u
        val padB = 36 * u
        val gap = 30 * u
        val top = h * 0.40f
        val bottomLimit = h * 0.86f

        var tRows = minOf(undone.size, 6)
        var lRows = minOf(later.size, 4)
        fun extra(total: Int, shown: Int) = if (total > shown) 1 else 0
        fun h1(): Float = head1 + (maxOf(tRows, 1) + extra(undone.size, tRows)) * rowH + (if (doneCount > 0 && undone.isNotEmpty()) rowH * 0.8f else 0f) + padB
        fun h2(): Float = if (later.isEmpty()) 0f else gap + head2 + (lRows + extra(later.size, lRows)) * rowH + padB
        while (top + h1() + h2() > bottomLimit && (lRows > 2 || tRows > 3)) {
            if (lRows > 2) lRows-- else tRows--
        }

        val maxW = cardR - cardL - padX * 2

        // ---- 카드 1: 오늘 할 일
        val c1 = RectF(cardL, top, cardR, top + h1())
        c.drawRoundRect(c1, 44 * u, 44 * u, cardP)

        val cat = BitmapFactory.decodeResource(ctx.resources, R.drawable.nabi_cat)
        if (cat != null) {
            val cw = 300 * u
            val ch = cw * cat.height / cat.width
            c.drawBitmap(cat, null, RectF(cardR - 20 * u - cw, top - ch * 0.62f, cardR - 20 * u, top + ch * 0.38f), Paint(Paint.FILTER_BITMAP_FLAG))
            cat.recycle()
        }

        var y = top + 28 * u + 54 * u
        val head = when {
            list.isEmpty() -> "오늘은 비어 있어요"
            undone.isEmpty() -> "오늘 할 일을 모두 끝냈어요!"
            else -> "오늘 할 일 ${undone.size}개 남음"
        }
        c.drawText(TextUtils.ellipsize(head, title, maxW - 260 * u, TextUtils.TruncateAt.END).toString(), cardL + padX, y, title)
        y += 46 * u
        val dow = "일월화수목금토"[today.dayOfWeek.value % 7]
        val subText = "${today.monthValue}월 ${today.dayOfMonth}일 ($dow)" + if (calName.isNotEmpty()) " · $calName" else ""
        c.drawText(TextUtils.ellipsize(subText, sub, maxW, TextUtils.TruncateAt.END).toString(), cardL + padX, y, sub)
        y += 34 * u

        fun row(t: Task, withTime: Boolean) {
            y += rowH
            val b = 32 * u
            boxP.color = prio(t.priority)
            c.drawRoundRect(RectF(cardL + padX, y - b + 4 * u, cardL + padX + b, y + 4 * u), 8 * u, 8 * u, boxP)
            val txt = (if (withTime && t.time.isNotEmpty()) t.time + "  " else "") + t.title
            c.drawText(TextUtils.ellipsize(txt, item, maxW - b - 22 * u, TextUtils.TruncateAt.END).toString(), cardL + padX + b + 22 * u, y, item)
        }
        undone.take(tRows).forEach { row(it, true) }
        if (undone.size > tRows) { y += rowH; c.drawText("… 외 ${undone.size - tRows}개", cardL + padX, y, sub) }
        if (undone.isEmpty() && list.isNotEmpty()) { y += rowH; c.drawText("수고했어요", cardL + padX, y, sub) }
        if (list.isEmpty()) { y += rowH; c.drawText("여유로운 하루예요", cardL + padX, y, sub) }
        if (doneCount > 0 && undone.isNotEmpty()) { y += rowH * 0.8f; c.drawText("✓ 완료 ${doneCount}개", cardL + padX, y, sub) }

        // ---- 카드 2: 언젠가 할 일 (날짜 없는 일)
        if (later.isNotEmpty()) {
            val t2 = c1.bottom + gap
            val c2 = RectF(cardL, t2, cardR, t2 + h2() - gap)
            c.drawRoundRect(c2, 44 * u, 44 * u, cardP)
            y = t2 + 26 * u + 50 * u
            c.drawText("언젠가 할 일 ${later.size}개", title, cardL + padX, y)
            y += 22 * u
            later.take(lRows).forEach { row(it, false) }
            if (later.size > lRows) { y += rowH; c.drawText("… 외 ${later.size - lRows}개", cardL + padX, y, sub) }
        }
        return bmp
    }

    private fun Canvas.drawText(text: String, paint: TextPaint, x: Float, y: Float) = drawText(text, x, y, paint)
}
