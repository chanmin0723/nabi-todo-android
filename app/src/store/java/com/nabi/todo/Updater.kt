package com.nabi.todo

import android.content.Context

/** Google Play 버전: 앱 스스로 업데이트하지 않습니다(업데이트는 Play 스토어가 처리). */
object Updater {
    const val ENABLED = false
    const val MODE_OFF = 0
    const val MODE_WIFI = 1
    const val MODE_ANY = 2

    @Volatile
    var foreground = false

    fun currentVersion(): Int = BuildConfig.VERSION_CODE
    fun currentName(): String = BuildConfig.VERSION_NAME
    fun canInstall(ctx: Context): Boolean = false
    fun schedule(ctx: Context) {}
    fun networkAllowed(ctx: Context): Boolean = false
    suspend fun checkAndInstall(ctx: Context): String = ""
    suspend fun checkOnOpen(ctx: Context) {}
}
