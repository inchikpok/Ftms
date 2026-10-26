package com.custom.treadmill.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class HrPoint(val timestamp: Long, val bpm: Int)

@Composable
fun HeartRateChart(
    points: List<HrPoint>,
    targetHr: Int = 0,
    zoneBounds: List<Int> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Ожидание данных пульса…", color = Color.Gray, fontSize = 13.sp)
        }
        return
    }

    val minT = points.first().timestamp
    val maxT = points.last().timestamp

    val target = targetHr.coerceAtLeast(0)
    val minBpmRaw = points.minOf { it.bpm }
    val maxBpmRaw = points.maxOf { it.bpm }
    val refMin = if (target > 0) minOf(minBpmRaw, target) else minBpmRaw
    val refMax = if (target > 0) maxOf(maxBpmRaw, target) else maxBpmRaw

    var yMin = (refMin - 10).coerceAtLeast(40)
    var yMax = (refMax + 10).coerceAtMost(220)
    val actualRange = yMax - yMin
    if (actualRange < 30) {
        val padding = (30 - actualRange) / 2
        yMin = (yMin - padding).coerceAtLeast(0)
        yMax = (yMax + padding).coerceAtMost(220)
    }
    val yRange = (yMax - yMin).coerceAtLeast(1)

    val lineColor = Color(0xFFC62828)
    val targetColor = Color(0xFF1976D2)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    val zone1 = Color(0xFF64B5F6).copy(alpha = 0.15f)
    val zone2 = Color(0xFF81C784).copy(alpha = 0.15f)
    val zone3 = Color(0xFFFFD54F).copy(alpha = 0.15f)
    val zone4 = Color(0xFFFFB74D).copy(alpha = 0.15f)
    val zone5 = Color(0xFFE57373).copy(alpha = 0.15f)

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val padL = 38.dp.toPx() // Отступ слева под цифры
            val padR = 8.dp.toPx()
            val padT = 8.dp.toPx()
            val padB = 8.dp.toPx()
            val innerW = w - padL - padR
            val innerH = h - padT - padB
            if (innerW <= 0f || innerH <= 0f) return@Canvas

            // 1. Рисуем зоны
            fun drawZone(startVal: Float, endVal: Float, color: Color) {
                if (startVal >= endVal) return
                val topY = padT + innerH * (1f - (endVal - yMin) / yRange)
                val bottomY = padT + innerH * (1f - (startVal - yMin) / yRange)
                drawRect(color = color, topLeft = Offset(padL, topY), size = Size(innerW, bottomY - topY))
            }

            if (zoneBounds.size == 5) {
                drawZone(0f, zoneBounds[0].toFloat(), Color.Transparent)
                drawZone(zoneBounds[0].toFloat(), zoneBounds[1].toFloat(), zone1)
                drawZone(zoneBounds[1].toFloat(), zoneBounds[2].toFloat(), zone2)
                drawZone(zoneBounds[2].toFloat(), zoneBounds[3].toFloat(), zone3)
                drawZone(zoneBounds[3].toFloat(), zoneBounds[4].toFloat(), zone4)
                drawZone(zoneBounds[4].toFloat(), 250f, zone5)
            }

            // 2. Текст для оси Y
            val textPaint = android.graphics.Paint().apply {
                color = textColor.toArgb()
                textSize = 10.sp.toPx()
                textAlign = android.graphics.Paint.Align.RIGHT
                isAntiAlias = true
            }

            // 3. Горизонтальная сетка и подписи
            val steps = 4
            for (i in 0..steps) {
                val frac = i / steps.toFloat()
                val y = padT + innerH * frac
                val value = (yMax - frac * yRange).toInt()
                
                drawLine(color = gridColor, start = Offset(padL, y), end = Offset(w - padR, y), strokeWidth = 1f)
                
                // Рисуем текст (только если это не самый верх/низ, чтобы не перекрывать края)
                if (i > 0 && i < steps) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "$value",
                        padL - 6.dp.toPx(),
                        y + 4.dp.toPx(), // небольшая коррекция для центрирования по вертикали
                        textPaint
                    )
                }
            }

            // 4. Линия цели
            if (target in yMin..yMax) {
                val ty = padT + innerH * (1f - (target - yMin).toFloat() / yRange)
                drawLine(
                    color = targetColor, 
                    start = Offset(padL, ty), 
                    end = Offset(w - padR, ty), 
                    strokeWidth = 2f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
            }

            // 5. Линия пульса
            val tRange = (maxT - minT).coerceAtLeast(1000L)
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = padL + innerW * (p.timestamp - minT).toFloat() / tRange
                val y = padT + innerH * (1f - (p.bpm - yMin).toFloat() / yRange)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
