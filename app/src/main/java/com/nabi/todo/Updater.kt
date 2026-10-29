package com.nabi.todo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * GitHub Release(v1.0.N)를 확인해서 지금 버전(versionCode = N)보다 새것이면
 * APK를 받아 PackageInstaller로 설치한다.
 * - 처음 한 번은 Android가 설치 확인을 물어볼 수 있음
 * - 그 뒤로는 이 앱이 '설치한 앱'이 되어 Android 12+에서는 묻지 않고 설치됨
 */
object Updater {
    // 배포 저장소를 바꾸면 이 한 줄만 고치면 됩니다.
    const val REPO = "chanmin0723/nabi-todo-android"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val WORK = "nabi_auto_update"

    const val MODE_OFF = 0
    const val MODE_WIFI = 1
    const val MODE_ANY = 2

    data class Release(val versionCode: Int, val tag: String, val apkUrl: String)

    @Volatile
    var foreground = false
    private val lock = Mutex()

    fun currentVersion(): Int = BuildConfig.VERSION_CODE
    fun currentName(): String = BuildConfig.VERSION_NAME

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val mode = Store.updateMode(ctx)
        if (mode == MODE_OFF) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (mode == MODE_WIFI) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun networkAllowed(ctx: Context): Boolean {
        val mode = Store.updateMode(ctx)
        if (mode == MODE_OFF) return false
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (cm.activeNetwork == null) return false
        return mode == MODE_ANY || !cm.isActiveNetworkMetered
    }

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (c.responseCode != 200) return@withContext null
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = o.getString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
            val assets = o.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    url = a.getString("browser_download_url")
                    break
                }
            }
            url?.let { Release(code, tag, it) }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun download(url: String, dest: File) = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (c.responseCode != 200) throw IllegalStateException("다운로드 실패 (${c.responseCode})")
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                ctx, sessionId,
                Intent(ctx, InstallStatusReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            session.commit(status.intentSender)
        }
    }

    /** @return 화면에 보여줄 결과 한 줄 */
    suspend fun checkAndInstall(ctx: Context): String = lock.withLock {
        val r = runCatching { fetchLatest() }.getOrNull()
            ?: return@withLock "업데이트 정보를 못 가져왔어요 (인터넷 확인)"
        if (r.versionCode <= currentVersion()) return@withLock "이미 최신 버전(v${currentName()})이에요 ✨"

        if (!canInstall(ctx)) {
            Notifier.post(
                ctx, CH_UPDATE, ID_UPDATE,
                "새 버전 ${r.tag}이 나왔어요!",
                "설정 → 자동 업데이트에서 '설치 허용'을 켜면 알아서 설치할게요"
            )
            return@withLock "새 버전 ${r.tag}이 있지만 설치 허용이 필요해요"
        }
        return@withLock try {
            val apk = File(ctx.cacheDir, "update.apk")
            download(r.apkUrl, apk)
            install(ctx, apk)
            "${r.tag} 설치 중이에요…"
        } catch (e: Exception) {
            "업데이트 실패: ${e.message ?: "알 수 없는 오류"}"
        }
    }

    /** 앱을 열 때: 1시간에 한 번만, 네트워크 설정에 맞을 때만 */
    suspend fun checkOnOpen(ctx: Context) {
        val last = Store.lastUpdateCheck(ctx)
        val now = System.currentTimeMillis()
        if (now - last < 60 * 60 * 1000L || !networkAllowed(ctx)) return
        Store.setLastUpdateCheck(ctx, now)
        checkAndInstall(ctx)
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Updater.checkAndInstall(applicationContext)
        return Result.success()
    }
}

/** PackageInstaller 결과. 사용자 확인이 필요하면 확인 화면을 띄우거나 알림으로 안내 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Updater.foreground) {
                    runCatching { context.startActivity(confirm) }
                }
                val pi = PendingIntent.getActivity(
                    context, 4242, confirm,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                Notifier.post(
                    context, CH_UPDATE, ID_UPDATE,
                    "🐾 새 버전 설치 준비 완료",
                    "눌러서 업데이트를 마무리해 주세요 (다음부터는 알아서 할게요)", pi
                )
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // 새 버전이 뜨면 BootReceiver가 알림
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 오류"
                Notifier.post(context, CH_UPDATE, ID_UPDATE, "업데이트 실패 😿", msg)
            }
        }
    }
}
