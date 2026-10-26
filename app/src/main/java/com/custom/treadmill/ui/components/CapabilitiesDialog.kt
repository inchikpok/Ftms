package com.custom.treadmill.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.ble.TreadmillCapabilities

@Composable
fun CapabilitiesDialog(
    deviceName: String?,
    caps: TreadmillCapabilities,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Возможности дорожки", fontWeight = FontWeight.Bold)
                if (!deviceName.isNullOrBlank()) {
                    Text(deviceName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!caps.isLoaded) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Дорожка не предоставляет информацию о своих возможностях (FTMS Feature). Будут использованы универсальные настройки.",
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                CapRow(
                    label = "Скорость",
                    supported = true,
                    details = "%.1f – %.1f км/ч".format(caps.minSpeedKmh, caps.maxSpeedKmh)
                )
                CapRow(
                    label = "Шаг скорости",
                    supported = true,
                    details = "%.2f км/ч".format(caps.speedIncrementKmh)
                )
                CapRow(
                    label = "Управление наклоном",
                    supported = caps.supportsIncline,
                    details = if (caps.supportsIncline)
                        "%.1f – %.1f %%".format(caps.minInclinePercent, caps.maxInclinePercent) else null
                )
                CapRow(
                    label = "Измерение дистанции",
                    supported = caps.supportsDistance
                )
                CapRow(
                    label = "Подсчёт калорий",
                    supported = caps.supportsCalories
                )
                CapRow(
                    label = "Пульсометр (встроенный)",
                    supported = caps.supportsHeartRate
                )
                CapRow(
                    label = "Измерение времени",
                    supported = caps.supportsElapsedTime
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "Если чего-то не хватает — приложение всё равно работает, но соответствующие функции будут недоступны.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Composable
private fun CapRow(label: String, supported: Boolean, details: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (supported) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline,
            contentDescription = null,
            tint = if (supported) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (details != null) {
                Text(details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
