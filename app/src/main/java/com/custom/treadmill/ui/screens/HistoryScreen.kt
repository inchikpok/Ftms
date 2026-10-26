package com.custom.treadmill.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.TreadmillApp
import com.custom.treadmill.data.database.WorkoutLogEntity
import com.custom.treadmill.logic.HeartRateZoneCalculator
import com.custom.treadmill.ui.components.HeartRateChart
import com.custom.treadmill.ui.components.HrPoint
import com.custom.treadmill.ui.components.ZoneLegend
import com.custom.treadmill.ui.viewmodels.ProgramViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        items(logs, key = { it.id }) { log ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(fmt.format(Date(log.dateMillis)), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Программа: ${log.programName}", fontSize = 13.sp)
                    Text("Время: ${log.durationSec / 60} мин ${log.durationSec % 60} сек", fontSize = 13.sp)
                    Text("Дистанция: %.2f км".format(log.distanceKm), fontSize = 13.sp)
                    Text("Калории: ${log.calories}", fontSize = 13.sp)
                    if (log.avgHeartRate > 0) {
                        Text(
                            "Пульс: ср. ${log.avgHeartRate}, макс ${log.maxHeartRate} уд/мин",
                            fontSize = 13.sp,
                            color = Color(0xFFC62828),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text("Средняя скорость: %.1f км/ч".format(log.avgSpeedKmh), fontSize = 13.sp)
                }
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
        
        ChartCard(
            title = "Средний пульс",
            subtitle = "${withHr.size} тренировок",
            points = withHr.map { HrPoint(it.dateMillis, it.avgHeartRate) },
            zoneBounds = zoneBounds
        )
        Spacer(Modifier.height(12.dp))
        
        ChartCard(
            title = "Максимальный пульс",
            subtitle = "Пик за тренировку",
            points = withHr.map { HrPoint(it.dateMillis, it.maxHeartRate) },
            zoneBounds = zoneBounds
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

@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    points: List<HrPoint>,
    zoneBounds: List<Int>
) {
    Card(
        Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            HeartRateChart(
                points = points,
                targetHr = 0,
                zoneBounds = zoneBounds,
                showTargetLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            )
            
            Spacer(Modifier.height(12.dp))
            
            // Компактная легенда
            ZoneLegend(zoneBounds)
        }
    }
}

private fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}ч ${m}м" else "${m}м"
}
