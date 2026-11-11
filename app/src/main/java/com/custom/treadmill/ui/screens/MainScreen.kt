package com.custom.treadmill.ui.screens

import android.app.Activity
import android.bluetooth.le.ScanResult
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.R
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
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { 
        val granted = hasBlePermissions(ctx)
        permsGranted = granted
        if (granted) {
            pendingAction?.invoke()
        } else {
            vm.showMessage(ctx.getString(R.string.ble_permission_denied))
        }
        pendingAction = null
    }

    fun requestBleAndRun(action: () -> Unit) {
        if (hasBlePermissions(ctx)) {
            action()
        } else {
            pendingAction = action
            permLauncher.launch(requiredBlePermissions())
        }
    }

    LaunchedEffect(scanMode) {
        if (scanMode != ScanMode.NONE) { delay(15_000); vm.stopScan() }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                Text(
                    stringResource(R.string.app_name),
                    fontSize = 22.sp,
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
                        treadmillName ?: stringResource(R.string.main_no_treadmill),
                        fontSize = 12.sp,
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
                            modifier = Modifier.size(28.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = stringResource(R.string.main_caps_contentdesc),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactStatus(
                        label = stringResource(R.string.main_treadmill_label),
                        deviceName = treadmillName,
                        state = treadmillState,
                        onConnect = { requestBleAndRun { vm.startScanTreadmill() } },
                        onDisconnect = { vm.disconnectTreadmill() },
                        cooldownRemaining = cooldownRemaining,
                        modifier = Modifier.weight(1f)
                    )
                    CompactStatus(
                        label = stringResource(R.string.main_hr_label),
                        deviceName = hrName,
                        state = hrState,
                        onConnect = { requestBleAndRun { vm.startScanHeartRate() } },
                        onDisconnect = { vm.disconnectHeartRate() },
                        cooldownRemaining = cooldownRemaining,
                        modifier = Modifier.weight(1f)
                    )
                }

                SegmentLine(
                    visible = workoutState.running || workoutState.paused,
                    segmentNumber = workoutState.currentSegmentIndex + 1,
                    totalSegments = workoutState.totalSegments,
                    segmentName = workoutState.currentSegmentName,
                    paused = workoutState.paused
                )

                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlTile(
                        label = stringResource(R.string.main_speed),
                        value = "%.1f".format(targetSpeed),
                        unit = stringResource(R.string.unit_kmh),
                        enabled = treadmillReady,
                        onMinus = { vm.setSpeed(targetSpeed - speedStep) },
                        onPlus = { vm.setSpeed(targetSpeed + speedStep) },
                        modifier = Modifier.weight(1f)
                    )
                    if (showIncline) {
                        ControlTile(
                            label = stringResource(R.string.main_incline),
                            value = "${targetIncline.roundToInt()}",
                            unit = stringResource(R.string.unit_percent),
                            enabled = treadmillReady,
                            onMinus = { vm.setIncline(max(0.0, targetIncline.roundToInt() - 1.0)) },
                            onPlus = { vm.setIncline(targetIncline.roundToInt() + 1.0) },
                            onReset = { vm.setIncline(0.0) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(vertical = 10.dp, horizontal = 12.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            MetricBlock(
                                stringResource(R.string.main_hr),
                                if (hr > 0) "$hr" else "—",
                                stringResource(R.string.unit_bpm),
                                Modifier.weight(1f)
                            )
                            MetricBlock(
                                stringResource(R.string.main_time),
                                formatTime(elapsedSec),
                                "",
                                Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            MetricBlock(
                                stringResource(R.string.main_distance),
                                "%.2f".format(distanceKm),
                                stringResource(R.string.unit_km),
                                Modifier.weight(1f)
                            )
                            MetricBlock(
                                stringResource(R.string.main_calories),
                                "$calories",
                                stringResource(R.string.unit_calories),
                                Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.main_autohr_title), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (settings.hrEnabled) stringResource(R.string.main_autohr_target, settings.targetHr)
                                else stringResource(R.string.main_autohr_off),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { onNavigate(Routes.SETTINGS) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text(stringResource(R.string.main_autohr_configure), fontSize = 12.sp) }
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                elevation = CardDefaults.cardElevation(0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.startTreadmill() },
                        enabled = treadmillReady && !paused,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.main_btn_start), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                        onClick = { vm.togglePauseTreadmill() },
                        enabled = treadmillReady,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = if (paused)
                            ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White)
                        else
                            ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Text(
                            if (paused) stringResource(R.string.main_btn_resume)
                            else stringResource(R.string.main_btn_pause),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

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
            title = if (scanMode == ScanMode.TREADMILL)
                stringResource(R.string.scan_title_treadmill)
            else
                stringResource(R.string.scan_title_hr),
            devices = filtered,
            allDevices = scanResults, // <--- ПЕРЕДАЕМ ВСЕ УСТРОЙСТВА ДЛЯ ПЕРЕКЛЮЧАТЕЛЯ
            nameProvider = { ble.safeName(it) },
            onSelect = { r ->
                if (scanMode == ScanMode.TREADMILL) vm.connectTreadmill(r.device)
                else vm.connectHeartRate(r.device)
            },
            onDismiss = { vm.stopScan() }
        )
    }
}

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
            .height(24.dp)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (visible) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.main_segment_progress, segmentNumber, totalSegments),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (segmentName.isNotBlank()) {
                    Text(" · ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        segmentName,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (paused) {
                    Text(
                        " · " + stringResource(R.string.main_segment_paused),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF9A825)
                    )
                }
            }
        }
    }
}

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
    val stopLabel = stringResource(R.string.main_btn_stop)

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
        modifier = modifier.pointerInput(enabled) {
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
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stopLabel,
                    fontSize = 15.sp,
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
        BleConnectionState.READY -> Color(0xFF2E7D32) to stringResource(R.string.main_status_connected)
        BleConnectionState.CONNECTING,
        BleConnectionState.CONNECTED,
        BleConnectionState.DISCOVERING -> Color(0xFFF9A825) to stringResource(R.string.main_status_connecting)
        BleConnectionState.FAILED -> Color(0xFFC62828) to stringResource(R.string.main_status_error)
        BleConnectionState.DISCONNECTED -> Color(0xFF9E9E9E) to stringResource(R.string.main_status_none)
    }
    val connected = state == BleConnectionState.READY

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = dotColor, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                statusText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                deviceName ?: "\u00A0",
                fontSize = 11.sp,
                color = if (deviceName != null)
                    MaterialTheme.colorScheme.primary
                else Color.Transparent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            if (connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(stringResource(R.string.main_btn_disconnect), fontSize = 12.sp) }
            } else {
                FilledTonalButton(
                    onClick = onConnect,
                    enabled = cooldownRemaining == 0,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        if (cooldownRemaining > 0) stringResource(R.string.main_btn_cooldown, cooldownRemaining)
                        else stringResource(R.string.main_btn_connect),
                        fontSize = 12.sp
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
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label.uppercase(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                RollingText(
                    text = value,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(
                    onClick = onMinus,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.more_less), modifier = Modifier.size(18.dp))
                }
                FilledTonalIconButton(
                    onClick = onPlus,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.more_greater), modifier = Modifier.size(18.dp))
                }
            }
            if (onReset != null) {
                TextButton(
                    onClick = onReset,
                    enabled = enabled,
                    modifier = Modifier.height(24.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) { Text(stringResource(R.string.main_reset), fontSize = 11.sp) }
            } else {
                Spacer(Modifier.height(24.dp))
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
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            RollingText(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun ScanDialog(
    title: String,
    devices: List<ScanResult>,
    allDevices: List<ScanResult>,
    nameProvider: (ScanResult) -> String,
    onSelect: (ScanResult) -> Unit,
    onDismiss: () -> Unit
) {
    var showAll by remember { mutableStateOf(false) }
    val display = if (showAll) allDevices else devices

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (showAll) "Все устройства: ${allDevices.size}"
                        else "Похожие на дорожку: ${devices.size}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = showAll,
                        onCheckedChange = { showAll = it }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (display.isEmpty()) {
                    Text(stringResource(R.string.scan_searching))
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        display.forEach { r ->
                            TextButton(
                                onClick = { onSelect(r) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${nameProvider(r)}  [${r.rssi} dBm]",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private fun formatTime(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
