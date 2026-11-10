@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.lingan.imagestudio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
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
fun AnnotationEditor(image: String?, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var source by remember(image) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(image) { mutableStateOf(image == null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(image) {
        if (image != null) {
            source = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(image) }
            loaded = true
            if (source == null) error = "参考图读取失败，请重新选择"
        }
    }
    val marks = remember(image) { mutableStateListOf<AnnotationMark>() }
    val redo = remember(image) { mutableStateListOf<AnnotationMark>() }
    var tool by remember { mutableStateOf(AnnotationTool.BRUSH) }
    var color by remember { mutableIntStateOf(android.graphics.Color.RED) }
    var brush by remember { mutableFloatStateOf(.012f) }
    var text by remember { mutableStateOf("") }
    var blankSize by remember { mutableStateOf(1024 to 1024) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val typing = tool == AnnotationTool.TEXT && WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val width = source?.width ?: blankSize.first
    val height = source?.height ?: blankSize.second
    fun close() { if (!saving) { if (marks.isEmpty()) onDismiss() else confirmClose = true } }
    fun add(mark: AnnotationMark) {
        if (marks.size >= 1000) { error = "最多 1000 条标注，请先保存"; return }
        marks.add(mark); redo.clear()
    }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("标注画板", style = MaterialTheme.typography.titleLarge)
                Text("画笔/形状拖动绘制；文字点按放置。移动模式可缩放平移。橡皮仅擦除标注。", style = MaterialTheme.typography.labelSmall)
                if (image == null && !typing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("方形" to (1024 to 1024), "横向" to (1536 to 1024), "纵向" to (1024 to 1536)).forEach { (label, dimensions) ->
                        FilterChip(selected = blankSize == dimensions, onClick = { blankSize = dimensions; zoom = 1f; pan = Offset.Zero }, enabled = !saving, label = { Text(label) })
                    }
                }
                if (!typing) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(AnnotationTool.BRUSH to "画笔", AnnotationTool.TEXT to "文字", AnnotationTool.RECTANGLE to "矩形", AnnotationTool.ELLIPSE to "椭圆", AnnotationTool.ERASER to "橡皮", AnnotationTool.MOVE to "移动").forEach { (id, title) ->
                            FilterChip(selected = tool == id, onClick = { tool = id }, enabled = !saving, label = { Text(title) })
                        }
                    }
                }
                if (tool == AnnotationTool.TEXT) OutlinedTextField(text, { text = it.take(200) }, label = { Text("标注文字") }, modifier = Modifier.fillMaxWidth(), maxLines = 3, enabled = !saving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                // Keep the canvas usable while the text keyboard occupies the lower screen.
                if (!typing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("红" to android.graphics.Color.RED, "黄" to android.graphics.Color.YELLOW, "蓝" to android.graphics.Color.BLUE, "黑" to android.graphics.Color.BLACK, "白" to android.graphics.Color.WHITE).forEach { (label, value) ->
                            FilterChip(selected = color == value, onClick = { color = value }, enabled = !saving, label = { Text(label, color = if (value == android.graphics.Color.WHITE) MaterialTheme.colorScheme.onSurface else Color(value)) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tool == AnnotationTool.TEXT) "字号" else "粗细", style = MaterialTheme.typography.labelSmall)
                        Slider(brush, { brush = it }, valueRange = .003f.. .08f, enabled = !saving, modifier = Modifier.weight(1f))
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF303030)), contentAlignment = Alignment.Center) {
                    if (!loaded) Text("正在读取图片…", color = Color.White)
                    else if (image == null || source != null) {
                        val ratio = width.toFloat() / height
                        val displayWidth = minOf(maxWidth, maxHeight * ratio)
                        Canvas(Modifier.width(displayWidth).height(displayWidth / ratio)
                            .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
                            .testTag("annotation-canvas")
                            .pointerInput(tool, saving) {
                                if (tool == AnnotationTool.MOVE && !saving) detectTransformGestures { _, shift, factor, _ ->
                                    zoom = (zoom * factor).coerceIn(1f, 5f)
                                    pan = Offset((pan.x + shift.x).coerceIn(-size.width * 2f, size.width * 2f), (pan.y + shift.y).coerceIn(-size.height * 2f, size.height * 2f))
                                }
                            }
                            .pointerInput(tool, color, brush, text, saving) {
                                if (saving || tool == AnnotationTool.MOVE) return@pointerInput
                                detectTapGestures { position ->
                                    val point = MaskPoint((position.x / size.width).coerceIn(0f, 1f), (position.y / size.height).coerceIn(0f, 1f))
                                    if (tool == AnnotationTool.TEXT && text.isBlank()) error = "先输入标注文字，再点按画板放置"
                                    else {
                                        add(AnnotationMark(tool, listOf(point), color, brush, text))
                                        if (tool == AnnotationTool.TEXT) focus.clearFocus()
                                    }
                                }
                            }
                            .pointerInput(tool, color, brush, saving) {
                                if (saving || tool in listOf(AnnotationTool.TEXT, AnnotationTool.MOVE)) return@pointerInput
                                var active = false
                                fun point(position: Offset) = MaskPoint((position.x / size.width).coerceIn(0f, 1f), (position.y / size.height).coerceIn(0f, 1f))
                                detectDragGestures(onDragStart = { position ->
                                    active = marks.size < 1000
                                    if (active) add(AnnotationMark(tool, listOf(point(position)), color, brush))
                                }, onDrag = { change, _ ->
                                    change.consume()
                                    if (active && marks.isNotEmpty()) {
                                        val previous = marks.last()
                                        if (previous.points.size < 2000) marks[marks.lastIndex] = previous.copy(points = if (tool in listOf(AnnotationTool.RECTANGLE, AnnotationTool.ELLIPSE)) listOf(previous.points.first(), point(change.position)) else previous.points + point(change.position))
                                    }
                                }, onDragEnd = { active = false }, onDragCancel = { if (active && marks.isNotEmpty()) marks.removeAt(marks.lastIndex); active = false })
                            }) {
                            drawIntoCanvas { AnnotationRenderer.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), source, marks.toList()) }
                        }
                    }
                }
                if (!typing) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { redo.add(marks.removeAt(marks.lastIndex)) }, enabled = marks.isNotEmpty() && !saving) { Text("撤销") }
                    TextButton(onClick = { marks.add(redo.removeAt(redo.lastIndex)) }, enabled = redo.isNotEmpty() && !saving) { Text("重做") }
                    TextButton(onClick = { confirmClear = true }, enabled = marks.isNotEmpty() && !saving) { Text("清空") }
                    TextButton(onClick = { zoom = 1f; pan = Offset.Zero }, enabled = !saving) { Text("复位") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { close() }, enabled = !saving, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = {
                        val snapshot = marks.toList(); val bitmap = source
                        saving = true; error = null
                        scope.launch {
                            var output: File? = null
                            try {
                                val file = File(context.cacheDir, "annotation-${UUID.randomUUID()}.png")
                                output = file
                                withContext(Dispatchers.IO) { AnnotationRenderer.save(width, height, bitmap, snapshot, file) }
                                onApply(file.path)
                            } catch (e: Exception) { output?.delete(); error = e.message ?: "标注保存失败" }
                            finally { saving = false }
                        }
                    }, enabled = loaded && (image == null || source != null) && marks.isNotEmpty() && !saving, modifier = Modifier.weight(1f)) { Text(if (saving) "保存中…" else "用作参考图") }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空全部标注？") }, text = { Text("保留原参考图或空白背景，清除画板标注。") },
        confirmButton = { TextButton(onClick = { marks.clear(); redo.clear(); confirmClear = false }) { Text("清空标注") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("返回画板") } })
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("放弃未保存的标注？") }, text = { Text("本次标注尚未应用到参考图。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃标注") } }, dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("继续编辑") } })
}
