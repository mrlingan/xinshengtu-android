@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.lingan.imagestudio.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lingan.imagestudio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun MaskEditor(image: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, image) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(image) }
    }
    val strokes = remember { mutableStateListOf<MaskStroke>() }
    var brush by remember { mutableFloatStateOf(.06f) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("遮罩局部编辑", style = MaterialTheme.typography.titleLarge)
                Text("红色涂抹区域将请求重绘；遮罩作用于第一张参考图。再次编辑会重新绘制选区。", style = MaterialTheme.typography.bodySmall)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
                    bitmap?.let { source ->
                        val ratio = source.width.toFloat() / source.height
                        val displayWidth = minOf(maxWidth, maxHeight * ratio)
                        Box(Modifier.width(displayWidth).height(displayWidth / ratio)) {
                            Image(source.asImageBitmap(), "第一张参考图", Modifier.fillMaxSize())
                            Canvas(Modifier.fillMaxSize().testTag("mask-canvas").pointerInput(brush, saving) {
                                if (!saving) detectTapGestures { position -> strokes.add(MaskStroke(listOf(MaskPoint((position.x / size.width).coerceIn(0f, 1f), (position.y / size.height).coerceIn(0f, 1f))), brush)) }
                            }.pointerInput(brush, saving) {
                                if (saving) return@pointerInput
                                fun point(position: Offset) = MaskPoint((position.x / size.width).coerceIn(0f, 1f), (position.y / size.height).coerceIn(0f, 1f))
                                detectDragGestures(onDragStart = { strokes.add(MaskStroke(listOf(point(it)), brush)) }, onDrag = { change, _ ->
                                    change.consume()
                                    if (strokes.isNotEmpty()) strokes[strokes.lastIndex] = strokes.last().copy(points = strokes.last().points + point(change.position))
                                })
                            }) {
                                strokes.forEach { stroke ->
                                    val width = stroke.widthFraction * size.width
                                    stroke.points.forEach { drawCircle(Color.Red.copy(alpha = .35f), width / 2, Offset(it.x * size.width, it.y * size.height)) }
                                    stroke.points.zipWithNext().forEach { (a, b) -> drawLine(Color.Red.copy(alpha = .35f), Offset(a.x * size.width, a.y * size.height), Offset(b.x * size.width, b.y * size.height), width, StrokeCap.Round) }
                                }
                            }
                        }
                    } ?: Text("正在读取参考图…", color = Color.White)
                }
                Text("笔刷粗细", style = MaterialTheme.typography.labelLarge)
                Slider(brush, { brush = it }, valueRange = .015f.. .2f, enabled = !saving)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty() && !saving) { Text("撤销") }
                    OutlinedButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty() && !saving) { Text("清空") }
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = {
                        val source = bitmap ?: return@Button
                        val snapshot = strokes.toList()
                        saving = true
                        scope.launch {
                            try {
                                val file = File(context.cacheDir, "mask-${UUID.randomUUID()}.png")
                                withContext(Dispatchers.IO) { MaskRenderer.save(source.width, source.height, snapshot, file) }
                                onApply(file.path)
                            } catch (e: Exception) { error = e.message ?: "遮罩保存失败" }
                            finally { saving = false }
                        }
                    }, enabled = bitmap != null && strokes.isNotEmpty() && !saving, modifier = Modifier.weight(1f)) { Text(if (saving) "保存中…" else "应用选区") }
                }
            }
        }
    }
}
