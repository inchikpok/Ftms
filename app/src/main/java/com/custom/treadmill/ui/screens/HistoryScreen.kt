package com.custom.treadmill.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.R
import com.custom.treadmill.TreadmillApp
import com.custom.treadmill.data.database.HrSamplesCodec
import com.custom.treadmill.data.database.WorkoutLogEntity
import com.custom.treadmill.logic.HeartRateZoneCalculator
import com.custom.treadmill.ui.components.HeartRateChart
import com.custom.treadmill.ui.components.HrPoint
import com.custom.treadmill.ui.components.HrSparkline
import com.custom.treadmill.ui.viewmodels.ProgramViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ZoneColors = listOf(
    Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFD54F), Color(0xFFFFB74D), Color(0xFFE57373)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(programVm: ProgramViewModel, onBack: (() -> Unit)? = null) {
    val logs by programVm.logs.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDemo by remember { mutableStateOf(false) }
    var showHrInfo by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.height(12.dp))
            if (onBack != null) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(4.dp)) {
                    Text("← " + stringResource(R.string.back))
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(R.string.history_title),
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_menu_load_demo)) },
                        onClick = { menuOpen = false; confirmDemo = true }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_menu_clear), color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; confirmClear = true }
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.history_tab_list))
                }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.history_tab_stats))
                }
            }
            if (tab == 1) {
                IconButton(onClick = { showHrInfo = true }) {
                    Icon(Icons.Default.Info, contentDescription = stringResource(R.string.history_hr_info_contentdesc), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.history_empty_title), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.history_empty_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (tab == 0) {
            LogsList(logs)
        } else {
            StatsView(logs)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_dialog_title)) },
            text = { Text(stringResource(R.string.history_clear_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; programVm.clearLogs() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.history_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (confirmDemo) {
        AlertDialog(
            onDismissRequest = { confirmDemo = false },
            title = { Text(stringResource(R.string.history_menu_load_demo)) },
            text = { Text("Демонстрационные тренировки будут добавлены в журнал. Ваши реальные записи останутся на месте. (Демо будут помечены префиксом «demo».)") },
            confirmButton = {
                TextButton(onClick = { confirmDemo = false; programVm.addDemoLogs() }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmDemo = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showHrInfo) {
        val withHr = logs.count { it.avgHeartRate > 0 }
        val withoutHr = logs.size - withHr
        AlertDialog(
            onDismissRequest = { showHrInfo = false },
            icon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFC62828)) },
            title = { Text(stringResource(R.string.history_hr_info_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.history_hr_info_total, logs.size), fontSize = 13.sp)
                    Text(stringResource(R.string.history_hr_info_with_hr, withHr), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (withoutHr > 0) {
                        Text(stringResource(R.string.history_hr_info_without_hr, withoutHr), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.history_hr_info_explain), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.history_hr_info_zones_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showHrInfo = false }) { Text(stringResource(R.string.history_hr_info_ok)) } }
        )
    }
}

@Composable
private fun LogsList(logs: List<WorkoutLogEntity>) {
    val settings = TreadmillApp.instance.settingsStore.settings.value
    val maxHr = HeartRateZoneCalculator.getEffectiveMaxHr(settings)
    val zoneBounds = HeartRateZoneCalculator.getZoneBounds(maxHr, settings.activeProfile.restingHr)

    var expandedIds by remember(logs) { mutableStateOf(setOf<Long>()) }
    val allExpanded = logs.isNotEmpty() && logs.all { expandedIds.contains(it.id) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.history_total, logs.size), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expandedIds = if (allExpanded) emptySet() else logs.map { it.id }.toSet() }) {
                Text(if (allExpanded) stringResource(R.string.history_collapse_all) else stringResource(R.string.history_expand_all), fontSize = 13.sp)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(logs, key = { it.id }) { log ->
                val isExpanded = expandedIds.contains(log.id)
                CompactLogCard(
                    log = log,
                    isExpanded = isExpanded,
                    onToggle = { expandedIds = if (isExpanded) expandedIds - log.id else expandedIds + log.id },
                    zoneBounds = zoneBounds
                )
            }
        }
    }
}

@Composable
private fun CompactLogCard(log: WorkoutLogEntity, isExpanded: Boolean, onToggle: () -> Unit, zoneBounds: List<Int>) {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val isManual = log.programName.isBlank() || log.programName == "Ручная тренировка" || log.programName == "Manual workout"
    val displayName = if (isManual) stringResource(R.string.manual_workout) else log.programName

    Card(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fmt.format(Date(log.dateMillis)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (log.avgHeartRate > 0) {
                    Surface(color = Color(0xFFE57373).copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFC62828), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${log.avgHeartRate}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CompactMetric(Icons.Default.Timer, formatDuration(log.durationSec))
                CompactMetric(Icons.Default.DirectionsRun, "%.2f ${stringResource(R.string.unit_km)}".format(log.distanceKm))
                CompactMetric(Icons.Default.LocalFireDepartment, "${log.calories} ${stringResource(R.string.unit_calories)}")
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.padding(top = 14.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        CompactMetric(Icons.Default.Speed, "%.1f ${stringResource(R.string.unit_kmh)}".format(log.avgSpeedKmh), stringResource(R.string.history_avg_speed))
                        CompactMetric(Icons.Default.FavoriteBorder, if (log.maxHeartRate > 0) "${log.maxHeartRate} ${stringResource(R.string.unit_bpm)}" else "—", stringResource(R.string.history_max_hr))
                    }

                    val samples = remember(log.hrSamples) { HrSamplesCodec.decode(log.hrSamples) }

                    if (log.avgHeartRate > 0) {
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.history_hr_in_workout), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (samples.isNotEmpty()) {
                                val mn = samples.minOf { it.second }
                                val mx = samples.maxOf { it.second }
                                Text(stringResource(R.string.history_hr_minmax, mn, mx), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (samples.isNotEmpty()) {
                            HrSparkline(samples = samples, durationSec = log.durationSec, modifier = Modifier.fillMaxWidth().height(56.dp))
                        } else {
                            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.hr_no_data), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // [ФИКС] Показываем зоны ТОЛЬКО если есть реальные данные пульса (samples.isNotEmpty())
                    if (samples.isNotEmpty() && zoneBounds.size == 5) {
                        Spacer(Modifier.height(14.dp))
                        val zoneDurations = remember(log, zoneBounds) { computeZoneDurations(log, zoneBounds) }
                        val totalSec = zoneDurations.sum().coerceAtLeast(1L)

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.history_hr_zones), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))

                        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                            zoneDurations.forEachIndexed { i, sec ->
                                if (sec > 0) Box(Modifier.weight(sec.toFloat()).fillMaxHeight().background(ZoneColors[i]))
                            }
                        }
                        Spacer(Modifier.height(10.dp))

                        zoneDurations.forEachIndexed { i, sec ->
                            if (sec > 0) {
                                val pct = (sec.toFloat() / totalSec) * 100f
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(ZoneColors[i], CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(getZoneName(i), fontSize = 12.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                    Text(formatZoneDuration(sec), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp), textAlign = TextAlign.End)
                                    Spacer(Modifier.width(8.dp))
                                    Text("%.0f%%".format(pct), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMetric(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (label != null) Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatsView(logs: List<WorkoutLogEntity>) {
    val withHr = remember(logs) { logs.filter { it.avgHeartRate > 0 }.sortedBy { it.dateMillis } }
    val settings = TreadmillApp.instance.settingsStore.settings.value
    val maxHr = HeartRateZoneCalculator.getEffectiveMaxHr(settings)
    val zoneBounds = HeartRateZoneCalculator.getZoneBounds(maxHr, settings.activeProfile.restingHr)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(4.dp))
        SummaryCard(logs)

        if (withHr.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.history_no_hr_title), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.history_no_hr_text), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        HeartSummaryCard(withHr)

        Spacer(Modifier.height(12.dp))
        ZoneStatsCard(withHr, zoneBounds)

        Spacer(Modifier.height(16.dp))
        ChartCard(
            title = stringResource(R.string.history_chart_avg_title),
            subtitle = stringResource(R.string.history_chart_workouts_count, withHr.size),
            points = withHr.map { HrPoint(it.dateMillis, it.avgHeartRate) }
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SummaryCard(logs: List<WorkoutLogEntity>) {
    val totalSec = logs.sumOf { it.durationSec }
    val totalKm = logs.sumOf { it.distanceKm }
    val totalKcal = logs.sumOf { it.calories }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.history_summary_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                StatBlock(stringResource(R.string.history_summary_workouts), "${logs.size}", Modifier.weight(1f))
                StatBlock(stringResource(R.string.history_summary_time), formatDuration(totalSec), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                StatBlock(stringResource(R.string.history_summary_distance), "%.1f ${stringResource(R.string.unit_km)}".format(totalKm), Modifier.weight(1f))
                StatBlock(stringResource(R.string.history_summary_calories), "$totalKcal", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeartSummaryCard(logs: List<WorkoutLogEntity>) {
    val avgAll = logs.map { it.avgHeartRate }.average().toInt()
    val maxAll = logs.maxOf { it.maxHeartRate }
    val minAll = logs.minOf { it.avgHeartRate }

    val trend: Pair<Int, Boolean>? = if (logs.size >= 4) {
        val half = logs.size / 2
        val first = logs.take(half).map { it.avgHeartRate }.average()
        val second = logs.drop(half).map { it.avgHeartRate }.average()
        val diff = (second - first).toInt()
        diff to (diff <= 0)
    } else null

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.history_hr_card_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                StatBlock(stringResource(R.string.history_hr_avg), "$avgAll", Modifier.weight(1f))
                StatBlock(stringResource(R.string.history_hr_max), "$maxAll", Modifier.weight(1f))
                StatBlock(stringResource(R.string.history_hr_best), "$minAll", Modifier.weight(1f))
            }

            if (trend != null) {
                Spacer(Modifier.height(14.dp))
                val (diff, improved) = trend
                val color = if (improved) Color(0xFF2E7D32) else Color(0xFFC62828)
                val arrow = if (diff < 0) "↓" else if (diff > 0) "↑" else "→"
                val sign = if (diff > 0) "+" else ""
                val message = when {
                    diff < -2 -> stringResource(R.string.history_trend_falling)
                    diff > 2 -> stringResource(R.string.history_trend_rising)
                    else -> stringResource(R.string.history_trend_stable)
                }
                Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(arrow, fontSize = 24.sp, color = color, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.history_trend, sign, diff), color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(message, fontSize = 12.sp, color = color)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.history_trend_need_more), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class ZoneStat(val seconds: Long, val percent: Float)

@Composable
private fun ZoneStatsCard(logs: List<WorkoutLogEntity>, zoneBounds: List<Int>) {
    val stats = remember(logs, zoneBounds) {
        if (logs.isEmpty() || zoneBounds.size != 5) return@remember List(5) { ZoneStat(0L, 0f) }
        val durations = LongArray(5)
        logs.forEach { log ->
            if (log.avgHeartRate > 0 && log.durationSec > 0) {
                val est = computeZoneDurations(log, zoneBounds)
                for (i in 0..4) durations[i] += est[i]
            }
        }
        val total = durations.sum().coerceAtLeast(1L)
        durations.map { ZoneStat(it, (it.toFloat() / total) * 100f) }
    }
    val totalSec = stats.sumOf { it.seconds }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.history_zones_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.history_zones_total, formatZoneDuration(totalSec)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
                stats.forEachIndexed { i, stat ->
                    if (stat.percent > 0f) Box(Modifier.weight(stat.percent).fillMaxHeight().background(ZoneColors[i]))
                }
            }

            Spacer(Modifier.height(16.dp))

            stats.forEachIndexed { i, stat ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(ZoneColors[i], CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(getZoneName(i), fontSize = 13.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        formatZoneDuration(stat.seconds),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (stat.seconds > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.End
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("%.1f%%".format(stat.percent), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.history_zones_bounds, zoneBounds.joinToString(" / ")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.history_zones_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, points: List<HrPoint>) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(Modifier.padding(12.dp)) {
                HeartRateChart(points = points, targetHr = 0, zoneBounds = emptyList(), showTargetLine = false, modifier = Modifier.fillMaxWidth().height(180.dp))
            }
        }
    }
}

@Composable
private fun getZoneName(index: Int): String {
    return when (index) {
        0 -> stringResource(R.string.history_zone_z1)
        1 -> stringResource(R.string.history_zone_z2)
        2 -> stringResource(R.string.history_zone_z3)
        3 -> stringResource(R.string.history_zone_z4)
        else -> stringResource(R.string.history_zone_z5)
    }
}

private fun computeZoneDurations(log: WorkoutLogEntity, zoneBounds: List<Int>): LongArray {
    if (zoneBounds.size != 5 || log.durationSec <= 0) return LongArray(5)
    val samples = HrSamplesCodec.decode(log.hrSamples)
    if (samples.isEmpty()) return LongArray(5) // [ФИКС] Если нет данных, возвращаем нули (без аппроксимации в UI)

    fun zoneOf(bpm: Int): Int = when {
        bpm < zoneBounds[0] -> 0
        bpm < zoneBounds[1] -> 1
        bpm < zoneBounds[2] -> 2
        bpm < zoneBounds[3] -> 3
        else -> 4
    }

    val durations = LongArray(5)
    val endT = log.durationSec

    for (i in 0 until samples.size - 1) {
        val t0 = samples[i].first
        val bpm0 = samples[i].second
        val t1 = samples[i + 1].first
        if (t0 >= endT) break
        val dt = (t1 - t0).coerceAtLeast(0)
        if (dt <= 0) continue
        durations[zoneOf(bpm0)] += dt.coerceAtMost(10).toLong()
    }
    val lastT = samples.last().first
    val lastBpm = samples.last().second
    val tail = (endT - lastT.coerceAtLeast(0)).coerceAtLeast(0).coerceAtMost(10)
    if (tail > 0) durations[zoneOf(lastBpm)] += tail.toLong()

    return durations
}

@Composable
private fun formatZoneDuration(sec: Long): String {
    if (sec <= 0L) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 && m > 0 -> stringResource(R.string.history_duration_hm, h.toInt(), m.toInt())
        h > 0 -> stringResource(R.string.history_duration_hm, h.toInt(), 0)
        m > 0 -> stringResource(R.string.history_duration_m, m.toInt())
        else -> stringResource(R.string.history_duration_s, sec.toInt())
    }
}

@Composable
private fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) stringResource(R.string.history_duration_hm, h, m) else stringResource(R.string.history_duration_m, m)
}
