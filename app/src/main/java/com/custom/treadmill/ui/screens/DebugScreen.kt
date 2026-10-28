package com.custom.treadmill.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.DebugLog
import com.custom.treadmill.R
import com.custom.treadmill.ble.BleConnectionState
import com.custom.treadmill.ui.viewmodels.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val logs by vm.logs.collectAsState()
    val treadmillState by vm.treadmillState.collectAsState()
    val treadmillName by vm.treadmillName.collectAsState()
    val data by vm.treadmillData.collectAsState()

    val treadmillReady = treadmillState == BleConnectionState.READY

    var hexInput by rememberSaveable { mutableStateOf("02 96 00") }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var hideTreadmillFrames by rememberSaveable { mutableStateOf(true) }
    var hideUiEvents by rememberSaveable { mutableStateOf(true) }

    val listState = rememberLazyListState()

    val visibleLogs = remember(logs, hideTreadmillFrames, hideUiEvents) {
        logs.filter { line ->
            val ftmsOk = !hideTreadmillFrames || !line.contains("<- 00002acd")
            val uiOk = !hideUiEvents || !line.contains("[UI]")
            val navOk = !line.contains("deepLinkIntent") // <--- ДОБАВИТЬ ЭТО
            ftmsOk && uiOk
        }
    }

    LaunchedEffect(visibleLogs.size) {
        if (tab == 0 && visibleLogs.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = DebugLog.exportText()
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }
            savedMessage = ctx.getString(R.string.debug_saved)
        } catch (e: Exception) {
            savedMessage = ctx.getString(R.string.debug_save_error, e.message ?: "")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.clearLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.debug_clear))
                    }
                    IconButton(onClick = {
                        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                        saveLauncher.launch("treadmill_log_$ts.txt")
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.debug_save))
                    }
                    IconButton(onClick = {
                        val content = DebugLog.exportText()
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.debug_share_subject))
                            putExtra(Intent.EXTRA_TEXT, content)
                        }
                        ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.debug_share_chooser)))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.debug_share))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 4.dp)) {

            savedMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(treadmillName ?: stringResource(R.string.debug_no_connection), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(treadmillState.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (treadmillReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.debug_status_format, data.speedKmh, data.inclinePercent, logs.size),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it },
                    placeholder = { Text("02 96 00", fontSize = 14.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    modifier = Modifier.weight(1f).height(54.dp)
                )
                Button(
                    onClick = { vm.sendRawHex(hexInput) },
                    enabled = treadmillReady,
                    modifier = Modifier.height(54.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) { Text(stringResource(R.string.debug_send)) }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabButton(stringResource(R.string.debug_tab_log), tab == 0, Modifier.weight(1f)) { tab = 0 }
                TabButton(stringResource(R.string.debug_tab_reference), tab == 1, Modifier.weight(1f)) { tab = 1 }
                TabButton(stringResource(R.string.debug_tab_constructor), tab == 2, Modifier.weight(1f)) { tab = 2 }
            }

            Spacer(Modifier.height(8.dp))

            when (tab) {
                0 -> LogView(
                    logs = visibleLogs,
                    listState = listState,
                    hideTreadmillFrames = hideTreadmillFrames,
                    hideUiEvents = hideUiEvents,
                    onToggleHide = { hideTreadmillFrames = !hideTreadmillFrames },
                    onToggleUi = { hideUiEvents = !hideUiEvents },
                    modifier = Modifier.weight(1f)
                )
                1 -> CommandReference(
                    treadmillReady = treadmillReady,
                    currentSpeedKmh = data.speedKmh,
                    onSend = { vm.sendRawHex(it) },
                    onStartBelt = { vm.startTreadmill() },
                    onAdjustSpeed = { delta -> vm.setSpeed(data.speedKmh + delta) },
                    modifier = Modifier.weight(1f)
                )
                else -> CommandConstructor(
                    treadmillReady = treadmillReady,
                    onSend = { vm.sendRawHex(it) },
                    onStartBelt = { vm.startTreadmill() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TabButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = modifier) { Text(text, fontSize = 13.sp) }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = modifier) { Text(text, fontSize = 13.sp) }
    }
}

@Composable
private fun LogView(
    logs: List<String>,
    listState: LazyListState,
    hideTreadmillFrames: Boolean,
    hideUiEvents: Boolean,
    onToggleHide: () -> Unit,
    onToggleUi: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.debug_log_title), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("FTMS", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    // Компактный Switch для FTMS
                    Box(Modifier.size(34.dp, 24.dp), contentAlignment = Alignment.Center) {
                        Switch(
                            checked = !hideTreadmillFrames,
                            onCheckedChange = { onToggleHide() },
                            modifier = Modifier.scale(0.7f)
                        )
                    }
                    
                    Spacer(Modifier.width(4.dp))
                    Text("UI", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    // Компактный Switch для UI
                    Box(Modifier.size(34.dp, 24.dp), contentAlignment = Alignment.Center) {
                        Switch(
                            checked = !hideUiEvents,
                            onCheckedChange = { onToggleUi() },
                            modifier = Modifier.scale(0.7f)
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(logs.asReversed()) { line ->
                    Text(
                        line,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = lineColor(line),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

private fun lineColor(line: String): Color = when {
    line.contains("[ERR/") -> Color(0xFFE57373)
    line.contains("[UI]") -> Color(0xFF80DEEA)
    line.contains("[NAV]") -> Color(0xFFCE93D8)
    line.contains("[VM]") -> Color(0xFFFFB74D)
    line.contains("[WM]") -> Color(0xFFBA68C8)
    line.contains("[SVC]") -> Color(0xFF4DD0E1)
    line.contains("<-") -> Color(0xFF4CAF50)
    line.contains("->") -> Color(0xFF64B5F6)
    line.contains("[PROTO]") || line.contains("[SESSION]") -> Color(0xFFFFB300)
    else -> Color(0xFFBDBDBD)
}

private data class CmdEntry(val label: String, val hex: String, val note: String = "")

private fun buildCommandReference(ctx: Context): List<Pair<String, List<CmdEntry>>> {
    val speedsKmh = listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0, 12.0, 15.0, 20.0)
    val speedChange = speedsKmh.map { kmh ->
        val raw = (kmh * 100).roundToInt()
        CmdEntry(ctx.getString(R.string.debug_cmd_speed_label, kmh), "02 %02X %02X".format(raw and 0xFF, (raw shr 8) and 0xFF), ctx.getString(R.string.debug_cmd_speed_note))
    }
    val inclines = listOf(0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 15)
    val inclineCmds = inclines.map { p ->
        val raw = p * 10
        CmdEntry(ctx.getString(R.string.debug_cmd_incline_label, p), "03 %02X %02X".format(raw and 0xFF, (raw shr 8) and 0xFF))
    }
    return listOf(
        ctx.getString(R.string.debug_cmd_group_control) to listOf(
            CmdEntry("Request Control", "00", ctx.getString(R.string.debug_cmd_request_control_note)),
            CmdEntry("Reset", "01", ctx.getString(R.string.debug_cmd_reset_note)),
            CmdEntry("Start / Resume", "07", ctx.getString(R.string.debug_cmd_start_note)),
            CmdEntry(ctx.getString(R.string.debug_cmd_stop), "02 00 00", ctx.getString(R.string.debug_cmd_stop_note)),
            CmdEntry(ctx.getString(R.string.debug_cmd_pause), "08 01", ctx.getString(R.string.debug_cmd_pause_note))
        ),
        ctx.getString(R.string.debug_cmd_group_speed) to speedChange,
        ctx.getString(R.string.debug_cmd_group_incline) to inclineCmds
    )
}

@Composable
private fun CommandReference(treadmillReady: Boolean, currentSpeedKmh: Double, onSend: (String) -> Unit, onStartBelt: () -> Unit, onAdjustSpeed: (Double) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val groups = remember { buildCommandReference(ctx) }
    var lastSent by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        if (!treadmillReady) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(stringResource(R.string.debug_not_connected), Modifier.padding(10.dp), fontSize = 12.sp)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.debug_quick_adjust), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.debug_current_speed, currentSpeedKmh), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { onAdjustSpeed(-1.0) }, enabled = treadmillReady && currentSpeedKmh > 1.0, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("−1", fontSize = 12.sp) }
                        OutlinedButton(onClick = { onAdjustSpeed(-0.5) }, enabled = treadmillReady && currentSpeedKmh > 0.5, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("−0.5", fontSize = 12.sp) }
                        Button(onClick = { onAdjustSpeed(0.5) }, enabled = treadmillReady && currentSpeedKmh > 0.5, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("+0.5", fontSize = 12.sp) }
                        Button(onClick = { onAdjustSpeed(1.0) }, enabled = treadmillReady && currentSpeedKmh > 0.5, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("+1", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onStartBelt, enabled = treadmillReady, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.debug_start_treadmill))
                    }
                }
            }
        }
        groups.forEach { (title, commands) ->
            item { Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
            items(commands) { cmd ->
                Card(Modifier.fillMaxWidth().clickable(enabled = treadmillReady) { onSend(cmd.hex); lastSent = "${cmd.label} (${cmd.hex})" }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cmd.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (cmd.note.isNotBlank()) Text(cmd.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(cmd.hex, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            lastSent?.let {
                Card(Modifier.fillMaxWidth().padding(top = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(stringResource(R.string.debug_last_sent, it), Modifier.padding(10.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun CommandConstructor(treadmillReady: Boolean, onSend: (String) -> Unit, onStartBelt: () -> Unit, modifier: Modifier = Modifier) {
    var speedStr by rememberSaveable { mutableStateOf("3") }
    var inclineStr by rememberSaveable { mutableStateOf("2") }
    var lastSent by remember { mutableStateOf<String?>(null) }
    val speed = speedStr.replace(',', '.').toDoubleOrNull() ?: 0.0
    val incline = inclineStr.replace(',', '.').toDoubleOrNull() ?: 0.0

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.debug_speed), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = speedStr, onValueChange = { speedStr = it }, label = { Text(stringResource(R.string.unit_kmh)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                val raw = (speed * 100).roundToInt().coerceIn(0, 65535)
                val changeHex = "02 %02X %02X".format(raw and 0xFF, (raw shr 8) and 0xFF)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { onSend(changeHex); lastSent = "speed=$speed ($changeHex)" }, enabled = treadmillReady && speed > 0, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.debug_apply), fontSize = 12.sp) }
                    OutlinedButton(onClick = { onSend("02 00 00"); lastSent = "стоп (02 00 00)" }, enabled = treadmillReady, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.debug_stop), fontSize = 12.sp) }
                }
                Text(changeHex, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.debug_incline), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = inclineStr, onValueChange = { inclineStr = it }, label = { Text(stringResource(R.string.unit_percent)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                val raw = (incline * 10).roundToInt().coerceIn(0, 32767)
                val hex = "03 %02X %02X".format(raw and 0xFF, (raw shr 8) and 0xFF)
                Button(onClick = { onSend(hex); lastSent = "incline=$incline ($hex)" }, enabled = treadmillReady, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.debug_send_incline), fontSize = 13.sp) }
                Text(hex, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        lastSent?.let {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text(stringResource(R.string.debug_last_sent, it), Modifier.padding(10.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
