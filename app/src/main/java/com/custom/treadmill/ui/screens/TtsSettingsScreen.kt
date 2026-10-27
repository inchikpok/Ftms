package com.custom.treadmill.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.treadmill.R
import com.custom.treadmill.ui.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()

    var startTemplate by remember { mutableStateOf(settings.ttsStartTemplate) }
    var segmentTemplate by remember { mutableStateOf(settings.ttsSegmentTemplate) }
    var finishTemplate by remember { mutableStateOf(settings.ttsFinishTemplate) }
    var pauseText by remember { mutableStateOf(settings.ttsPauseText) }
    var resumeText by remember { mutableStateOf(settings.ttsResumeText) }

    // Значения по умолчанию из ресурсов — для кнопки "Сбросить"
    val defaultStart = stringResource(R.string.tts_default_start)
    val defaultSegment = stringResource(R.string.tts_default_segment)
    val defaultFinish = stringResource(R.string.tts_default_finish)
    val defaultPause = stringResource(R.string.tts_default_pause)
    val defaultResume = stringResource(R.string.tts_default_resume)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tts_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    TextButton(onClick = {
                        vm.updateSettings {
                            it.copy(
                                ttsStartTemplate = startTemplate,
                                ttsSegmentTemplate = segmentTemplate,
                                ttsFinishTemplate = finishTemplate,
                                ttsPauseText = pauseText,
                                ttsResumeText = resumeText
                            )
                        }
                        onBack()
                    }) { Text(stringResource(R.string.save), fontWeight = FontWeight.Bold) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.tts_variables_title), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tts_variables_start), fontSize = 12.sp)
                    Text(stringResource(R.string.tts_variables_segment), fontSize = 12.sp)
                    Text(stringResource(R.string.tts_variables_finish), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tts_variables_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            TtsField(
                value = startTemplate,
                onValueChange = { startTemplate = it },
                label = stringResource(R.string.tts_start_label),
                onListen = { vm.testSpeak(startTemplate) }
            )

            TtsField(
                value = segmentTemplate,
                onValueChange = { segmentTemplate = it },
                label = stringResource(R.string.tts_segment_label),
                onListen = { vm.testSpeak(segmentTemplate) }
            )

            TtsField(
                value = finishTemplate,
                onValueChange = { finishTemplate = it },
                label = stringResource(R.string.tts_finish_label),
                onListen = { vm.testSpeak(finishTemplate) }
            )

            TtsField(
                value = pauseText,
                onValueChange = { pauseText = it },
                label = stringResource(R.string.tts_pause_label),
                onListen = { vm.testSpeak(pauseText) },
                singleLine = true
            )

            TtsField(
                value = resumeText,
                onValueChange = { resumeText = it },
                label = stringResource(R.string.tts_resume_label),
                onListen = { vm.testSpeak(resumeText) },
                singleLine = true
            )

            TextButton(
                onClick = {
                    startTemplate = defaultStart
                    segmentTemplate = defaultSegment
                    finishTemplate = defaultFinish
                    pauseText = defaultPause
                    resumeText = defaultResume
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.tts_reset_defaults))
            }
        }
    }
}

@Composable
private fun TtsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onListen: () -> Unit,
    singleLine: Boolean = false
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            minLines = if (singleLine) 1 else 2,
            singleLine = singleLine
        )
        TextButton(
            onClick = onListen,
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.listen), fontSize = 12.sp)
        }
    }
}
