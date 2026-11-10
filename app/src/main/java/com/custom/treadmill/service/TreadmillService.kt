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

    // [ФИКС] SupervisorJob вместо Job: падение одного коллектора не убивает остальные.
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // [ФИКС] Коллекторы уже запущены. При повторных onStartCommand
    // (например, после нажатия «Пауза»/«Стоп» в уведомлении) не перезапускаем.
    @Volatile private var collectorsStarted = false

    // [ФИКС] Троттлинг уведомления: CONFLATED-канал + delay в воркере.
    // Сколько бы раз ни пришли изменения, notify() вызывается не чаще раза в 1.5с.
    private val notificationChannel = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Один воркер на весь срок жизни сервиса.
        serviceScope.launch {
            for (u in notificationChannel) {
                updateNotification()
                delay(1500L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Действия из уведомления
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

        // startForeground можно вызывать многократно — просто обновит уведомление
        startForeground(notificationId, buildNotification())

        // [ФИКС] Коллекторы запускаем ровно один раз за жизнь сервиса
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

    // [ФИКС] Утечка корутин: при уничтожении сервиса отменяем scope целиком.
    // Без этого 6 collectLatest продолжали бы жить и на следующем запуске
    // сервиса запустились бы ещё раз → дублирующиеся обновления уведомления.
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
        val hrStr = if (hr > 0) "$hr уд/мин" else "—"

        val title = if (ws.running || ws.paused) {
            val segName = ws.currentSegmentName.ifBlank { "Сегмент ${ws.currentSegmentIndex + 1}" }
            "Сегмент ${ws.currentSegmentIndex + 1}/${ws.totalSegments} · $segName"
        } else {
            "Treadmill Control"
        }

        val content = buildString {
            append("%.1f км/ч".format(data.speedKmh))
            append(" · ")
            append(hrStr)
            append(" · ")
            append(timeStr)
            if (isPaused) {
                append(" · ПАУЗА")
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

        val pauseLabel = if (isPaused) "Продолжить" else "Пауза"

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(0, pauseLabel, pausePendingIntent)
            .addAction(0, "Стоп", stopPendingIntent)
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
                "Тренировка",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает текущие метрики тренировки"
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
