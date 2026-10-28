package com.custom.treadmill.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.R

@Composable
fun AboutScreen(onBack: () -> Unit) {
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.back))
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.about_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.about_version), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodySmall)
                }
            }

            InfoSection(stringResource(R.string.about_compat_title), listOf(
                stringResource(R.string.about_compat_items)
            ))

            InfoSection(stringResource(R.string.about_features_title), listOf(
                stringResource(R.string.about_features_items)
            ))

            InfoSection(stringResource(R.string.about_stack_title), listOf(
                stringResource(R.string.about_stack_items)
            ))

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.about_custom_workout_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.about_custom_workout_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text(
                stringResource(R.string.about_footer),
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
