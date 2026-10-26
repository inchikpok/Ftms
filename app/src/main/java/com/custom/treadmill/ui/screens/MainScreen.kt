package com.custom.treadmill.ui.screens

import android.app.Activity
import android.bluetooth.le.ScanResult
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val statusMessage by vm.statusMessage.collectAsState()
    val settings by vm.settings.collectAsState()
    val paused by vm.isPaused.collectAsState()

    val elapsedSec by vm.uiElapsedSec.collectAsState()
    val distanceKm by vm.uiDistanceKm.collectAsState()
    val calories by vm.uiCalories.collectAsState()

    val treadmillReady = treadmillState == BleConnectionState.READY
    val speedStep = settings.speedStepKmh
    val showIncline = !capabilities.isLoaded || capabilities.supportsIncline

    // [ФИКС] Кулдаун сканирования после отключения
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
    val scanBlocked = cooldownRemaining > 0

    var showCapsDialog by remember { mutableStateOf(false) }
    var capsShownFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(treadmillReady, capabilities, treadmillName) {
        if (treadmillReady && capabilities.isLoaded && capsShownFor != treadmillName) {
            showCapsDialog = true
            capsShownFor = treadmillName
        }
    }

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
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { showCapsDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Возможности дорожки",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
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
                        onConnect = { 
                            if (!scanBlocked) vm.startScanTreadmill()
                            else vm.showMessage("Подождите $cooldownRemaining сек")
                        },
                        onDisconnect = { vm.disconnectTreadmill() },
                        cooldownRemaining = cooldownRemaining,
                        modifier = Modifier.weight(1f)
                    )
                    CompactStatus(
                        label = "Пульсометр",
                        deviceName = hrName,
                        state = hrState,
                        onConnect = { 
                            if (!scanBlocked) vm.startScanHeartRate()
                            else vm.showMessage("Подождите $cooldownRemaining сек")
                        },
                        onDisconnect = { vm.disconnectHeartRate() },
                        cooldownRemaining = 0,
                        modifier = Modifier.weight(1f)
                    )
                }

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

            statusMessage?.let { msg ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, modifier = Modifier.weight(1f), fontSize = 12.sp)
                        TextButton(onClick = { vm.clearStatusMessage() }) { Text("OK") }
                    }
                }
            }

            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { vm.startTreadmill() },
                        enabled = treadmillReady && !paused,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Text("Старт", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { vm.togglePauseTreadmill() },
                        enabled = treadmillReady,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp),
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

                    Button(
                        onClick = { vm.stopTreadmill() },
                        enabled = treadmillReady,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Стоп", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
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
            if (connected && deviceName != null) {
                Text(
                    deviceName,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
