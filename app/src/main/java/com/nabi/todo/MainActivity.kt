package com.nabi.todo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            NabiTheme {
                val vm: MainViewModel = viewModel()
                MainScreen(vm)
            }
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
