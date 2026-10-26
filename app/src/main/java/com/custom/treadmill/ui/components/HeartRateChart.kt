package com.custom.treadmill.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HrPoint(val timestamp: Long, val bpm: Int)

@Composable
fun HeartRateChart(
    points: List<HrPoint>,
    targetHr: Int = 0,
    zoneBounds: List<Int> = emptyList(),
    showTargetLine: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Ожидание данных пульса…", color = Color.Gray, fontSize = 13.sp)
        }
        return
    }

    // 1. Расчет Y-оси
    val dataMin = points.minOf { it.bpm }
    val dataMax = points.maxOf { it.bpm }
    
    var yMin = (dataMin - 10).coerceAtLeast(40)
    var yMax = (dataMax + 10).coerceAtMost(220)

    if (showTargetLine && targetHr > 0) {
        yMin = minOf(yMin, targetHr - 5)
        yMax = maxOf(yMax, targetHr + 5)
    }
    
    if (yMax - yMin < 30) {
        val mid = (yMax + yMin) / 2
        yMin = mid - 15
        yMax = mid + 15
    }
    
    val yRange = (yMax - yMin).coerceAtLeast(1)
    val yMid = (yMax + yMin) / 2

    val minT = points.first().timestamp
    val maxT = points.last().timestamp
    val fmt = SimpleDateFormat("dd.MM", Locale.getDefault())

    val lineColor = Color(0xFFE53935)
    val targetColor = Color(0xFF1976D2)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    val zoneColors = listOf(
        Color(0xFF64B5F6), // Z1
        Color(0xFF81C784), // Z2
        Color(0xFFFFD54F), // Z3
        Color(0xFFFFB74D), // Z4
        Color(0xFFE57373)  // Z5
    )
    val zoneAlpha = 0.15f

    Column(modifier) {
        // График + ось Y
        Row(Modifier.fillMaxWidth().weight(1f)) {
            
            // Ось Y (подписи)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp), // Небольшой отступ, чтобы текст не прилипал
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text("$yMax", fontSize = 10.sp, color = textColor)
                Text("$yMid", fontSize = 10.sp, color = textColor)
                Text("$yMin", fontSize = 10.sp, color = textColor)
            }

            // Сам график
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height

                // Рисуем зоны
                fun drawZone(startVal: Float, endVal: Float, color: Color) {
                    if (startVal >= endVal) return
                    val topY = h * (1f - (endVal - yMin) / yRange)
                    val bottomY = h * (1f - (startVal - yMin) / yRange)
                    drawRect(
                        color = color,
                        topLeft = Offset(0f, topY),
                        size = Size(w, (bottomY - topY).coerceAtLeast(1f))
                    )
                }

                if (zoneBounds.size == 5) {
                    drawZone(0f, zoneBounds[0].toFloat(), zoneColors[0].copy(alpha = zoneAlpha))
                    drawZone(zoneBounds[0].toFloat(), zoneBounds[1].toFloat(), zoneColors[1].copy(alpha = zoneAlpha))
                    drawZone(zoneBounds[1].toFloat(), zoneBounds[2].toFloat(), zoneColors[2].copy(alpha = zoneAlpha))
                    drawZone(zoneBounds[2].toFloat(), zoneBounds[3].toFloat(), zoneColors[3].copy(alpha = zoneAlpha))
                    drawZone(zoneBounds[3].toFloat(), 250f, zoneColors[4].copy(alpha = zoneAlpha * 1.5f))
                }

                // Сетка (3 линии)
                val ySteps = 2
                for (i in 0..ySteps) {
                    val y = h * (i / ySteps.toFloat())
                    drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
                }

                // Линия цели
                if (showTargetLine && targetHr in yMin..yMax) {
                    val ty = h * (1f - (targetHr - yMin).toFloat() / yRange)
                    drawLine(
                        color = targetColor, 
                        start = Offset(0f, ty), 
                        end = Offset(w, ty), 
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    )
                }

                // Линия пульса и градиент
                val tRange = (maxT - minT).coerceAtLeast(1000L)
                val linePath = Path()
                val fillPath = Path()

                points.forEachIndexed { i, p ->
                    val x = w * (p.timestamp - minT).toFloat() / tRange
                    val y = h * (1f - (p.bpm - yMin).toFloat() / yRange)
                    
                    if (i == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                    if (i == points.size - 1) {
                        fillPath.lineTo(x, h)
                        fillPath.close()
                    }
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent),
                        startY = 0f,
                        endY = h
                    )
                )

                drawPath(
                    path = linePath, 
                    color = lineColor, 
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        // Ось X (даты) под графиком
        Row(
            Modifier.fillMaxWidth().padding(start = 32.dp, top = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(fmt.format(Date(minT)), fontSize = 10.sp, color = textColor)
            Text(fmt.format(Date(maxT)), fontSize = 10.sp, color = textColor)
        }

        // Легенда зон (если есть)
        if (zoneBounds.size == 5) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ZoneItem("Z1", "<${zoneBounds[0]}", zoneColors[0])
                ZoneItem("Z2", "${zoneBounds[0]}-${zoneBounds[1]}", zoneColors[1])
                ZoneItem("Z3", "${zoneBounds[1]}-${zoneBounds[2]}", zoneColors[2])
                ZoneItem("Z4", "${zoneBounds[2]}-${zoneBounds[3]}", zoneColors[3])
                ZoneItem("Z5", ">${zoneBounds[3]}", zoneColors[4])
            }
        }
    }
}

@Composable
private fun ZoneItem(name: String, range: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(3.dp))
            Text(name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(range, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}
