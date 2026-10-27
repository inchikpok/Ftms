package com.custom.treadmill.ui.screens

import android.app.Activity
import android.bluetooth.le.ScanResult
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.TreadmillApp
import com.custom.treadmill.ble.BleConnectionState
import com.custom.treadmill.ui.Routes
import com.custom.treadmill.ui.components.CapabilitiesDialog
import com.custom.treadmill.ui.components.RollingText
import com.custom.treadmill.ui.hasBlePermissions
import com.custom.treadmill.ui.requiredBlePermissions
import com.custom.treadmill.ui.viewmodels.MainViewModel
import com.custom.treadmill.ui.viewmodels.ScanMode
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun MainScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    val ctx = LocalContext.current
    val ble = TreadmillApp.instance.bleManager

    val treadmillState by vm.treadmillState.collectAsState()
    val treadmillName by vm.treadmillName.collectAsState()
    val treadmillData by vm.treadmillData.collectAsState()
    val capabilities by vm.capabilities.collectAsState()
    val hrState by vm.hrState.collectAsState()
    val hrName by vm.hrName.collectAsState()
    val targetSpeed by vm.targetSpeed.collectAsState()
    val targetIncline by vm.targetIncline.collectAsState()
    val hr by vm.heartRate.collectAsState()
    val scanResults by vm.scanResults.collectAsState()
    val scanMode by vm.scanMode.collectAsState()
    val settings by vm.settings.collectAsState()
    val paused by vm.isPaused.collectAsState()
    val workoutState by vm.workoutState.collectAsState()

    val elapsedSec by vm.uiElapsedSec.collectAsState()
    val distanceKm by vm.uiDistanceKm.collectAsState()
    val calories by vm.uiCalories.collectAsState()

    val treadmillReady = treadmillState == BleConnectionState.READY
    val speedStep = settings.speedStepKmh
    val showIncline = !capabilities.isLoaded || capabilities.supportsIncline

    var cooldownRemaining by remember { mutableStateOf(0) }
    val cooldownUntil by vm.scanCooldownUntil.collectAsState()

    LaunchedEffect(cooldownUntil) {
        while (true) {
            val rem = ((cooldownUntil - System.currentTimeMillis()) / 1000L).toInt() + 1
            cooldownRemaining = if (rem > 0) rem else 0
            if (cooldownRemaining == 0) break
            delay(500)
        }
    }

    var showCapsDialog by remember { mutableStateOf(false) }

    val keepScreenOn = settings.keepScreenOn && (treadmillData.isRunning || paused)
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        val window = (view.context as? Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var permsGranted by remember { mutableStateOf(hasBlePermissions(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permsGranted = hasBlePermissions(ctx) }

    LaunchedEffect(Unit) { if (!permsGranted) permLauncher.launch(requiredBlePermissions()) }
    LaunchedEffect(scanMode) {
        if (scanMode != ScanMode.NONE) { kotlinx.coroutines.delay(15_000); vm.stopScan() }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
            ) {
                Spacer(Modifier.height(6.dp))

                Text(
                    "Treadmill Control",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        treadmillName ?: "Дорожка не подключена",
                        fontSize = 11.sp,
                        color = if (treadmillName != null)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (treadmillReady) {
                        Spacer(Modifier.width(6.dp))
                        FilledIconButton(
                            onClick = { showCapsDialog = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Возможности дорожки",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactStatus(
                        label = "Дорожка",
                        deviceName = treadmillName,
                        state = treadmillState,
                        onConnect = { vm.startScanTreadmill() },
                        onDisconnect = { vm.disconnectTreadmill() },
                        cooldownRemaining = cooldownRemaining,
                        modifier = Modifier.weight(1f)
                    )
                    CompactStatus(
                        label = "Пульсометр",
                        deviceName = hrName,
                        state = hrState,
                        onConnect = { vm.startScanHeartRate() },
                        onDisconnect = { vm.disconnectHeartRate() },
                        cooldownRemaining = cooldownRemaining,
                        modifier = Modifier.weight(1f)
                    )
                }

                // [ФИКС] Строка текущего сегмента программы (фиксированной высоты,
                // чтобы раскладка не «прыгала» при старте/остановке тренировки).
                SegmentLine(
                    visible = workoutState.running || workoutState.paused,
                    segmentNumber = workoutState.currentSegmentIndex + 1,
                    totalSegments = workoutState.totalSegments,
                    segmentName = workoutState.currentSegmentName,
                    paused = workoutState.paused
                )

                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ControlTile(
                        label = "Скорость",
                        value = "%.1f".format(targetSpeed),
                        unit = "км/ч",
                        enabled = treadmillReady,
                        onMinus = { vm.setSpeed(targetSpeed - speedStep) },
                        onPlus = { vm.setSpeed(targetSpeed + speedStep) },
                        modifier = Modifier.weight(1f)
                    )
                    if (showIncline) {
                        ControlTile(
                            label = "Наклон",
                            value = "${targetIncline.roundToInt()}",
                            unit = "%",
                            enabled = treadmillReady,
                            onMinus = { vm.setIncline(max(0.0, targetIncline.roundToInt() - 1.0)) },
                            onPlus = { vm.setIncline(targetIncline.roundToInt() + 1.0) },
                            onReset = { vm.setIncline(0.0) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp, horizontal = 10.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            MetricBlock(
                                "Пульс",
                                if (hr > 0) "$hr" else "—",
                                "уд/мин",
                                Modifier.weight(1f)
                            )
                            MetricBlock(
                                "Время",
                                formatTime(elapsedSec),
                                "",
                                Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth()) {
                            MetricBlock(
                                "Дистанция",
                                "%.2f".format(distanceKm),
                                "км",
                                Modifier.weight(1f)
                            )
                            MetricBlock(
                                "Калории",
                                "$calories",
                                "ккал",
                                Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Автопульс", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (settings.hrEnabled) "Цель: ${settings.targetHr} уд/мин" else "Выключен",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { onNavigate(Routes.SETTINGS) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text("Настроить", fontSize = 11.sp) }
                        Switch(
                            checked = settings.hrEnabled,
                            onCheckedChange = { vm.setAutoHrEnabled(it) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp).height(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { vm.startTreadmill() },
                        enabled = treadmillReady && !paused,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Старт", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { vm.togglePauseTreadmill() },
                        enabled = treadmillReady,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(0.dp),
                        colors = if (paused)
                            ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        else
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                    ) {
                        Text(
                            if (paused) "Продолжить" else "Пауза",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // [ФИКС] «Стоп» теперь требует удержания 0.8 сек с визуальным прогрессом.
                    // Случайное нажатие в кармане больше не остановит дорожку.
                    HoldToConfirmButton(
                        enabled = treadmillReady,
                        onConfirmed = { vm.stopTreadmill() },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }

    if (showCapsDialog) {
        CapabilitiesDialog(
            deviceName = treadmillName,
            caps = capabilities,
            onDismiss = { showCapsDialog = false }
        )
    }

    if (scanMode != ScanMode.NONE) {
        val filtered = remember(scanResults, scanMode) {
            scanResults.filter {
                if (scanMode == ScanMode.TREADMILL) ble.isTreadmillDevice(it)
                else ble.isHeartRateDevice(it)
            }
        }
        ScanDialog(
            title = if (scanMode == ScanMode.TREADMILL) "Выбор дорожки" else "Выбор пульсометра",
            devices = filtered,
            nameProvider = { ble.safeName(it) },
            onSelect = { r ->
                if (scanMode == ScanMode.TREADMILL) vm.connectTreadmill(r.device)
                else vm.connectHeartRate(r.device)
            },
            onDismiss = { vm.stopScan() }
        )
    }
}

/**
 * [ФИКС] Строка текущего сегмента программы.
 * Всегда занимает 22dp по высоте — даже когда программа не запущена, чтобы нижние
 * карточки (скорость, метрики, кнопки) не «прыгали» при старте/остановке тренировки.
 */
@Composable
private fun SegmentLine(
    visible: Boolean,
    segmentNumber: Int,
    totalSegments: Int,
    segmentName: String,
    paused: Boolean
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (visible) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Сегмент $segmentNumber/$totalSegments",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (segmentName.isNotBlank()) {
                    Text(" · ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        segmentName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (paused) {
                    Text(
                        " · ПАУЗА",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF9A825)
                    )
                }
            }
        }
    }
}

/**
 * [ФИКС] Кнопка с подтверждением удержанием. Отображает круговой прогресс
 * вокруг контента. При отпускании до завершения — сбрасывается.
 */
@Composable
private fun HoldToConfirmButton(
    enabled: Boolean,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdDurationMs: Long = 800L
) {
    var isPressing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(isPressing) {
        if (isPressing && enabled) {
            val stepMs = 20L
            val steps = (holdDurationMs / stepMs).toInt().coerceAtLeast(1)
            progress = 0f
            for (i in 1..steps) {
                delay(stepMs)
                if (!isPressing) return@LaunchedEffect
                progress = i.toFloat() / steps
            }
            if (progress >= 1f) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                progress = 0f
                isPressing = false
                onConfirmed()
            }
        } else {
            progress = 0f
        }
    }

    Box(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isPressing = true
                        tryAwaitRelease()
                        isPressing = false
                    }
                )
            }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = ButtonDefaults.shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Стоп",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
        if (progress > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidth = 3.dp.toPx()
                val inset = strokeWidth / 2
                // Затемнённый фон при удержании — визуальная «заливка»
                drawRoundRect(
                    color = Color(0xFFE53935).copy(alpha = 0.15f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        x = size.height / 2, y = size.height / 2
                    )
                )
                drawArc(
                    color = Color(0xFFE53935),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
private fun CompactStatus(
    label: String,
    deviceName: String?,
    state: BleConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    cooldownRemaining: Int,
    modifier: Modifier = Modifier
) {
    val (dotColor, statusText) = when (state) {
        BleConnectionState.READY -> Color(0xFF2E7D32) to "подключено"
        BleConnectionState.CONNECTING,
        BleConnectionState.CONNECTED,
        BleConnectionState.DISCOVERING -> Color(0xFFF9A825) to "подключение…"
        BleConnectionState.FAILED -> Color(0xFFC62828) to "ошибка"
        BleConnectionState.DISCONNECTED -> Color(0xFF9E9E9E) to "нет"
    }
    val connected = state == BleConnectionState.READY

    Card(modifier = modifier, elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = dotColor, shape = CircleShape, modifier = Modifier.size(7.dp)) {}
                Spacer(Modifier.width(5.dp))
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(1.dp))
            Text(
                statusText,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Всегда резервируем строку под имя устройства, чтобы блок не менял высоту.
            Text(
                deviceName ?: "\u00A0",
                fontSize = 10.sp,
                color = if (deviceName != null)
                    MaterialTheme.colorScheme.primary
                else Color.Transparent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            if (connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) { Text("Откл.", fontSize = 11.sp) }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = cooldownRemaining == 0,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    Text(
                        if (cooldownRemaining > 0) "Ждите ${cooldownRemaining}с" else "Подкл.",
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlTile(
    label: String,
    value: String,
    unit: String,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null
) {
    Card(modifier = modifier, elevation = CardDefaults.cardElevation(2.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label.uppercase(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 1.dp)
            ) {
                RollingText(
                    text = value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = onMinus,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(34.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Меньше", modifier = Modifier.size(16.dp))
                }
                FilledTonalButton(
                    onClick = onPlus,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(34.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Больше", modifier = Modifier.size(16.dp))
                }
            }
            if (onReset != null) {
                TextButton(
                    onClick = onReset,
                    enabled = enabled,
                    modifier = Modifier.height(18.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) { Text("Сбросить", fontSize = 10.sp) }
            } else {
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun MetricBlock(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            RollingText(
                text = value,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun ScanDialog(
    title: String,
    devices: List<ScanResult>,
    nameProvider: (ScanResult) -> String,
    onSelect: (ScanResult) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (devices.isEmpty()) {
                    Text("Поиск устройств…\nУбедитесь, что устройство включено и рядом.")
                } else devices.forEach { r ->
                    TextButton(onClick = { onSelect(r) }) {
                        Text("${nameProvider(r)}  [${r.rssi} dBm]")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun formatTime(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
