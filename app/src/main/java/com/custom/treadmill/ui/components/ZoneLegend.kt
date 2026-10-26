package com.custom.treadmill.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ZoneLegend(zoneBounds: List<Int>, modifier: Modifier = Modifier) {
    if (zoneBounds.size != 5) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        LegendItem("Z1", "<${zoneBounds[0]}", Color(0xFF64B5F6))
        LegendItem("Z2", "${zoneBounds[0]}-${zoneBounds[1]}", Color(0xFF81C784))
        LegendItem("Z3", "${zoneBounds[1]}-${zoneBounds[2]}", Color(0xFFFFD54F))
        LegendItem("Z4", "${zoneBounds[2]}-${zoneBounds[3]}", Color(0xFFFFB74D))
        LegendItem("Z5", ">${zoneBounds[3]}", Color(0xFFE57373))
    }
}

@Composable
private fun LegendItem(name: String, range: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text(name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(range, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}
