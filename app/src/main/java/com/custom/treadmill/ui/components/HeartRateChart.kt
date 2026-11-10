package com.custom.treadmill.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.R
import com.custom.treadmill.ui.LocalTreadmillColors
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
    // [ФИКС] Семантические цвета из темы — Canvas не @Composable, все цвета
    // вычисляются здесь, до входа в draw scope.
    val tc = LocalTreadmillColors.current

    if (points.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.hr_waiting_for_data),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        return
    }

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
    val midT = minT + (maxT - minT) / 2
    val fmt = SimpleDateFormat("dd.MM", Locale.getDefault())

    val lineColor = tc.metricRed
    val targetColor = tc.metricBlue
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    val zoneColors = listOf(
        tc.chartBlue, tc.chartGreen, tc.chartYellow, tc.chartOrange, tc.chartRed
    )
    val zoneAlpha = 0.10f

    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text("$yMax", fontSize = 10.sp, color = textColor, fontWeight = FontWeight.Medium)
                Text("$yMid", fontSize = 10.sp, color = textColor, fontWeight = FontWeight.Medium)
                Text("$yMin", fontSize = 10.sp, color = textColor, fontWeight = FontWeight.Medium)
            }

            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height

                fun drawZone(startVal: Float, endVal: Float, color: Color) {
                    if (startVal >= endVal) return
                    val topY = h * (1f - (endVal - yMin) / yRange)
                    val bottomY = h * (1f - (startVal - yMin) / yRange)
                    drawRect(
                        color = color,
                        topLeft = Offset(0f, topY.coerceIn(0f, h)),
                        size = Size(w, (bottomY - topY).coerceAtLeast(1f))
                    )
                }

                if (zoneBounds.size == 5) {
                    drawZone(yMin.toFloat(), zoneBounds[0].toFloat(), zoneColors[0].copy(alpha = zoneAlpha))
                    drawZone(zoneBounds[0].toFloat(), zoneBounds[1].toFloat(), zoneColors[1].copy(alpha = zoneAlpha))
                    drawZone(zoneBounds[1].toFloat(), zoneBounds[2].toFloat(), zoneColors[2].copy(alpha = zoneAlpha))
                    drawZone(zoneBounds[2].toFloat(), zoneBounds[3].toFloat(), zoneColors[3].copy(alpha = zoneAlpha))
                    drawZone(zoneBounds[3].toFloat(), yMax.toFloat(), zoneColors[4].copy(alpha = zoneAlpha))
                }

                val ySteps = 2
                for (i in 0..ySteps) {
                    val y = h * (i / ySteps.toFloat())
                    drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
                }

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

                val tRange = (maxT - minT).coerceAtLeast(1000L)

                val offsets = points.map { p ->
                    val x = w * (p.timestamp - minT).toFloat() / tRange
                    val y = h * (1f - (p.bpm - yMin).toFloat() / yRange)
                    Offset(x, y)
                }

                val linePath = Path()
                val fillPath = Path()

                if (offsets.size == 1) {
                    val p = offsets[0]
                    linePath.moveTo(0f, p.y)
                    linePath.lineTo(w, p.y)
                    fillPath.moveTo(0f, h)
                    fillPath.lineTo(0f, p.y)
                    fillPath.lineTo(w, p.y)
                    fillPath.lineTo(w, h)
                    fillPath.close()
                    drawCircle(color = lineColor, radius = 4.dp.toPx(), center = p)
                } else {
                    linePath.moveTo(offsets[0].x, offsets[0].y)
                    fillPath.moveTo(offsets[0].x, h)
                    fillPath.lineTo(offsets[0].x, offsets[0].y)

                    for (i in 0 until offsets.size - 1) {
                        val p0 = offsets[i]
                        val p1 = offsets[i + 1]
                        val cp1 = Offset(p0.x + (p1.x - p0.x) / 2f, p0.y)
                        val cp2 = Offset(p0.x + (p1.x - p0.x) / 2f, p1.y)

                        linePath.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p1.x, p1.y)
                        fillPath.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p1.x, p1.y)
                    }
                    fillPath.lineTo(offsets.last().x, h)
                    fillPath.close()
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.30f), Color.Transparent),
                        startY = 0f,
                        endY = h
                    )
                )

                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                if (offsets.size in 2..20) {
                    offsets.forEach {
                        drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = it)
                        drawCircle(color = Color.White, radius = 1.5.dp.toPx(), center = it)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 32.dp, top = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(fmt.format(Date(minT)), fontSize = 10.sp, color = textColor, fontWeight = FontWeight.Medium)
            if (minT != maxT) {
                Text(fmt.format(Date(midT)), fontSize = 10.sp, color = textColor, fontWeight = FontWeight.Medium)
                Text(fmt.format(Date(maxT)), fontSize = 10.sp, color = textColor, fontWeight = FontWeight.Medium)
            }
        }
    }
}
