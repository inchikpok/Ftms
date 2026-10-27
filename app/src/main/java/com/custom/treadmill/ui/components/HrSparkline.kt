package com.custom.treadmill.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HrSparkline(
    samples: List<Pair<Int, Int>>,
    durationSec: Int,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFFE53935)
) {
    if (samples.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Нет данных пульса",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val dataMin = samples.minOf { it.second }
    val dataMax = samples.maxOf { it.second }
    val rawRange = (dataMax - dataMin).coerceAtLeast(20)
    val yPad = (rawRange * 0.15).toInt().coerceAtLeast(3)
    val yMin = (dataMin - yPad).coerceAtLeast(30)
    val yMax = (dataMax + yPad).coerceAtMost(220)
    val yRange = (yMax - yMin).coerceAtLeast(1)
    val tRange = durationSec.coerceAtLeast(1)

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        val offsets = samples.map { sample ->
            val x = w * sample.first.toFloat() / tRange
            val y = h * (1f - (sample.second - yMin).toFloat() / yRange)
            Offset(x.coerceIn(0f, w), y.coerceIn(0f, h))
        }

        val linePath = Path()
        val fillPath = Path()
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
            style = Stroke(
                width = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
