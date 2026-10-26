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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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

    val minT = points.first().timestamp
    val maxT = points.last().timestamp

    // 1. Умный расчет Y-оси
    val dataMin = points.minOf { it.bpm }
    val dataMax = points.maxOf { it.bpm }
    
    var yMin = (dataMin - 10).coerceAtLeast(40)
    var yMax = (dataMax + 10).coerceAtMost(220)

    if (showTargetLine && targetHr > 0) {
        yMin = minOf(yMin, targetHr - 5)
        yMax = maxOf(yMax, targetHr + 5)
    }
    
    // Если диапазон слишком маленький (например, пульс 60-65), искусственно расширяем
    if (yMax - yMin < 30) {
        val mid = (yMax + yMin) / 2
        yMin = mid - 15
        yMax = mid + 15
    }
    val yRange = (yMax - yMin).coerceAtLeast(1)

    val lineColor = Color(0xFFE53935)
    val targetColor = Color(0xFF1976D2)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    val zoneColors = listOf(
        Color(0xFF64B5F6).copy(alpha = 0.15f), // Z1
        Color(0xFF81C784).copy(alpha = 0.15f), // Z2
        Color(0xFFFFD54F).copy(alpha = 0.15f), // Z3
        Color(0xFFFFB74D).copy(alpha = 0.15f), // Z4
        Color(0xFFE57373).copy(alpha = 0.20f)  // Z5
    )

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            
            val padL = 32.dp.toPx() // Место под цифры Y
            val padR = 4.dp.toPx()
            val padT = 8.dp.toPx()
            val padB = 20.dp.toPx() // Место под даты X
            val innerW = w - padL - padR
            val innerH = h - padT - padB
            
            if (innerW <= 0f || innerH <= 0f) return@Canvas

            // 2. Рисуем зоны
            fun drawZone(startVal: Float, endVal: Float, color: Color) {
                if (startVal >= endVal) return
                val topY = padT + innerH * (1f - (endVal - yMin) / yRange)
                val bottomY = padT + innerH * (1f - (startVal - yMin) / yRange)
                drawRect(
                    color = color,
                    topLeft = Offset(padL, topY),
                    size = Size(innerW, (bottomY - topY).coerceAtLeast(1f))
                )
            }

            if (zoneBounds.size == 5) {
                drawZone(0f, zoneBounds[0].toFloat(), Color.Transparent)
                drawZone(zoneBounds[0].toFloat(), zoneBounds[1].toFloat(), zoneColors[0])
                drawZone(zoneBounds[1].toFloat(), zoneBounds[2].toFloat(), zoneColors[1])
                drawZone(zoneBounds[2].toFloat(), zoneBounds[3].toFloat(), zoneColors[2])
                drawZone(zoneBounds[3].toFloat(), zoneBounds[4].toFloat(), zoneColors[3])
                drawZone(zoneBounds[4].toFloat(), 250f, zoneColors[4])
            }

            // 3. Подготовка текста
            val textPaint = android.graphics.Paint().apply {
                color = textColor.toArgb()
                textSize = 9.sp.toPx()
                isAntiAlias = true
            }

            // 4. Сетка и подписи Y
            val ySteps = 3 // 3 линии сетки
            for (i in 0..ySteps) {
                val frac = i / ySteps.toFloat()
                val y = padT + innerH * frac
                val value = (yMax - frac * yRange).toInt()
                
                drawLine(color = gridColor, start = Offset(padL, y), end = Offset(w - padR, y), strokeWidth = 1f)
                
                textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(
                    "$value",
                    padL - 4.dp.toPx(),
                    y + 3.dp.toPx(),
                    textPaint
                )
            }

            // 5. Линия цели (если нужно)
            if (showTargetLine && targetHr in yMin..yMax) {
                val ty = padT + innerH * (1f - (targetHr - yMin).toFloat() / yRange)
                drawLine(
                    color = targetColor, 
                    start = Offset(padL, ty), 
                    end = Offset(w - padR, ty), 
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )
            }

            // 6. Линия пульса и градиент
            val tRange = (maxT - minT).coerceAtLeast(1000L)
            
            val linePath = Path()
            val fillPath = Path()

            points.forEachIndexed { i, p ->
                val x = padL + innerW * (p.timestamp - minT).toFloat() / tRange
                val y = padT + innerH * (1f - (p.bpm - yMin).toFloat() / yRange)
                
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, padT + innerH)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                if (i == points.size - 1) {
                    fillPath.lineTo(x, padT + innerH)
                    fillPath.close()
                }
            }

            // Градиент под линией
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.3f), Color.Transparent),
                    startY = padT,
                    endY = padT + innerH
                )
            )

            // Сама линия
            drawPath(
                path = linePath, 
                color = lineColor, 
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // 7. Подписи X (даты)
            val fmt = SimpleDateFormat("dd.MM", Locale.getDefault())
            textPaint.textAlign = android.graphics.Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(
                fmt.format(Date(minT)),
                padL,
                h - 2.dp.toPx(),
                textPaint
            )
            
            textPaint.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(
                fmt.format(Date(maxT)),
                w - padR,
                h - 2.dp.toPx(),
                textPaint
            )
        }
    }
}
