package com.nabi.todo

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.util.concurrent.TimeUnit

class NabiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // google-services.json 없이, 웹과 같은 Firebase 프로젝트 값으로 직접 초기화합니다.
        if (FirebaseApp.getApps(this).isEmpty()) {
            val opts = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FB_API_KEY)
                .setApplicationId(BuildConfig.FB_APP_ID)
                .setProjectId(BuildConfig.FB_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FB_SENDER_ID)
                .setStorageBucket(BuildConfig.FB_BUCKET)
                .build()
            FirebaseApp.initializeApp(this, opts)
        }
        Scheduler.ensureChannels(this)
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("nabi-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        Updater.schedule(this)
    }
}
