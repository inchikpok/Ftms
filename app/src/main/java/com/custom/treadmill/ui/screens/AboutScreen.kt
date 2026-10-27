package com.custom.treadmill.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.R

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val template = remember { buildTemplate() }
    val instructions = stringResource(R.string.about_instructions)

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

            InfoSection(R.string.about_compat_title, R.string.about_compat_items)
            InfoSection(R.string.about_features_title, R.string.about_features_items)
            InfoSection(R.string.about_stack_title, R.string.about_stack_items)

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
                        stringResource(R.string.about_custom_workout_desc),
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
                                Toast.makeText(ctx, ctx.getString(R.string.about_template_copied), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.about_copy_template), fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(ctx, "workout_instructions", instructions)
                                Toast.makeText(ctx, ctx.getString(R.string.about_instructions_copied), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.about_copy_instructions), fontSize = 13.sp)
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
                stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun InfoSection(@StringRes titleRes: Int, @StringRes contentRes: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(contentRes), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun buildTemplate(): String = """
{
  "name": "My workout",
  "description": "",
  "segments": [
    {
      "name": "Warm-up",
      "durationSec": 300,
      "speedKmh": 4.0,
      "inclinePercent": 0.0
    },
    {
      "name": "Run",
      "durationSec": 600,
      "speedKmh": 8.0,
      "inclinePercent": 1.0
    },
    {
      "name": "Cool-down",
      "durationSec": 300,
      "speedKmh": 4.0,
      "inclinePercent": 0.0
    }
  ]
}
""".trimIndent()
