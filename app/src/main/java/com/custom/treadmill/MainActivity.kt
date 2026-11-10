package com.custom.treadmill

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.custom.treadmill.data.repository.ThemeMode
import com.custom.treadmill.ui.AppNavHost
import com.custom.treadmill.ui.TreadmillTheme
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(applicationContext)
        DebugLog.d("UI", "MainActivity.onCreate()")

        setContent {
            val settings by TreadmillApp.instance.settingsStore.settings.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            val context = LocalContext.current
            val notificationPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                DebugLog.d("UI", "POST_NOTIFICATIONS granted=$granted")
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            TreadmillTheme(darkTheme = darkTheme) {
                AppNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DebugLog.d("UI", "MainActivity.onResume()")
    }

    override fun onPause() {
        super.onPause()
        DebugLog.d("UI", "MainActivity.onPause()")
    }

    /**
     * Глобальный перехват тапов/свайпов.
     * [ФИКС] Логируем ТОЛЬКО в debug-сборке. В release приложение не пишет
     * координаты каждого тапа в лог — это и приватность, и лишний I/O.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (BuildConfig.DEBUG) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = ev.x
                    touchDownY = ev.y
                    touchDownAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dur = (SystemClock.uptimeMillis() - touchDownAt).toInt()
                    val dx = ev.x - touchDownX
                    val dy = ev.y - touchDownY
                    val dist = sqrt(dx * dx + dy * dy)
                    val kind = when {
                        dist > 40f -> "SWIPE ${swipeDir(dx, dy)} dist=${dist.toInt()}"
                        dur > 700 -> "LONG-PRESS"
                        else -> "TAP"
                    }
                    DebugLog.d("UI", "$kind x=${ev.x.toInt()} y=${ev.y.toInt()} dur=${dur}ms")
                }
                MotionEvent.ACTION_CANCEL -> {
                    DebugLog.d("UI", "TOUCH-CANCEL")
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun swipeDir(dx: Float, dy: Float): String =
        if (abs(dx) > abs(dy)) (if (dx > 0) "→" else "←")
        else (if (dy > 0) "↓" else "↑")
}
