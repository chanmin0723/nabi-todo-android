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
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/** 잠금 화면 배경 사진을 "오늘 할 일"이 그려진 그림으로 바꿉니다 */
object LockWallpaper {
    private val exec = Executors.newSingleThreadExecutor()
    private const val BG_FILE = "lock_bg.jpg"

    fun bgFile(ctx: Context) = File(ctx.filesDir, BG_FILE)

    /** 고른 사진을 기기 안에 줄여서 복사해 둡니다 */
    fun saveBackground(ctx: Context, uri: Uri): Boolean {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > 2400 || opts.outHeight / sample > 2400) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return false
            bgFile(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearBackground(ctx: Context) {
        bgFile(ctx).delete()
    }

    fun update(ctx: Context, force: Boolean = false) {
        if (!Store.lockWpOn(ctx)) return
        val app = ctx.applicationContext
        exec.execute {
            try {
                render(app, force)
            } catch (e: Throwable) {
                // 기기에서 잠금 화면 배경 변경을 막은 경우 등은 조용히 넘어갑니다
            }
        }
    }

    private fun render(ctx: Context, force: Boolean) {
        val (cal, tasks) = activeTasks(ctx)
        val today = LocalDate.now()
        val list = tasksOnDay(tasks, today)
        val bg = bgFile(ctx)
        val sig = today.toString() + "|" + (cal?.name ?: "") + "|" + (if (bg.exists()) bg.lastModified() else 0L) + "|" +
            list.joinToString(";") { "${it.id}:${it.title}:${it.time}:${it.isDone(today)}" }
        val h = sig.hashCode()
        if (!force && h == Store.lockWpHash(ctx)) return

        val dm = ctx.resources.displayMetrics
        val bmp = draw(ctx, dm.widthPixels, dm.heightPixels, cal?.name ?: "", today, list)
        try {
            WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            Store.setLockWpHash(ctx, h)
        } finally {
            bmp.recycle()
        }
    }

    private fun draw(ctx: Context, w: Int, h: Int, calName: String, today: LocalDate, list: List<Task>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = w / 1080f

        // 배경: 고른 사진(가운데 맞춰 자르기) 또는 파스텔 그라데이션
        val bgf = bgFile(ctx)
        var drewPhoto = false
        if (bgf.exists()) {
            val photo = BitmapFactory.decodeFile(bgf.absolutePath)
            if (photo != null) {
                val scale = maxOf(w / photo.width.toFloat(), h / photo.height.toFloat())
                val dw = photo.width * scale
                val dh = photo.height * scale
                val dst = RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
                c.drawBitmap(photo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                photo.recycle()
                drewPhoto = true
            }
        }
        if (!drewPhoto) {
            val p = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#DCE6FF"), Color.parseColor("#FFE6CF"), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }

        // 항목 정리
        val undone = list.filter { !it.isDone(today) }
        val doneCount = list.size - undone.size
        val rows = undone.take(8).map { (if (it.time.isNotEmpty()) it.time + "  " else "") + it.title }
        val more = undone.size - rows.size

        val ink = Color.parseColor("#2B2A4C")
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 58 * u; typeface = Typeface.DEFAULT_BOLD }
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5B5A80"); textSize = 38 * u }
        val item = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 46 * u; typeface = Typeface.DEFAULT_BOLD }
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F4A259"); style = Paint.Style.STROKE; strokeWidth = 5 * u }

        val padX = 56 * u
        val cardL = 48 * u
        val cardR = w - 48 * u
        val rowH = 78 * u
        val lineCount = rows.size + (if (more > 0) 1 else 0) + (if (rows.isEmpty()) 1 else 0)
        val cardH = 56 * u + 70 * u + 50 * u + lineCount * rowH + (if (doneCount > 0 && rows.isNotEmpty()) 60 * u else 0f) + 40 * u
        val cardT = h * 0.42f
        val rect = RectF(cardL, cardT, cardR, cardT + cardH)

        c.drawRoundRect(rect, 44 * u, 44 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(222, 255, 255, 255) })

        // 나비 아이콘
        ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_fg)?.let {
            val s = (150 * u).toInt()
            val x = (cardR - 30 * u - s).toInt()
            val y = (cardT - s * 0.55f).toInt()
            it.setBounds(x, y, x + s, y + s)
            it.draw(c)
        }

        val maxW = cardR - cardL - padX * 2
        var y = cardT + 56 * u + 50 * u
        val head = when {
            list.isEmpty() -> "오늘은 비어 있어요"
            undone.isEmpty() -> "오늘 할 일을 모두 끝냈어요 🎉"
            else -> "오늘 할 일 ${undone.size}개 남음"
        }
        c.drawText(TextUtils.ellipsize(head, title, maxW - 150 * u, TextUtils.TruncateAt.END).toString(), cardL + padX, y, title)
        y += 46 * u
        val dow = "일월화수목금토"[today.dayOfWeek.value % 7]
        val subText = "${today.monthValue}월 ${today.dayOfMonth}일 ($dow)" + if (calName.isNotEmpty()) " · $calName" else ""
        c.drawText(TextUtils.ellipsize(subText, sub, maxW, TextUtils.TruncateAt.END).toString(), cardL + padX, y, sub)
        y += 40 * u

        rows.forEach { r ->
            y += rowH
            val b = 34 * u
            c.drawRoundRect(RectF(cardL + padX, y - b + 4 * u, cardL + padX + b, y + 4 * u), 8 * u, 8 * u, box)
            val txt = TextUtils.ellipsize(r, item, maxW - b - 24 * u, TextUtils.TruncateAt.END).toString()
            c.drawText(txt, cardL + padX + b + 24 * u, y, item)
        }
        if (more > 0) {
            y += rowH
            c.drawText("… 외 ${more}개", cardL + padX, y, sub)
        }
        if (rows.isEmpty() && list.isNotEmpty()) {
            y += rowH
            c.drawText("수고했어요 🐾", cardL + padX, y, sub)
        }
        if (doneCount > 0 && rows.isNotEmpty()) {
            y += 60 * u
            c.drawText("✓ 완료 ${doneCount}개", cardL + padX, y, sub)
        }
        return bmp
    }
}
