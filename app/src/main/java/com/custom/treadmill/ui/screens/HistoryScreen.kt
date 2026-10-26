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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.TreadmillApp
import com.custom.treadmill.data.database.WorkoutLogEntity
import com.custom.treadmill.logic.HeartRateZoneCalculator
import com.custom.treadmill.ui.components.HeartRateChart
import com.custom.treadmill.ui.components.HrPoint
import com.custom.treadmill.ui.viewmodels.ProgramViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Общие цвета и названия зон
private val ZoneColors = listOf(
    Color(0xFF64B5F6), // Z1
    Color(0xFF81C784), // Z2
    Color(0xFFFFD54F), // Z3
    Color(0xFFFFB74D), // Z4
    Color(0xFFE57373)  // Z5
)
private val ZoneNames = listOf("Z1 Восстановление", "Z2 Лёгкая", "Z3 Аэробная", "Z4 Порог", "Z5 Максимум")

@Composable
fun HistoryScreen(programVm: ProgramViewModel, onBack: (() -> Unit)? = null) {
    val logs by programVm.logs.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.height(12.dp))
            if (onBack != null) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(4.dp)) {
                    Text("← Назад")
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "Журнал тренировок",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Загрузить демо-данные") },
                        onClick = {
                            menuOpen = false
                            programVm.seedDemoLogs()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Очистить журнал", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            confirmClear = true
                        }
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TabChip("Список", tab == 0) { tab = 0 }
            TabChip("Статистика", tab == 1) { tab = 1 }
        }

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Журнал пуст", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Тренируйтесь с приложением или\n" +
                                "загрузите демо-данные через меню ⋮ справа",
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
            title = { Text("Очистить журнал?") },
            text = { Text("Будут удалены все записи о тренировках. Действие необратимо.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    programVm.clearLogs()
                }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) { Text(text, fontSize = 14.sp) }
    } else {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) { Text(text, fontSize = 14.sp) }
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
            Text("Всего: ${logs.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                expandedIds = if (allExpanded) emptySet() else logs.map { it.id }.toSet()
            }) {
                Text(if (allExpanded) "Свернуть все" else "Развернуть все", fontSize = 13.sp)
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
                    onToggle = {
                        expandedIds = if (isExpanded) expandedIds - log.id else expandedIds + log.id
                    },
                    zoneBounds = zoneBounds
                )
            }
        }
    }
}

@Composable
private fun CompactLogCard(
    log: WorkoutLogEntity,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    zoneBounds: List<Int>
) {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    Card(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(12.dp).animateContentSize()) {
            // Шапка карточки
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        fmt.format(Date(log.dateMillis)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        log.programName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (log.avgHeartRate > 0) {
                    Surface(
                        color = Color(0xFFE57373).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFC62828), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${log.avgHeartRate}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC62828)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            // Базовые метрики (Всегда видны)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CompactMetric(Icons.Default.Timer, formatDuration(log.durationSec))
                CompactMetric(Icons.Default.DirectionsRun, "%.2f км".format(log.distanceKm))
                CompactMetric(Icons.Default.LocalFireDepartment, "${log.calories} ккал")
            }

            // Спойлер (Доп. метрики + Детализация зон)
            AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    Spacer(Modifier.height(10.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        CompactMetric(Icons.Default.Speed, "%.1f км/ч".format(log.avgSpeedKmh), "ср. скорость")
                        CompactMetric(Icons.Default.FavoriteBorder, if (log.maxHeartRate > 0) "${log.maxHeartRate} уд/мин" else "—", "макс. пульс")
                    }

                    if (log.avgHeartRate > 0 && zoneBounds.size == 5) {
                        Spacer(Modifier.height(12.dp))
                        val zoneDurations = remember(log, zoneBounds) {
                            estimateZoneDurations(log.durationSec, log.avgHeartRate, log.maxHeartRate, zoneBounds)
                        }
                        val totalSec = zoneDurations.sum().coerceAtLeast(1L)

                        Text("Пульсовые зоны", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        
                        // Мини-бар распределения
                        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
                            zoneDurations.forEachIndexed { i, sec ->
                                if (sec > 0) {
                                    Box(Modifier.weight(sec.toFloat()).fillMaxHeight().background(ZoneColors[i]))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        // Детальный список
                        zoneDurations.forEachIndexed { i, sec ->
                            if (sec > 0) {
                                val pct = (sec.toFloat() / totalSec) * 100f
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).background(ZoneColors[i], CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(ZoneNames[i], fontSize = 12.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
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
private fun CompactMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            if (label != null) {
                Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatsView(logs: List<WorkoutLogEntity>) {
    val withHr = remember(logs) {
        logs.filter { it.avgHeartRate > 0 }.sortedBy { it.dateMillis }
    }

    val settings = TreadmillApp.instance.settingsStore.settings.value
    val maxHr = HeartRateZoneCalculator.getEffectiveMaxHr(settings)
    val zoneBounds = HeartRateZoneCalculator.getZoneBounds(maxHr, settings.activeProfile.restingHr)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        SummaryCard(logs)

        if (withHr.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Нет данных пульса", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Для отображения статистики тренируйтесь с подключённым пульсометром.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
        // [ФИКС] Оставили только график среднего пульса. График максимального пульса убран.
        ChartCard(
            title = "Средний пульс",
            subtitle = "${withHr.size} тренировок",
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Общая сводка", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatBlock("Тренировок", "${logs.size}", Modifier.weight(1f))
                StatBlock("Время", formatDuration(totalSec), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatBlock("Дистанция", "%.1f км".format(totalKm), Modifier.weight(1f))
                StatBlock("Калории", "$totalKcal", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Пульс", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatBlock("Средний", "$avgAll", Modifier.weight(1f))
                StatBlock("Максимум", "$maxAll", Modifier.weight(1f))
                StatBlock("Лучший ср.", "$minAll", Modifier.weight(1f))
            }

            if (trend != null) {
                Spacer(Modifier.height(12.dp))
                val (diff, improved) = trend
                val color = if (improved) Color(0xFF2E7D32) else Color(0xFFC62828)
                val arrow = if (diff < 0) "↓" else if (diff > 0) "↑" else "→"
                val sign = if (diff > 0) "+" else ""
                val message = when {
                    diff < -2 -> "Пульс снижается — форма растёт"
                    diff > 2 -> "Пульс растёт — возможно, накопилась усталость"
                    else -> "Пульс стабилен"
                }
                Surface(
                    color = color.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(arrow, fontSize = 22.sp, color = color, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Тренд: $sign$diff уд/мин",
                                color = color,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(message, fontSize = 12.sp, color = color)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Для оценки тренда нужно минимум 4 тренировки с пульсометром.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                val est = estimateZoneDurations(log.durationSec, log.avgHeartRate, log.maxHeartRate, zoneBounds)
                for (i in 0..4) {
                    durations[i] += est[i]
                }
            }
        }
        val total = durations.sum().coerceAtLeast(1L)
        durations.map { ZoneStat(it, (it.toFloat() / total) * 100f) }
    }
    val totalSec = stats.sumOf { it.seconds }

    Card(
        Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Время в пульсовых зонах", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Всего: ${formatZoneDuration(totalSec)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Горизонтальный стеклянный бар
            Row(
                Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))
            ) {
                stats.forEachIndexed { i, stat ->
                    if (stat.percent > 0f) {
                        Box(
                            Modifier.weight(stat.percent).fillMaxHeight().background(ZoneColors[i])
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Легенда: цвет · название · время · процент
            stats.forEachIndexed { i, stat ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).background(ZoneColors[i], CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ZoneNames[i],
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        formatZoneDuration(stat.seconds),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (stat.seconds > 0) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.End
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%.1f%%".format(stat.percent),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(Modifier.height(6.dp))
            Text(
                "Границы зон (уд/мин): ${zoneBounds.joinToString(" / ")}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Расчёт на основе аппроксимации средней и максимальной ЧСС",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    points: List<HrPoint>
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(Modifier.padding(12.dp)) {
                HeartRateChart(
                    points = points,
                    targetHr = 0,
                    zoneBounds = emptyList(), // [ФИКС] Убрали фоновые зоны для графика истории
                    showTargetLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }
        }
    }
}

// ---------- helpers ----------

private fun estimateZoneDurations(
    durationSec: Int,
    avgHr: Int,
    maxHr: Int,
    zoneBounds: List<Int>
): LongArray {
    if (avgHr <= 0 || durationSec <= 0 || zoneBounds.size != 5) return LongArray(5)
    val safeMax = maxHr.coerceAtLeast(avgHr)
    
    val centers = doubleArrayOf(
        (70.0 + zoneBounds[0]) / 2.0,
        (zoneBounds[0] + zoneBounds[1]) / 2.0,
        (zoneBounds[1] + zoneBounds[2]) / 2.0,
        (zoneBounds[2] + zoneBounds[3]) / 2.0,
        (zoneBounds[3] + 200.0) / 2.0
    )
    
    val sigma = ((safeMax - avgHr).coerceAtLeast(10) / 1.5).coerceAtLeast(8.0)
    val variance2 = 2.0 * sigma * sigma
    
    val weights = DoubleArray(5) { i ->
        val dist = centers[i] - avgHr
        val base = kotlin.math.exp(-(dist * dist) / variance2)
        val isPeakZone = (i == 4 && safeMax >= zoneBounds[3]) ||
                         (i == 3 && safeMax >= zoneBounds[2] && safeMax < zoneBounds[3]) ||
                         (i == 2 && safeMax >= zoneBounds[1] && safeMax < zoneBounds[2])
        if (isPeakZone) base + 0.4 else base
    }
    
    val sumW = weights.sum()
    if (sumW == 0.0) return LongArray(5).also { it[0] = durationSec.toLong() }
    
    val result = LongArray(5)
    var remaining = durationSec.toLong()
    for (i in 0..4) {
        val sec = (durationSec * weights[i] / sumW).toLong()
        result[i] = sec
        remaining -= sec
    }
    if (remaining > 0) {
        val maxIdx = weights.indices.maxByOrNull { weights[it] } ?: 0
        result[maxIdx] += remaining
    }
    return result
}

private fun formatZoneDuration(sec: Long): String {
    if (sec <= 0L) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}ч ${m}м"
        h > 0 -> "${h}ч"
        m > 0 -> "${m}м"
        else -> "${sec}с"
    }
}

private fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}ч ${m}м" else "${m}м"
}
