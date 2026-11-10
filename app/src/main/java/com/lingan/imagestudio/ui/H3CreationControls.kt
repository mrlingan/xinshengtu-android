package com.lingan.imagestudio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lingan.imagestudio.R
import com.lingan.imagestudio.data.*
import java.io.File

@Composable
internal fun h3PresetOptions(): List<Pair<String, String>> = listOf(
    "reference" to stringResource(R.string.h3_mode_reference),
    "tail_frame" to stringResource(R.string.h3_mode_tail),
    "easy_15" to stringResource(R.string.h3_mode_15),
    "dmad_20" to stringResource(R.string.h3_mode_20),
    "dmad_30" to stringResource(R.string.h3_mode_30),
    "easy_15_portrait" to stringResource(R.string.h3_mode_15_portrait),
    "dmad_20_portrait" to stringResource(R.string.h3_mode_20_portrait),
    "dmad_30_portrait" to stringResource(R.string.h3_mode_30_portrait),
    "flashvsr_upscale" to stringResource(R.string.h3_mode_upscale),
)

@Composable
internal fun H3ModeSelector(preset: String, enabled: Boolean, onSelect: (String) -> Unit) {
    ChoiceField(stringResource(R.string.h3_preset), preset, h3PresetOptions(),
        Modifier.fillMaxWidth().testTag("h3-mode"), enabled, onSelect)
}

@Composable
internal fun H3ParameterFields(preset: String, params: GenerationParams, sourceDurationMs: Long, enabled: Boolean, onChange: (GenerationParams) -> Unit) {
    var customSize by rememberSaveable(preset) { mutableStateOf(params.size !in H3VideoSpec.sizes + "auto") }
    var customEdge by rememberSaveable(preset) { mutableStateOf(params.upscaleShortEdge !in listOf(480, 720, 1080)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            preset == "flashvsr_upscale" -> {
                val heights = if (sourceDurationMs >= 12_000) listOf(480, 720) else listOf(480, 720, 1080)
                ChoiceField(stringResource(R.string.h3_short_edge), if (customEdge) "custom" else params.upscaleShortEdge.toString(),
                    heights.map { it.toString() to "${it}p" } + ("custom" to stringResource(R.string.h3_custom)), Modifier.fillMaxWidth(), enabled) {
                    customEdge = it == "custom"
                    if (!customEdge) onChange(params.copy(count = 1, upscaleShortEdge = it.toInt()))
                }
                if (customEdge) OutlinedTextField(if (params.upscaleShortEdge == 0) "" else params.upscaleShortEdge.toString(),
                    { onChange(params.copy(count = 1, upscaleShortEdge = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
                    Modifier.fillMaxWidth(), enabled = enabled, label = { Text(stringResource(R.string.h3_short_edge)) },
                    supportingText = { Text(stringResource(R.string.h3_edge_range)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(stringResource(R.string.h3_upscale_hint), style = MaterialTheme.typography.bodySmall)
                if (sourceDurationMs >= 12_000 && params.upscaleShortEdge > 720) {
                    TextButton(onClick = { customEdge = false; onChange(params.copy(upscaleShortEdge = 720)) }, enabled = enabled) { Text(stringResource(R.string.h3_use_720)) }
                }
            }
            H3VideoSpec.isRecipe(preset) -> Text(stringResource(R.string.h3_locked_parameters,
                H3VideoSpec.durations(preset).first(), H3VideoSpec.size(preset, params)))
            else -> {
                ChoiceField(stringResource(R.string.video_resolution), if (customSize) "custom" else params.size,
                    listOf("auto" to stringResource(R.string.h3_auto_size)) + H3VideoSpec.sizes.map { it to it } +
                        ("custom" to stringResource(R.string.h3_custom)), Modifier.fillMaxWidth(), enabled) {
                    customSize = it == "custom"
                    onChange(params.copy(count = 1, size = if (customSize) "1440x1440" else it))
                }
                if (customSize || params.size !in H3VideoSpec.sizes + "auto") {
                    val dimensions = params.size.split("x", limit = 2)
                    val width = dimensions.getOrElse(0) { "" }; val height = dimensions.getOrElse(1) { "" }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(width, { onChange(params.copy(count = 1, size = "${it.filter(Char::isDigit).take(4)}x$height")) },
                            Modifier.weight(1f), label = { Text(stringResource(R.string.h3_width)) }, enabled = enabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(height, { onChange(params.copy(count = 1, size = "${width}x${it.filter(Char::isDigit).take(4)}")) },
                            Modifier.weight(1f), label = { Text(stringResource(R.string.h3_height)) }, enabled = enabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text(stringResource(R.string.h3_size_range), style = MaterialTheme.typography.bodySmall)
                }
                ChoiceField(stringResource(R.string.video_duration), params.videoDuration.toString(),
                    H3VideoSpec.durations(preset).map { it.toString() to stringResource(R.string.video_seconds, it) }, Modifier.fillMaxWidth(), enabled) {
                    onChange(params.copy(count = 1, videoDuration = it.toInt()))
                }
                ChoiceField(stringResource(R.string.h3_steps), params.videoSteps.toString(), listOf(8, 20).map { it.toString() to it.toString() }, Modifier.fillMaxWidth(), enabled) {
                    onChange(params.copy(count = 1, videoSteps = it.toInt()))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.h3_interpolate))
                    Switch(params.videoInterpolate, { onChange(params.copy(videoInterpolate = it)) }, enabled = enabled)
                }
            }
        }
        if (preset != "flashvsr_upscale") Text(stringResource(R.string.h3_parameters_hint), style = MaterialTheme.typography.bodySmall)
        if (runCatching { H3VideoSpec.validateParams(preset, params) }.isFailure) {
            TextButton(onClick = { customSize = false; customEdge = false; onChange(GenerationParams(h3Preset = preset)) }, enabled = enabled) {
                Text(stringResource(R.string.reset_video_parameters))
            }
        }
    }
}

@Composable
internal fun H3MediaInputs(preset: String, inputs: VideoInputPaths, enabled: Boolean,
                           onImport: (List<Uri>, String) -> Unit, onRemove: (String, String) -> Unit) {
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onImport(it, "video") }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onImport(it, "audio") }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onImport(listOf(it), "source") } }
    val allowsReferences = preset == "reference" || H3VideoSpec.isRecipe(preset)
    Column(Modifier.fillMaxWidth().testTag("h3-media"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (preset == "flashvsr_upscale") {
            Text(stringResource(R.string.h3_source_video), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { sourcePicker.launch(arrayOf("video/mp4", "video/quicktime", "video/webm")) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.h3_choose_source))
            }
        }
        if (inputs.sourceVideo.isNotBlank()) {
            MediaInputRow(stringResource(R.string.h3_source_video), inputs.sourceVideo, enabled) { onRemove("source", inputs.sourceVideo) }
            Text(stringResource(R.string.video_seconds, (inputs.sourceDurationMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
            if (preset == "flashvsr_upscale") Box(Modifier.fillMaxWidth().height(210.dp)) { VideoPlayer(File(inputs.sourceVideo)) }
            else Text(stringResource(R.string.h3_remove_incompatible), color = MaterialTheme.colorScheme.error)
        }
        if (allowsReferences || inputs.videos.isNotEmpty()) {
            Text(stringResource(R.string.h3_reference_videos), style = MaterialTheme.typography.titleMedium)
            inputs.videos.forEachIndexed { index, path ->
                key(path) { MediaInputRow(stringResource(R.string.h3_video_index, index + 1), path, enabled) { onRemove("video", path) } }
            }
            if (allowsReferences) OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/mp4", "video/quicktime", "video/webm")) },
                enabled = enabled && inputs.videos.size < 3, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.h3_add_video)) }
        }
        if (allowsReferences || inputs.audios.isNotEmpty()) {
            Text(stringResource(R.string.h3_reference_audios), style = MaterialTheme.typography.titleMedium)
            inputs.audios.forEachIndexed { index, path ->
                key(path) { MediaInputRow(stringResource(R.string.h3_audio_index, index + 1), path, enabled) { onRemove("audio", path) } }
            }
            if (allowsReferences) OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, enabled = enabled && inputs.audios.size < 3,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.h3_add_audio)) }
        }
        if (allowsReferences) Text(stringResource(R.string.h3_media_limits), style = MaterialTheme.typography.bodySmall)
        if (!allowsReferences && (inputs.videos.isNotEmpty() || inputs.audios.isNotEmpty())) {
            Text(stringResource(R.string.h3_remove_incompatible), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MediaInputRow(label: String, path: String, enabled: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label · ${File(path).extension.uppercase()} · ${File(path).length() / 1024} KB", Modifier.weight(1f))
        TextButton(onClick = onRemove, enabled = enabled) { Text(stringResource(R.string.h3_remove_media)) }
    }
}

@Composable
internal fun H3Progress(progress: String) {
    if (progress.isNotBlank()) {
        Text(progress, style = MaterialTheme.typography.bodySmall)
        val percent = Regex("(\\d{1,3})\\s*%").find(progress)?.groupValues?.get(1)?.toIntOrNull()
        if (percent != null && percent in 0..100) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
    }
}
