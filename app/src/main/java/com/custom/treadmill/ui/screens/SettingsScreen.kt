package com.custom.treadmill.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.R
import com.custom.treadmill.data.repository.HrMode
import com.custom.treadmill.data.repository.ThemeMode
import com.custom.treadmill.logic.HeartRateZoneCalculator
import com.custom.treadmill.ui.viewmodels.MainViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: (() -> Unit)? = null,
    onOpenDebug: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
    onOpenProfiles: (() -> Unit)? = null,
    onOpenTts: (() -> Unit)? = null
) {
    val settings by vm.settings.collectAsState()
    val calculatedMaxHr = HeartRateZoneCalculator.getEffectiveMaxHr(settings)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text("← " + stringResource(R.string.back)) }
                    }
                    Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }
            }

            // ================= ПРОФИЛИ =================
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_profiles), icon = Icons.Outlined.Person) {
                    CompactActionRow(
                        icon = Icons.Outlined.Person,
                        title = stringResource(R.string.settings_profiles_title),
                        subtitle = stringResource(
                            R.string.settings_profiles_subtitle,
                            settings.activeProfile.name,
                            settings.activeProfile.weightKg
                        ),
                        onClick = { onOpenProfiles?.invoke() }
                    )
                }
            }

            // ================= Внешний вид =================
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_appearance), icon = Icons.Outlined.Palette) {
                    CompactEnumDropdown(
                        label = stringResource(R.string.settings_theme_label),
                        current = settings.themeMode,
                        options = ThemeMode.values().toList(),
                        optionLabel = {
                            when (it) {
                                ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                                ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                            }
                        },
                        onSelect = { v -> vm.updateSettings { it.copy(themeMode = v) } }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    CompactSwitchRow(
                        title = stringResource(R.string.settings_keep_screen_title),
                        subtitle = stringResource(R.string.settings_keep_screen_subtitle),
                        checked = settings.keepScreenOn,
                        onChange = { v -> vm.updateSettings { it.copy(keepScreenOn = v) } }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    CompactSwitchRow(
                        title = stringResource(R.string.settings_tts_title),
                        subtitle = stringResource(R.string.settings_tts_subtitle),
                        checked = settings.ttsEnabled,
                        onChange = { v -> vm.updateSettings { it.copy(ttsEnabled = v) } }
                    )
                    
                    if (settings.ttsEnabled && onOpenTts != null) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        CompactActionRow(
                            icon = Icons.Outlined.RecordVoiceOver,
                            title = stringResource(R.string.settings_tts_customize),
                            subtitle = stringResource(R.string.settings_tts_customize_subtitle),
                            onClick = onOpenTts
                        )
                    }
                }
            }

            // ================= Скорость и Наклон =================
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_speed), icon = Icons.Outlined.Speed) {
                    CompactSliderSetting(
                        label = stringResource(R.string.settings_min_speed),
                        value = settings.minSpeedKmh.toFloat(),
                        valueRange = 0.5f..5.0f,
                        steps = 8,
                        display = { "%.1f %s".format(it, stringResource(R.string.unit_kmh)) },
                        onValueChange = { v -> vm.updateSettings { it.copy(minSpeedKmh = (v * 10).roundToInt() / 10.0) } }
                    )
                    CompactSliderSetting(
                        label = stringResource(R.string.settings_max_speed),
                        value = settings.maxSpeedKmh.toFloat(),
                        valueRange = 5.0f..20.0f,
                        steps = 29,
                        display = { "%.1f %s".format(it, stringResource(R.string.unit_kmh)) },
                        onValueChange = { v -> vm.updateSettings { it.copy(maxSpeedKmh = (v * 10).roundToInt() / 10.0) } }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    CompactEnumDropdown(
                        label = stringResource(R.string.settings_step_label),
                        current = settings.speedStepKmh,
                        options = listOf(0.1, 0.25, 0.5, 1.0),
                        optionLabel = { "%.2f %s".format(it, stringResource(R.string.unit_kmh)).replace(",00", "") },
                        onSelect = { v -> vm.updateSettings { it.copy(speedStepKmh = v) } }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    CompactSliderSetting(
                        label = stringResource(R.string.settings_max_incline),
                        value = settings.maxInclinePercent.toFloat(),
                        valueRange = 5.0f..20.0f,
                        steps = 14,
                        display = { "${it.roundToInt()} ${stringResource(R.string.unit_percent)}" },
                        onValueChange = { v -> vm.updateSettings { it.copy(maxInclinePercent = v.roundToInt().toDouble()) } }
                    )
                }
            }

            // ================= Автопульс =================
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_hr), icon = Icons.Outlined.FavoriteBorder) {
                    CompactSwitchRow(
                        title = stringResource(R.string.settings_hr_enabled_title),
                        subtitle = stringResource(R.string.settings_hr_enabled_subtitle),
                        checked = settings.hrEnabled,
                        onChange = { vm.setAutoHrEnabled(it) }
                    )

                    AnimatedVisibility(visible = settings.hrEnabled) {
                        Column {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            CompactEnumDropdown(
                                label = stringResource(R.string.settings_hr_mode_label),
                                current = settings.hrMode,
                                options = HrMode.values().toList(),
                                optionLabel = {
                                    when (it) {
                                        HrMode.TARGET -> stringResource(R.string.settings_hr_mode_target)
                                        HrMode.ZONE -> stringResource(R.string.settings_hr_mode_zone)
                                    }
                                },
                                onSelect = { v -> vm.setHrMode(v) }
                            )

                            if (settings.hrMode == HrMode.TARGET) {
                                CompactSliderSetting(
                                    label = stringResource(R.string.settings_hr_target),
                                    value = settings.targetHr.toFloat(),
                                    valueRange = 90f..190f,
                                    steps = 99,
                                    display = { "${it.roundToInt()}" },
                                    onValueChange = { v -> vm.updateSettings { it.copy(targetHr = v.roundToInt()) } }
                                )
                                CompactSliderSetting(
                                    label = stringResource(R.string.settings_hr_threshold_high),
                                    value = settings.thresholdHigh.toFloat(),
                                    valueRange = 1f..20f,
                                    steps = 18,
                                    display = { "${it.roundToInt()}" },
                                    onValueChange = { v -> vm.updateSettings { it.copy(thresholdHigh = v.roundToInt()) } }
                                )
                                CompactSliderSetting(
                                    label = stringResource(R.string.settings_hr_threshold_low),
                                    value = settings.thresholdLow.toFloat(),
                                    valueRange = 1f..20f,
                                    steps = 18,
                                    display = { "${it.roundToInt()}" },
                                    onValueChange = { v -> vm.updateSettings { it.copy(thresholdLow = v.roundToInt()) } }
                                )
                            } else {
                                CompactSliderSetting(
                                    label = stringResource(R.string.settings_hr_min),
                                    value = settings.zoneMin.toFloat(),
                                    valueRange = 90f..180f,
                                    steps = 89,
                                    display = { "${it.roundToInt()}" },
                                    onValueChange = { v -> vm.updateSettings { it.copy(zoneMin = v.roundToInt()) } }
                                )
                                CompactSliderSetting(
                                    label = stringResource(R.string.settings_hr_max),
                                    value = settings.zoneMax.toFloat(),
                                    valueRange = 100f..190f,
                                    steps = 89,
                                    display = { "${it.roundToInt()}" },
                                    onValueChange = { v -> vm.updateSettings { it.copy(zoneMax = v.roundToInt()) } }
                                )
                            }

                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            CompactSliderSetting(
                                label = stringResource(R.string.settings_hr_interval),
                                value = settings.intervalSec.toFloat(),
                                valueRange = 5f..120f,
                                steps = 22,
                                display = { "${it.roundToInt()} ${stringResource(R.string.unit_sec)}" },
                                onValueChange = { v -> vm.updateSettings { it.copy(intervalSec = v.roundToInt()) } }
                            )
                            CompactSliderSetting(
                                label = stringResource(R.string.settings_hr_step),
                                value = settings.stepKmh.toFloat(),
                                valueRange = 0.1f..2.0f,
                                steps = 18,
                                display = { "%.1f".format(it) },
                                onValueChange = { v -> vm.updateSettings { it.copy(stepKmh = (v * 10).roundToInt() / 10.0) } }
                            )
                            
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            CompactSwitchRow(
                                title = stringResource(R.string.settings_hr_auto_max_title),
                                subtitle = stringResource(R.string.settings_hr_auto_max_subtitle),
                                checked = settings.autoMaxHr,
                                onChange = { v -> vm.updateSettings { it.copy(autoMaxHr = v) } }
                            )
                            
                            if (settings.autoMaxHr) {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.settings_hr_calc_max), fontSize = 15.sp) },
                                    trailingContent = { 
                                        Text(
                                            "$calculatedMaxHr ${stringResource(R.string.unit_bpm)}", 
                                            fontWeight = FontWeight.Bold, 
                                            color = MaterialTheme.colorScheme.primary
                                        ) 
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            } else {
                                CompactSliderSetting(
                                    label = stringResource(R.string.settings_hr_manual_max),
                                    value = settings.maxHr.toFloat(),
                                    valueRange = 150f..220f,
                                    steps = 69,
                                    display = { "${it.roundToInt()}" },
                                    onValueChange = { v -> vm.updateSettings { it.copy(maxHr = v.roundToInt()) } }
                                )
                            }
                        }
                    }
                }
            }

            // ================= О приложении =================
            if (onOpenDebug != null || onOpenAbout != null) {
                item {
                    SettingsGroup(title = stringResource(R.string.settings_group_about), icon = Icons.Outlined.Info) {
                        onOpenDebug?.let {
                            CompactActionRow(
                                icon = Icons.Outlined.BugReport,
                                title = stringResource(R.string.settings_debug_title),
                                subtitle = stringResource(R.string.settings_debug_subtitle),
                                onClick = it
                            )
                        }
                        if (onOpenDebug != null && onOpenAbout != null) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                        onOpenAbout?.let {
                            CompactActionRow(
                                icon = Icons.Outlined.DirectionsRun,
                                title = stringResource(R.string.settings_about_title),
                                subtitle = stringResource(R.string.settings_about_subtitle),
                                onClick = it
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun CompactSwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontSize = 15.sp) },
        supportingContent = { Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> CompactEnumDropdown(label: String, current: T, options: List<T>, optionLabel: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = optionLabel(current), onValueChange = {}, readOnly = true,
            label = { Text(label, fontSize = 13.sp) }, textStyle = MaterialTheme.typography.bodyMedium,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun CompactSliderSetting(label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, steps: Int, display: @Composable (Float) -> String, onValueChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
                Text(display(value), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps, modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun CompactActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontSize = 15.sp) },
        supportingContent = { Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
