package com.custom.treadmill.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val template = remember { buildTemplate() }
    val instructions = remember { buildInstructions() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Назад")
                }
                Spacer(Modifier.weight(1f))
                Text("О программе", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Treadmill Control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("v2.2 — управление беговой дорожкой по BLE (FTMS)", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Универсальное приложение для любых беговых дорожек, поддерживающих стандарт FTMS (Fitness Machine Service, Bluetooth SIG). Работает полностью офлайн. Разрешение INTERNET намеренно не запрашивается.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            InfoSection("Совместимость", listOf(
                "Протокол: FTMS (стандарт Bluetooth SIG)",
                "Любая дорожка с поддержкой FTMS",
                "Например: FitShow, Horizon, Bowflex, Sole, NordicTrack",
                "Пульсометры: стандарт HR Service (0x180D)",
                "Возможности дорожки (скорость, наклон) определяются автоматически"
            ))

            InfoSection("Возможности", listOf(
                "Автоопределение функций дорожки по FTMS Feature",
                "Подключение по BLE, автопереподключение",
                "Нагрудный пульсометр + график по зонам",
                "Авторегулировка скорости по пульсу",
                "Программы тренировок (ручные и автоматические)",
                "Генератор тренировок по профилю (вес, возраст, уровень)",
                "Пульсовые зоны по формуле Карвонена",
                "Голосовые подсказки с настройкой фраз (TTS)",
                "Работа в фоне (Foreground Service)",
                "Журнал тренировок (Room)",
                "Debug-экран с полным логом BLE"
            ))

            InfoSection("Стек", listOf(
                "Kotlin 1.9.22 · JVM 17",
                "Jetpack Compose · Material 3",
                "Room 2.6.1 · kotlinx.serialization",
                "minSdk 21 · targetSdk 34 · AGP 8.2.2"
            ))

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Создать свою тренировку",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Скопируйте шаблон, отредактируйте в любом блокноте, скопируйте обратно и вставьте в «Программы → Из буфера». Никаких файлов сохранять не нужно.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                copyToClipboard(ctx, "workout_template.json", template)
                                Toast.makeText(ctx, "Шаблон JSON скопирован", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Шаблон", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(ctx, "workout_instructions", instructions)
                                Toast.makeText(ctx, "Инструкция скопирована", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Инструкция", fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = template,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            Text(
                "Сделано для себя. Работает офлайн.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun InfoSection(title: String, items: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            items.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun buildTemplate(): String = """
{
  "name": "Моя тренировка",
  "description": "",
  "segments": [
    {
      "name": "Разминка",
      "durationSec": 300,
      "speedKmh": 4.0,
      "inclinePercent": 0.0
    },
    {
      "name": "Бег",
      "durationSec": 600,
      "speedKmh": 8.0,
      "inclinePercent": 1.0
    },
    {
      "name": "Заминка",
      "durationSec": 300,
      "speedKmh": 4.0,
      "inclinePercent": 0.0
    }
  ]
}
""".trimIndent()

private fun buildInstructions(): String = """
КАК СОЗДАТЬ СВОЮ ТРЕНИРОВКУ

1. Нажмите "Шаблон" — JSON скопируется в буфер
2. Откройте любой блокнот (Заметки, Google Keep, любой)
3. Вставьте шаблон
4. Отредактируйте:
   - "name" — название программы
   - "description" — описание (необязательно, оставьте "")
   - "segments" — список сегментов

   Поля каждого сегмента:
   - "name" — название сегмента (строка)
   - "durationSec" — длительность в СЕКУНДАХ (300 = 5 мин)
   - "speedKmh" — скорость в км/ч
   - "inclinePercent" — наклон в %

5. Скопируйте весь текст из блокнота
6. В приложении откройте "Программы" → "Из буфера"
7. Готово — программа появится в списке

ВАЖНО:
- Не удаляйте кавычки и запятые
- Каждый сегмент в { }
- Между сегментами запятая, после последнего — нет
- Можно использовать и целые числа: 300 вместо 300.0
- Скорость и наклон ограничены возможностями вашей дорожки

ПРИМЕР — интервалы 5×1мин:
{
  "name": "Интервалы 5x1",
  "description": "5 быстрых отрезков по 1 мин",
  "segments": [
    {"name": "Разминка", "durationSec": 300, "speedKmh": 5.0, "inclinePercent": 0.0},
    {"name": "Интервал 1", "durationSec": 60, "speedKmh": 12.0, "inclinePercent": 1.0},
    {"name": "Отдых 1", "durationSec": 90, "speedKmh": 6.0, "inclinePercent": 0.0},
    {"name": "Интервал 2", "durationSec": 60, "speedKmh": 12.0, "inclinePercent": 1.0},
    {"name": "Отдых 2", "durationSec": 90, "speedKmh": 6.0, "inclinePercent": 0.0},
    {"name": "Интервал 3", "durationSec": 60, "speedKmh": 12.0, "inclinePercent": 1.0},
    {"name": "Отдых 3", "durationSec": 90, "speedKmh": 6.0, "inclinePercent": 0.0},
    {"name": "Интервал 4", "durationSec": 60, "speedKmh": 12.0, "inclinePercent": 1.0},
    {"name": "Отдых 4", "durationSec": 90, "speedKmh": 6.0, "inclinePercent": 0.0},
    {"name": "Интервал 5", "durationSec": 60, "speedKmh": 12.0, "inclinePercent": 1.0},
    {"name": "Заминка", "durationSec": 300, "speedKmh": 4.0, "inclinePercent": 0.0}
  ]
}
""".trimIndent()
