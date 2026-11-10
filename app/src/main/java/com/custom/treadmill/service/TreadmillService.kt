package com.custom.treadmill.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.custom.treadmill.MainActivity
import com.custom.treadmill.R
import com.custom.treadmill.TreadmillApp
import com.custom.treadmill.ble.BleConnectionState
import com.custom.treadmill.logic.SpeedFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TreadmillService : Service() {

    private val notificationId = 1
    private val channelId = "treadmill_channel"

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile private var collectorsStarted = false

    private val notificationChannel = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            for (u in notificationChannel) {
                updateNotification()
                delay(1500L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                TreadmillApp.instance.session.stopTreadmill()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                TreadmillApp.instance.session.togglePause()
                requestNotificationUpdate()
                return START_STICKY
            }
        }

        startForeground(notificationId, buildNotification())

        if (collectorsStarted) return START_STICKY
        collectorsStarted = true

        val session = TreadmillApp.instance.session

        serviceScope.launch {
            session.treadmillData.collectLatest { requestNotificationUpdate() }
        }
        serviceScope.launch {
            session.heartRate.collectLatest { requestNotificationUpdate() }
        }
        serviceScope.launch {
            session.uiElapsedSec.collectLatest { requestNotificationUpdate() }
        }
        serviceScope.launch {
            session.isPaused.collectLatest { requestNotificationUpdate() }
        }
        serviceScope.launch {
            session.workoutState.collectLatest { requestNotificationUpdate() }
        }
        serviceScope.launch {
            session.treadmillState.collectLatest { state ->
                requestNotificationUpdate()
                if (state == BleConnectionState.DISCONNECTED && !session.workoutState.value.running) {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        collectorsStarted = false
    }

    private fun requestNotificationUpdate() {
        notificationChannel.trySend(Unit)
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, buildNotification())
    }

    private fun buildNotification(): Notification {
        val session = TreadmillApp.instance.session
        val data = session.treadmillData.value
        val hr = session.heartRate.value
        val sec = session.uiElapsedSec.value
        val ws = session.workoutState.value
        val isPaused = session.isPaused.value || ws.paused

        val timeStr = formatElapsed(sec)
        val hrStr = if (hr > 0) "$hr ${getString(R.string.unit_bpm)}" else "—"

        val title = if (ws.running || ws.paused) {
            val segName = ws.currentSegmentName.ifBlank {
                getString(R.string.programs_segment_default, ws.currentSegmentIndex + 1)
            }
            getString(R.string.workout_segment_progress, ws.currentSegmentIndex + 1, ws.totalSegments, segName)
        } else {
            getString(R.string.app_name)
        }

        // [ФИКС] Скорость + темп рядом. Темп показываем только когда дорожка
        // реально едет — иначе в шторке будет бессмысленное "0.0 км/ч (— /км)".
        val content = buildString {
            append("%.1f %s".format(data.speedKmh, getString(R.string.unit_kmh)))
            if (data.speedKmh > 0.5) {
                append(" (")
                append(SpeedFormatter.formatPace(data.speedKmh))
                append(" ")
                append(getString(R.string.unit_pace))
                append(")")
            }
            append(" · ")
            append(hrStr)
            append(" · ")
            append(timeStr)
            if (isPaused) {
                append(" · ")
                append(getString(R.string.workout_paused))
            }
        }

        val stopIntent = Intent(this, TreadmillService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseIntent = Intent(this, TreadmillService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 2, pauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseLabel = getString(
            if (isPaused) R.string.main_btn_resume else R.string.main_btn_pause
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(0, pauseLabel, pausePendingIntent)
            .addAction(0, getString(R.string.main_btn_stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun formatElapsed(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.workout_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current workout metrics"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TOGGLE_PAUSE = "ACTION_TOGGLE_PAUSE"
    }
}
