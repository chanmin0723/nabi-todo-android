package com.nabi.todo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.time.LocalDate
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            NabiTheme {
                MainScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 위젯의 날짜 칸에서 들어오면 그 날짜를 보여 줍니다 */
    private fun handleIntent(i: Intent?) {
        val ds = i?.getStringExtra("date") ?: return
        try {
            vm.openDay(LocalDate.parse(ds))
        } catch (e: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        Scheduler.rescheduleAll(this) // 권한(정확한 알람) 변경이나 날짜 경과를 반영
        Updater.foreground = true
        lifecycleScope.launch { Updater.checkOnOpen(this@MainActivity) }
    }

    override fun onPause() {
        super.onPause()
        Updater.foreground = false
    }
}
