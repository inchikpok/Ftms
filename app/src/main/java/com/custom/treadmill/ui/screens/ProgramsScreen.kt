package com.custom.treadmill.ui.screens

import android.widget.NumberPicker
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.custom.treadmill.R
import com.custom.treadmill.ble.BleConnectionState
import com.custom.treadmill.data.database.ProgramData
import com.custom.treadmill.data.database.ProgramEntity
import com.custom.treadmill.data.database.ProgramSegment
import com.custom.treadmill.logic.ProgramGenerator
import com.custom.treadmill.logic.WorkoutGoal
import com.custom.treadmill.ui.viewmodels.MainViewModel
import com.custom.treadmill.ui.viewmodels.ProgramViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class SortMode { NAME_ASC, NAME_DESC, NEWEST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramsScreen(
    mainVm: MainViewModel,
    programVm: ProgramViewModel,
    onBack: (() -> Unit)? = null,
    onStartWorkout: (ProgramData) -> Unit
) {
    val ctx = LocalContext.current
    val programs by programVm.programs.collectAsState()
    val treadmillState by mainVm.treadmillState.collectAsState()
    val workoutState by mainVm.workoutState.collectAsState()
    val settings by mainVm.settings.collectAsState()
    val caps by mainVm.capabilities.collectAsState()

    val effectiveMaxSpeed = remember(settings.maxSpeedKmh, caps) {
        if (caps.isLoaded) minOf(settings.maxSpeedKmh, caps.maxSpeedKmh) else settings.maxSpeedKmh
    }
    val effectiveMaxIncline = remember(settings.maxInclinePercent, caps) {
        if (caps.isLoaded) minOf(settings.maxInclinePercent, caps.maxInclinePercent) else settings.maxInclinePercent
    }
    val showIncline = !caps.isLoaded || caps.supportsIncline

    val treadmillReady = treadmillState == BleConnectionState.READY

    var editingId by remember { mutableStateOf(-1L) }
    var editingData by remember { mutableStateOf<ProgramData?>(null) }
    var exportTarget by remember { mutableStateOf<ProgramData?>(null) }
    var deleteTarget by remember { mutableStateOf<ProgramEntity?>(null) }

    var showTemplates by remember { mutableStateOf(false) }
    var showGenerator by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(SortMode.NAME_ASC) }
    var showNotConnectedDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    fun tryImportText(text: String, source: String) {
        try {
            if (text.isBlank()) { mainVm.showMessage(ctx.getString(R.string.programs_import_error_empty)); return }
            val data = programVm.importJson(text)
            if (data.name.isBlank() || data.segments.isEmpty()) {
                mainVm.showMessage(ctx.getString(R.string.programs_import_error_no_program)); return
            }
            if (data.segments.any { it.durationSec <= 0 || it.speedKmh <= 0 }) {
                mainVm.showMessage(ctx.getString(R.string.programs_import_error_invalid_segments)); return
            }
            programVm.save(data)
            mainVm.showMessage(ctx.getString(R.string.programs_import_success, data.name, source))
        } catch (e: Exception) {
            mainVm.showMessage(ctx.getString(R.string.programs_import_error_generic, e.message ?: ""))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val data = exportTarget ?: return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(programVm.exportJson(data).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            mainVm.showMessage(ctx.getString(R.string.programs_import_error_save, e.message ?: ""))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val text = ctx.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return@rememberLauncherForActivityResult
            tryImportText(text, ctx.getString(R.string.programs_import_source_file))
        } catch (e: Exception) {
            mainVm.showMessage(ctx.getString(R.string.programs_import_error_read, e.message ?: ""))
        }
    }

    val visible = remember(programs, searchQuery, sortMode) {
        val q = searchQuery.trim().lowercase()
        val filtered = if (q.isEmpty()) programs
        else programs.filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
        when (sortMode) {
            SortMode.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            SortMode.NEWEST -> filtered.sortedByDescending { it.id }
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showTemplates = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.programs_create)) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                } else {
                    Spacer(Modifier.width(16.dp))
                }
                Text(
                    stringResource(R.string.programs_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = { showGenerator = true }) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = stringResource(R.string.programs_generator_tooltip), tint = MaterialTheme.colorScheme.tertiary)
                }

                IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.programs_import))
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.programs_search), fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear), modifier = Modifier.size(20.dp)) }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).height(50.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )

                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier.size(50.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        Icon(Icons.Default.Sort, contentDescription = stringResource(R.string.programs_sort), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.programs_sort_name_asc)) }, onClick = { sortMode = SortMode.NAME_ASC; showSortMenu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.programs_sort_name_desc)) }, onClick = { sortMode = SortMode.NAME_DESC; showSortMenu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.programs_sort_newest)) }, onClick = { sortMode = SortMode.NEWEST; showSortMenu = false })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FitnessCenter, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (programs.isEmpty()) stringResource(R.string.programs_empty_title)
                            else stringResource(R.string.programs_no_results_title),
                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (programs.isEmpty()) stringResource(R.string.programs_empty_subtitle)
                            else stringResource(R.string.programs_no_results_subtitle),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)
                ) {
                    items(visible, key = { it.id }) { p ->
                        val data = remember(p.id, p.segmentsJson) { programVm.toData(p) }
                        ProgramCardModern(
                            entity = p, data = data, canStart = treadmillReady,
                            onEdit = { editingId = p.id; editingData = data },
                            onDuplicate = { programVm.save(data.copy(name = ctx.getString(R.string.programs_copy_suffix, data.name)), 0L) },
                            onDelete = {
                                if (workoutState.running && workoutState.programName == p.name) mainVm.showMessage(ctx.getString(R.string.programs_cannot_delete_running))
                                else deleteTarget = p
                            },
                            onExport = { exportTarget = data; exportLauncher.launch("${p.name}.json") },
                            onStart = { if (treadmillReady) onStartWorkout(data) else showNotConnectedDialog = true }
                        )
                    }
                }
            }
        }
    }

    if (showGenerator) {
        WorkoutGeneratorDialog(
            profile = settings.activeProfile,
            maxSpeed = effectiveMaxSpeed,
            maxIncline = effectiveMaxIncline,
            capsLoaded = caps.isLoaded,
            capsSupportsIncline = caps.supportsIncline,
            onGenerate = { goal, duration, intensity ->
                val program = ProgramGenerator.generate(
                    context = ctx
                    profile = settings.activeProfile,
                    goal = goal,
                    durationMin = duration,
                    intensityPercent = intensity,
                    maxSpeedLimit = effectiveMaxSpeed,
                    maxInclineLimit = if (showIncline) effectiveMaxIncline else 0.0
                )
                programVm.save(program)
                mainVm.showMessage(ctx.getString(R.string.programs_generated_toast))
                showGenerator = false
            },
            onDismiss = { showGenerator = false }
        )
    }

    if (showTemplates) {
        TemplatesDialog(
            onPick = { template ->
                showTemplates = false
                editingId = 0L
                editingData = template ?: ProgramData(
                    name = ctx.getString(R.string.editor_new_title),
                    description = "",
                    segments = listOf(
                        ProgramSegment(
                            300, 5.0, 0.0,
                            ctx.getString(R.string.editor_segment_short) + " 1"
                        )
                    )
                )
            },
            onDismiss = { showTemplates = false }
        )
    }

    if (showNotConnectedDialog) {
        AlertDialog(
            onDismissRequest = { showNotConnectedDialog = false },
            title = { Text(stringResource(R.string.not_connected_title)) },
            text = { Text(stringResource(R.string.not_connected_text)) },
            confirmButton = { TextButton(onClick = { showNotConnectedDialog = false }) { Text(stringResource(R.string.ok)) } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_program_title)) },
            text = { Text(stringResource(R.string.delete_program_text, target.name)) },
            confirmButton = {
                TextButton(onClick = { programVm.delete(target); deleteTarget = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    editingData?.let { data ->
        val isNew = editingId == 0L
        ProgramEditorScreen(
            initial = data, isNew = isNew,
            maxSpeed = effectiveMaxSpeed,
            maxIncline = if (showIncline) effectiveMaxIncline else 0.0,
            showIncline = showIncline,
            onShowMessage = { mainVm.showMessage(it) },
            onSave = { newData ->
                programVm.save(newData, if (isNew) 0L else editingId)
                editingData = null; editingId = -1L
            },
            onDismiss = { editingData = null; editingId = -1L }
        )
    }
}

@Composable
private fun WorkoutGeneratorDialog(
    profile: com.custom.treadmill.data.repository.UserProfile,
    maxSpeed: Double,
    maxIncline: Double,
    capsLoaded: Boolean,
    capsSupportsIncline: Boolean,
    onGenerate: (WorkoutGoal, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var goal by remember { mutableStateOf(WorkoutGoal.WEIGHT_LOSS) }
    var duration by remember { mutableFloatStateOf(30f) }
    var intensity by remember { mutableFloatStateOf(100f) }

    val profileName = profile.name.ifBlank { stringResource(R.string.profiles_name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.generator_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.generator_profile_label, profileName, profile.weightKg, profile.age),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(stringResource(R.string.generator_goal), fontWeight = FontWeight.SemiBold)
                WorkoutGoal.values().forEach { g ->
                    val titleRes = when (g) {
                        WorkoutGoal.WEIGHT_LOSS -> R.string.goal_weight_loss_title
                        WorkoutGoal.CARDIO -> R.string.goal_cardio_title
                        WorkoutGoal.SPEED -> R.string.goal_speed_title
                    }
                    val descRes = when (g) {
                        WorkoutGoal.WEIGHT_LOSS -> R.string.goal_weight_loss_desc
                        WorkoutGoal.CARDIO -> R.string.goal_cardio_desc
                        WorkoutGoal.SPEED -> R.string.goal_speed_desc
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { goal = g }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = goal == g, onClick = { goal = g })
                        Column {
                            Text(stringResource(titleRes), fontWeight = FontWeight.Medium)
                            Text(stringResource(descRes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.generator_duration_label, duration.toInt()), fontWeight = FontWeight.SemiBold)
                Slider(
                    value = duration, onValueChange = { duration = it },
                    valueRange = 20f..60f, steps = 7
                )

                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.generator_intensity_label, intensity.toInt()), fontWeight = FontWeight.SemiBold)
                Slider(
                    value = intensity, onValueChange = { intensity = it },
                    valueRange = 70f..130f, steps = 11
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.generator_easy), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.generator_max), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(
                    when {
                        capsSupportsIncline -> stringResource(R.string.generator_limits_with_incline, maxSpeed, maxIncline.toInt())
                        capsLoaded -> stringResource(R.string.generator_limits_no_incline, maxSpeed)
                        else -> stringResource(R.string.generator_limits, maxSpeed)
                    },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onGenerate(goal, duration.toInt(), intensity.toInt()) }) {
                Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.generator_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun WorkoutMiniChart(segments: List<ProgramSegment>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) return
    val totalSec = segments.sumOf { it.durationSec }.coerceAtLeast(1)
    val maxSpeed = 20.0
    val maxIncline = 15.0
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current // <-- Получаем контекст для getString внутри Canvas

    Canvas(modifier = modifier) {
        val w = size.width
        val axisHeight = 18.dp.toPx()
        val chartH = size.height - axisHeight
        var currentX = 0f
        val gap = 1.5f
        val textPaint = android.graphics.Paint().apply {
            color = textColor.toArgb()
            textSize = 9.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        segments.forEach { seg ->
            val segW = (seg.durationSec.toFloat() / totalSec) * w
            val drawW = (segW - gap).coerceAtLeast(1f)
            val speedH = (seg.speedKmh.toFloat() / maxSpeed.toFloat()) * chartH * 0.9f
            val speedColor = when {
                seg.speedKmh < 6.0 -> Color(0xFF29B6F6)
                seg.speedKmh < 9.0 -> Color(0xFF66BB6A)
                seg.speedKmh < 12.0 -> Color(0xFFFFCA28)
                seg.speedKmh < 15.0 -> Color(0xFFFF7043)
                else -> Color(0xFFE53935)
            }
            drawRoundRect(color = speedColor, topLeft = Offset(currentX + gap/2, chartH - speedH), size = Size(drawW, speedH), cornerRadius = CornerRadius(2f, 2f))

            if (seg.inclinePercent > 0) {
                val inclineH = (seg.inclinePercent.toFloat() / maxIncline.toFloat()) * chartH * 0.9f
                val y = chartH - inclineH
                val linePath = Path().apply { moveTo(currentX + gap/2, y); lineTo(currentX + segW - gap/2, y) }
                drawPath(path = linePath, color = Color(0xFFAB47BC), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)))
            }
            currentX += segW
        }

        drawLine(axisColor, start = Offset(0f, chartH), end = Offset(w, chartH), strokeWidth = 1.dp.toPx())
        val totalMin = totalSec / 60f
        val stepMin = when { totalMin <= 5f -> 1f; totalMin <= 12f -> 2f; totalMin <= 25f -> 5f; totalMin <= 60f -> 10f; totalMin <= 120f -> 15f; else -> 30f }
        val numSteps = ceil((totalMin / stepMin).toDouble()).toInt()
        for (i in 0..numSteps) {
            val m = i * stepMin
            if (m > totalMin) break
            val x = (m / totalMin) * w
            val textX = when { i == 0 -> x + 4.dp.toPx(); m >= totalMin -> x - 4.dp.toPx(); else -> x }
            drawLine(axisColor, start = Offset(x, chartH), end = Offset(x, chartH - 3.dp.toPx()), strokeWidth = 1.dp.toPx())
            
            // [ФИКС] Используем context.getString вместо stringResource
            val label = when { 
                m < 60 -> "${m.toInt()}" 
                else -> context.getString(R.string.chart_hours, (m / 60).toInt()) 
            }
            drawContext.canvas.nativeCanvas.drawText(label, textX, size.height - 2.dp.toPx(), textPaint)
        }
    }
}

@Composable
private fun ProgramCardModern(
    entity: ProgramEntity, data: ProgramData, canStart: Boolean,
    onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit, onStart: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val totalSec = data.segments.sumOf { it.durationSec }
    val minutes = totalSec / 60

    val durationText = if (minutes > 0)
        stringResource(R.string.programs_minutes_short, minutes)
    else
        stringResource(R.string.programs_seconds_short, totalSec % 60)

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(entity.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    if (entity.description.isNotBlank()) {
                        Text(entity.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onStart, enabled = canStart, modifier = Modifier.size(36.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.programs_start), modifier = Modifier.size(20.dp))
                }
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu)) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { showMenu = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.programs_duplicate)) }, onClick = { showMenu = false; onDuplicate() }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.programs_export_json)) }, onClick = { showMenu = false; onExport() }, leadingIcon = { Icon(Icons.Default.Share, null) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            WorkoutMiniChart(segments = data.segments, modifier = Modifier.fillMaxWidth().height(80.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                StatChip(stringResource(R.string.programs_segments_count, data.segments.size))
                Spacer(Modifier.width(6.dp))
                StatChip(durationText)
                Spacer(Modifier.weight(1f))
                Icon(imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = stringResource(R.string.programs_expand), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    HorizontalDivider(Modifier.padding(bottom = 8.dp))
                    data.segments.forEachIndexed { i, seg ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text("${i + 1}.", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                                Text(seg.name.ifBlank { stringResource(R.string.programs_segment_default, i + 1) }, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(Modifier.height(2.dp))
                            Row(Modifier.padding(start = 24.dp)) {
                                val m = seg.durationSec / 60
                                val s = seg.durationSec % 60
                                val timeStr = if (m > 0)
                                    stringResource(R.string.programs_duration_format, m, s)
                                else
                                    stringResource(R.string.programs_seconds_short, s)
                                Text("$timeStr · %.1f ${stringResource(R.string.unit_kmh)} · %.0f${stringResource(R.string.unit_percent)}".format(seg.speedKmh, seg.inclinePercent), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                        if (i < data.segments.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(6.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TemplatesDialog(onPick: (ProgramData?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val templates = remember { buildTemplates(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.templates_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { onPick(null) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.templates_empty), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                items(templates) { (title, data) ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onPick(data) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(2.dp))
                            val totalSec = data.segments.sumOf { it.durationSec }
                            Text(
                                "${data.segments.size} · ${totalSec / 60} ${stringResource(R.string.unit_min)}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private fun buildTemplates(ctx: android.content.Context): List<Pair<String, ProgramData>> {
    val intervals = ProgramData(
        name = ctx.getString(R.string.template_intervals_title),
        description = ctx.getString(R.string.template_intervals_desc),
        segments = listOf(
            ProgramSegment(390, 9.5, 1.0, ctx.getString(R.string.seg_warmup_easy)),
            ProgramSegment(30, 16.0, 1.0, ctx.getString(R.string.seg_warmup_accel)),
            ProgramSegment(60, 8.0, 1.0, ctx.getString(R.string.seg_warmup_jog)),
            ProgramSegment(120, 9.0, 1.0, ctx.getString(R.string.seg_warmup_calm)),
            ProgramSegment(195, 15.0, 1.0, ctx.getString(R.string.seg_interval, 1)),
            ProgramSegment(160, 8.5, 1.0, ctx.getString(R.string.seg_rest_jog)),
            ProgramSegment(195, 15.0, 1.0, ctx.getString(R.string.seg_interval, 2)),
            ProgramSegment(160, 8.5, 1.0, ctx.getString(R.string.seg_rest_jog)),
            ProgramSegment(195, 15.0, 1.0, ctx.getString(R.string.seg_interval, 3)),
            ProgramSegment(160, 8.5, 1.0, ctx.getString(R.string.seg_rest_jog)),
            ProgramSegment(195, 15.0, 1.0, ctx.getString(R.string.seg_interval, 4)),
            ProgramSegment(360, 9.0, 1.0, ctx.getString(R.string.seg_cooldown_jog)),
            ProgramSegment(240, 5.0, 0.0, ctx.getString(R.string.seg_cooldown_walk))
        )
    )
    val pyramid = ProgramData(
        name = ctx.getString(R.string.template_pyramid_title),
        description = ctx.getString(R.string.template_pyramid_desc),
        segments = listOf(
            ProgramSegment(300, 9.5, 1.0, ctx.getString(R.string.seg_warmup)),
            ProgramSegment(60, 12.0, 1.0, ctx.getString(R.string.seg_pyramid, 1)),
            ProgramSegment(90, 8.5, 1.0, ctx.getString(R.string.seg_rest)),
            ProgramSegment(120, 12.5, 1.0, ctx.getString(R.string.seg_pyramid, 2)),
            ProgramSegment(90, 8.5, 1.0, ctx.getString(R.string.seg_rest)),
            ProgramSegment(180, 13.0, 1.0, ctx.getString(R.string.seg_pyramid, 3)),
            ProgramSegment(90, 8.5, 1.0, ctx.getString(R.string.seg_rest)),
            ProgramSegment(120, 12.5, 1.0, ctx.getString(R.string.seg_pyramid, 2)),
            ProgramSegment(90, 8.5, 1.0, ctx.getString(R.string.seg_rest)),
            ProgramSegment(60, 12.0, 1.0, ctx.getString(R.string.seg_pyramid, 1)),
            ProgramSegment(300, 9.0, 1.0, ctx.getString(R.string.seg_cooldown))
        )
    )
    val hills = ProgramData(
        name = ctx.getString(R.string.template_hills_title),
        description = ctx.getString(R.string.template_hills_desc),
        segments = listOf(
            ProgramSegment(300, 9.5, 1.0, ctx.getString(R.string.seg_warmup)),
            ProgramSegment(60, 8.0, 1.0, ctx.getString(R.string.seg_warmup_jog)),
            ProgramSegment(60, 12.0, 6.0, ctx.getString(R.string.seg_hill, 1)),
            ProgramSegment(100, 6.0, 6.0, ctx.getString(R.string.seg_rest_hill)),
            ProgramSegment(60, 12.0, 6.0, ctx.getString(R.string.seg_hill, 2)),
            ProgramSegment(100, 6.0, 6.0, ctx.getString(R.string.seg_rest_hill)),
            ProgramSegment(60, 12.0, 6.0, ctx.getString(R.string.seg_hill, 3)),
            ProgramSegment(100, 6.0, 6.0, ctx.getString(R.string.seg_rest_hill)),
            ProgramSegment(60, 12.0, 6.0, ctx.getString(R.string.seg_hill, 4)),
            ProgramSegment(100, 6.0, 6.0, ctx.getString(R.string.seg_rest_hill)),
            ProgramSegment(60, 12.0, 6.0, ctx.getString(R.string.seg_hill, 5)),
            ProgramSegment(100, 6.0, 6.0, ctx.getString(R.string.seg_rest_hill)),
            ProgramSegment(60, 12.0, 6.0, ctx.getString(R.string.seg_hill, 6)),
            ProgramSegment(300, 9.0, 1.0, ctx.getString(R.string.seg_cooldown))
        )
    )
    val fartlek = ProgramData(
        name = ctx.getString(R.string.template_fartlek_title),
        description = ctx.getString(R.string.template_fartlek_desc),
        segments = listOf(
            ProgramSegment(300, 9.5, 1.0, ctx.getString(R.string.seg_warmup)),
            ProgramSegment(60, 14.0, 1.0, ctx.getString(R.string.seg_sprint)),
            ProgramSegment(120, 9.0, 1.0, ctx.getString(R.string.seg_trot)),
            ProgramSegment(90, 13.0, 1.0, ctx.getString(R.string.seg_tempo)),
            ProgramSegment(120, 9.0, 1.0, ctx.getString(R.string.seg_trot)),
            ProgramSegment(60, 15.0, 1.0, ctx.getString(R.string.seg_sprint)),
            ProgramSegment(120, 9.0, 1.0, ctx.getString(R.string.seg_trot)),
            ProgramSegment(90, 13.0, 1.0, ctx.getString(R.string.seg_tempo)),
            ProgramSegment(120, 9.0, 1.0, ctx.getString(R.string.seg_trot)),
            ProgramSegment(60, 15.0, 1.0, ctx.getString(R.string.seg_sprint)),
            ProgramSegment(300, 9.0, 1.0, ctx.getString(R.string.seg_cooldown))
        )
    )
    return listOf(
        ctx.getString(R.string.template_intervals_title) to intervals,
        ctx.getString(R.string.template_pyramid_title) to pyramid,
        ctx.getString(R.string.template_hills_title) to hills,
        ctx.getString(R.string.template_fartlek_title) to fartlek
    )
}

private data class SegmentEdit(val name: String = "", val durationSec: Int = 60, val speedKmh: Double = 5.0, val inclinePercent: Double = 0.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramEditorScreen(
    initial: ProgramData,
    isNew: Boolean,
    maxSpeed: Double,
    maxIncline: Double,
    showIncline: Boolean,
    onShowMessage: (String) -> Unit,
    onSave: (ProgramData) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val segments = remember(initial) {
        mutableStateListOf<SegmentEdit>().apply {
            initial.segments.forEach { add(SegmentEdit(it.name, it.durationSec, it.speedKmh, it.inclinePercent)) }
            if (isEmpty()) add(SegmentEdit(ctx.getString(R.string.editor_segment_short) + " 1", 300, 5.0, 0.0))
        }
    }
    var name by remember(initial) { mutableStateOf(initial.name) }
    var description by remember(initial) { mutableStateOf(initial.description) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    if (isNew) stringResource(R.string.editor_new_title)
                                    else stringResource(R.string.editor_edit_title),
                                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.editor_total, segments.sumOf { it.durationSec } / 60),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close)) } },
                        actions = {
                            TextButton(onClick = {
                                if (name.isBlank()) {
                                    onShowMessage(ctx.getString(R.string.editor_validate_name)); return@TextButton
                                }
                                if (segments.isEmpty() || segments.any { it.durationSec <= 0 || it.speedKmh <= 0 }) {
                                    onShowMessage(ctx.getString(R.string.editor_validate_segments)); return@TextButton
                                }
                                onSave(ProgramData(name, description, segments.map {
                                    ProgramSegment(
                                        it.durationSec, it.speedKmh, it.inclinePercent,
                                        it.name.ifBlank { ctx.getString(R.string.editor_segment_short) }
                                    )
                                }))
                            }) { Text(stringResource(R.string.save), fontWeight = FontWeight.Bold) }
                        }
                    )
                },
                bottomBar = {
                    Surface(shadowElevation = 8.dp) {
                        Box(Modifier.fillMaxWidth().padding(12.dp)) {
                            Button(
                                onClick = {
                                    val last = segments.lastOrNull()
                                    segments.add(SegmentEdit(
                                        "${ctx.getString(R.string.editor_segment_short)} ${segments.size + 1}",
                                        60,
                                        last?.speedKmh ?: 5.0,
                                        last?.inclinePercent ?: 0.0
                                    ))
                                    scope.launch { listState.animateScrollToItem(segments.size - 1) }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.editor_add_segment), fontSize = 15.sp)
                            }
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                    SlimTextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.editor_name_label), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    SlimTextField(value = description, onValueChange = { description = it }, label = stringResource(R.string.editor_description_label), modifier = Modifier.fillMaxWidth())

                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.editor_segments_title), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        itemsIndexed(segments) { index, seg ->
                            SegmentEditor(
                                index = index, total = segments.size, seg = seg,
                                maxSpeed = maxSpeed,
                                maxIncline = maxIncline,
                                showIncline = showIncline,
                                onChange = { newSeg -> segments[index] = newSeg },
                                onMoveUp = {
                                    if (index > 0) {
                                        val tmp = segments[index - 1]
                                        segments[index - 1] = segments[index]
                                        segments[index] = tmp
                                        scope.launch { listState.animateScrollToItem(index - 1) }
                                    }
                                },
                                onMoveDown = {
                                    if (index < segments.size - 1) {
                                        val tmp = segments[index + 1]
                                        segments[index + 1] = segments[index]
                                        segments[index] = tmp
                                        scope.launch { listState.animateScrollToItem(index + 1) }
                                    }
                                },
                                onDuplicate = {
                                    segments.add(index + 1, seg)
                                    scope.launch { listState.animateScrollToItem(index + 1) }
                                },
                                onDelete = { if (segments.size > 1) segments.removeAt(index) },
                                canDelete = segments.size > 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlimTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) { innerTextField() }
            }
        )
    }
}

@Composable
private fun HoldableStepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean = true, isPlus: Boolean) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val currentOnClick by rememberUpdatedState(onClick)

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(400)
            while (true) {
                currentOnClick()
                delay(80)
            }
        }
    }

    val activeBgColor = if (isPlus) Color(0xFF4CAF50) else Color(0xFFE53935)
    val disabledBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val iconTint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled) activeBgColor else disabledBgColor)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = currentOnClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TimePickerDialog(initialSeconds: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val values = remember { (0..3600 step 5).toList() }
    var selectedIndex by remember { mutableIntStateOf((initialSeconds / 5).coerceIn(0, values.size - 1)) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.editor_pick_time), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(16.dp))

                AndroidView(
                    factory = { ctx ->
                        NumberPicker(ctx).apply {
                            minValue = 0
                            maxValue = values.size - 1
                            value = selectedIndex
                            wrapSelectorWheel = false
                            setFormatter { i -> val sec = values[i]; "%02d:%02d".format(sec / 60, sec % 60) }
                            setOnValueChangedListener { _, _, newVal -> selectedIndex = newVal }
                            try {
                                val f = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
                                f.isAccessible = true
                                (f.get(this) as android.graphics.Paint).color = textColor
                            } catch (_: Exception) {}
                            try {
                                val f = NumberPicker::class.java.getDeclaredField("mInputText")
                                f.isAccessible = true
                                (f.get(this) as android.widget.EditText).setTextColor(textColor)
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.height(180.dp)
                )

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { onConfirm(values[selectedIndex]) }) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}

@Composable
private fun SegmentEditor(
    index: Int, total: Int, seg: SegmentEdit,
    maxSpeed: Double,
    maxIncline: Double,
    showIncline: Boolean,
    onChange: (SegmentEdit) -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, canDelete: Boolean
) {
    var nameState by remember(seg) { mutableStateOf(seg.name) }
    var showTimePicker by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(8.dp)) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(20.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(6.dp))

                BasicTextField(
                    value = nameState,
                    onValueChange = { nameState = it; onChange(seg.copy(name = it)) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                            if (nameState.isEmpty()) {
                                Text(stringResource(R.string.editor_segment_name_placeholder), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 13.sp)
                            }
                            innerTextField()
                        }
                    }
                )

                Spacer(Modifier.width(4.dp))

                Column {
                    Row {
                        IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(14.dp)) }
                        IconButton(onClick = onMoveDown, enabled = index < total - 1, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(14.dp)) }
                    }
                    Row {
                        IconButton(onClick = onDuplicate, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp)) }
                        IconButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp), tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            Spacer(Modifier.height(6.dp))

            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_time), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HoldableStepButton(icon = Icons.Default.Remove, onClick = { onChange(seg.copy(durationSec = (seg.durationSec - 5).coerceAtLeast(5))) }, enabled = seg.durationSec > 5, isPlus = false)
                    Text(
                        text = "%02d:%02d".format(seg.durationSec / 60, seg.durationSec % 60),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(60.dp).clickable { showTimePicker = true }.padding(vertical = 4.dp)
                    )
                    HoldableStepButton(icon = Icons.Default.Add, onClick = { onChange(seg.copy(durationSec = (seg.durationSec + 5).coerceAtMost(3600))) }, enabled = seg.durationSec < 3600, isPlus = true)
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_speed), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HoldableStepButton(
                        icon = Icons.Default.Remove,
                        onClick = { onChange(seg.copy(speedKmh = (seg.speedKmh - 0.1).coerceAtLeast(0.5).roundToTenth())) },
                        enabled = seg.speedKmh > 0.5,
                        isPlus = false
                    )
                    Text(text = "%.1f".format(seg.speedKmh), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.width(60.dp))
                    HoldableStepButton(
                        icon = Icons.Default.Add,
                        onClick = { onChange(seg.copy(speedKmh = (seg.speedKmh + 0.1).coerceAtMost(maxSpeed).roundToTenth())) },
                        enabled = seg.speedKmh < maxSpeed,
                        isPlus = true
                    )
                }
            }

            if (showIncline) {
                Spacer(Modifier.height(2.dp))

                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.editor_incline), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HoldableStepButton(
                            icon = Icons.Default.Remove,
                            onClick = { onChange(seg.copy(inclinePercent = (seg.inclinePercent - 1.0).coerceAtLeast(0.0))) },
                            enabled = seg.inclinePercent > 0.0,
                            isPlus = false
                        )
                        Text(text = "%.0f".format(seg.inclinePercent), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.width(60.dp))
                        HoldableStepButton(
                            icon = Icons.Default.Add,
                            onClick = { onChange(seg.copy(inclinePercent = (seg.inclinePercent + 1.0).coerceAtMost(maxIncline))) },
                            enabled = seg.inclinePercent < maxIncline,
                            isPlus = true
                        )
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialSeconds = seg.durationSec,
            onConfirm = { newSec ->
                onChange(seg.copy(durationSec = newSec))
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }
}

private fun Double.roundToTenth(): Double = (this * 10).roundToInt() / 10.0
