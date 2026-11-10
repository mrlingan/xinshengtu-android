@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lingan.imagestudio.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lingan.imagestudio.*
import com.lingan.imagestudio.R
import com.lingan.imagestudio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StudioApp(vm: StudioViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) } }
    val detail = state.tasks.firstOrNull { it.id == detailId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf("创作" to Icons.Outlined.AutoAwesome, "作品库" to Icons.Outlined.GridView, "设置" to Icons.Outlined.Tune)
                    .forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(icon, null) }, label = { Text(label) })
                    }
            }
        },
    ) { padding ->
        if (state.loading) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Box(Modifier.fillMaxSize().padding(padding)) {
            val page = Modifier.widthIn(max = 900.dp).fillMaxSize().align(Alignment.TopCenter)
            when (tab) {
                0 -> CreateScreen(state, vm, page, onSettings = { tab = 2 }, onDetail = { detailId = it })
                1 -> LibraryScreen(state, vm, page, onCreate = { tab = 0 }, onDetail = { detailId = it })
                else -> SettingsScreen(state, vm, page)
            }
        }
    }
    if (detail != null) TaskDetail(detail, vm,
        onDismiss = { detailId = null }, onReuse = { detailId = null; tab = 0 })
}

@Composable
private fun PageHeader(eyebrow: String, title: String, subtitle: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SectionTitle(number: String, title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(10.dp))
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun CreateScreen(state: StudioState, vm: StudioViewModel, modifier: Modifier, onSettings: () -> Unit, onDetail: (String) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(16), vm::importImages)
    val savedProfile = state.settings.selected
    val profile = if (savedProfile.apiMode == "h3-video") savedProfile.copy(model = H3VideoSpec.preset(savedProfile, state.params)) else savedProfile
    val upscale = H3VideoSpec.isUpscale(profile, state.params)
    val imageLimit = if (profile.apiMode == "h3-video") H3VideoSpec.maxImages(profile.model) else 16
    var templatesExpanded by rememberSaveable { mutableStateOf(false) }
    var editingMask by remember { mutableStateOf(false) }
    var editingAnnotation by remember { mutableStateOf(false) }
    var annotationSource by remember { mutableStateOf<String?>(null) }
    if (editingAnnotation) AnnotationEditor(annotationSource, onDismiss = { editingAnnotation = false }, onApply = { path ->
        if (vm.applyAnnotation(path, annotationSource)) editingAnnotation = false
    })
    if (editingMask && state.references.isNotEmpty()) MaskEditor(state.references.first(), onDismiss = { editingMask = false }, onApply = { vm.setMask(it); editingMask = false })
    LazyColumn(modifier.imePadding().testTag("create-scroll"), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            PageHeader("IMAGE STUDIO / 新生图", "让想象，有画面。", "从一句灵感，开始一次创作。") {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Surface(onClick = onSettings, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (profile.apiKey.isBlank()) MaterialTheme.colorScheme.outline else Color(0xFF6A8253), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (profile.apiKey.isBlank()) "连接你的生图 API" else profile.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(if (profile.apiKey.isBlank()) "填写 API Key 与服务地址即可开始" else profile.model, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.ChevronRight, "打开 API 设置", Modifier.size(20.dp))
                }
            }
        }
        if (profile.apiMode == "h3-video") item {
            H3ModeSelector(profile.model, !state.generating && !state.importing) { preset ->
                vm.setParams(state.params.copy(h3Preset = preset, count = 1))
            }
        }
        if (!upscale) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("01", "描述你的灵感", "PROMPT")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("多提示词排队", style = MaterialTheme.typography.bodyMedium)
                        Text("每条提示词之间空一行，最多 20 条，按顺序生成", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(state.batchMode, vm::setBatchMode, enabled = !state.generating)
                }
                OutlinedTextField(value = state.prompt, onValueChange = vm::setPrompt, modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp),
                    placeholder = { Text("例如：清晨薄雾中的山间小屋，暖色灯光透过窗户，电影胶片质感，安静而自由。", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f)) },
                    minLines = 5, maxLines = 12, shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface))
                TextButton(onClick = { templatesExpanded = !templatesExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("灵感模板", modifier = Modifier.weight(1f))
                    Text(if (templatesExpanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
                    Icon(if (templatesExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                AnimatedVisibility(visible = templatesExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("参考 OpenAI 公开示例编写，点击后可修改方括号内容", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "电影感" to "辽阔的海岸线，一位旅人站在悬崖边，海风吹起衣角，自然光，35mm 胶片质感，电影远景。",
                        "产品摄影" to "一瓶琥珀色香水置于浅米色石台上，窗边柔和的晨光，精致玻璃反射，极简高端产品摄影。",
                        "创意插画" to "一座漂浮在云海上的小岛，红色小屋与巨大的树，温暖治愈的手绘插画，柔和配色，丰富细节。",
                        "海报" to "设计一张关于【活动或主题】的海报。突出【主体】，使用【视觉风格】和【配色】；清晰呈现文字：【标题与时间地点】。画面层级清晰，适合印刷或社交媒体发布。",
                        "品牌标志" to "为【品牌名称】设计原创标志。品牌定位是【定位】，受众是【受众】；使用【风格与颜色】，保持造型简洁、易辨识，适用于网站和包装。",
                        "周边商品" to "为【品牌或主题】设计一款【T恤、帆布袋或其他商品】。主体图案为【图案描述】，采用【配色和风格】，在商品实物展示图中清晰呈现设计。",
                        "社交媒体" to "制作一张用于【平台】的【推广或公告】配图。主题：【主题】；主要信息：【文案】；视觉风格：【风格和配色】。构图醒目，文字准确清晰。",
                        "贴纸" to "设计一组围绕【主题】的原创贴纸，包含【角色或物件】。采用【插画风格】和【颜色】，各元素轮廓清晰、互不重叠，适合裁切。",
                        "产品包装" to "设计【产品名称】的包装概念，品牌风格为【风格】，面向【受众】。展示【材质、配色和图案】，准确排版文字：【文字内容】。",
                        "信息图" to "制作关于【主题】的信息图，按清晰层级展示：【要点一】【要点二】【要点三】。采用【视觉风格】、简洁图标和易读中文，准确呈现提供的数据。",
                        "照片编辑" to "以参考图为基础，将【需要修改的部分】调整为【目标效果】。保留人物身份、主体构图及未提及的细节，使光线、透视和材质自然一致。",
                    ).forEach { (label, prompt) -> SuggestionChip(onClick = { vm.setPrompt(prompt); templatesExpanded = false }, label = { Text(label) }, icon = { Icon(Icons.Outlined.NorthEast, null, Modifier.size(14.dp)) }) }
                }
                    }
                }
            }
        }
        if (!upscale || state.references.isNotEmpty() || state.mask != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("02", "参考图", "${state.references.size}/$imageLimit")
                if (upscale) Text(stringResource(R.string.h3_clear_images_for_upscale), color = MaterialTheme.colorScheme.error)
                Text(if (profile.apiMode == "h3-video") stringResource(R.string.h3_reference_hint) else if (profile.apiMode == "xai-video") stringResource(R.string.grok_reference_hint) else if (profile.isVideo) "I2V 模型仅支持一张首帧图片；T2V 模型无需图片。" else "添加图片后将切换为参考图编辑。图片会缩小至最长边 2048px 并转为 PNG。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.references.forEach { path ->
                        Box(Modifier.size(84.dp)) {
                            AsyncImage(File(path), "参考图，点按标注", Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).clickable { annotationSource = path; editingAnnotation = true }, contentScale = ContentScale.Crop)
                            IconButton(onClick = { vm.removeReference(path) }, Modifier.align(Alignment.TopEnd).size(28.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), CircleShape)) {
                                Icon(Icons.Outlined.Close, "移除参考图", Modifier.size(16.dp))
                            }
                        }
                    }
                    if (state.references.size < imageLimit) {
                        Surface(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.importing,
                            modifier = Modifier.size(84.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                if (state.importing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.AddPhotoAlternate, "添加参考图")
                                Spacer(Modifier.height(4.dp)); Text("添加图片", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { annotationSource = null; editingAnnotation = true }, enabled = state.references.size < imageLimit && !state.importing && !state.generating, modifier = Modifier.fillMaxWidth()) { Text("新建空白画板") }
                if (profile.apiMode == "h3-video" && profile.model == "tail_frame" && state.references.size == 2) {
                    TextButton(onClick = vm::swapVideoFrames, enabled = !state.generating) { Text(stringResource(R.string.h3_swap_frames)) }
                }
                if (state.references.isNotEmpty()) Text("点按参考图可打开标注画板。保存后替换该参考图；修改第一张图会清除已有遮罩选区。", style = MaterialTheme.typography.bodySmall)
                if (state.references.isNotEmpty() && !profile.isVideo) {
                    OutlinedButton(onClick = { editingMask = true }, modifier = Modifier.fillMaxWidth()) { Text(if (state.mask == null) "涂抹局部编辑区域" else "重新编辑选区") }
                    if (state.mask != null) {
                        Text("已设置第一张参考图的编辑选区", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.setMask(null) }) { Text("移除选区") }
                    }
                }
                if (profile.isVideo && state.mask != null) {
                    TextButton(onClick = { vm.setMask(null) }) { Text(stringResource(R.string.remove_video_mask)) }
                }
            }
        }
        if (profile.apiMode == "h3-video" || state.videoInputs.names.isNotEmpty()) item {
            H3MediaInputs(if (profile.apiMode == "h3-video") profile.model else "", state.videoInputs,
                !state.importing && !state.generating, vm::importVideoInputs, vm::removeVideoInput)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("03", "画面设置", "PARAMETERS")
                if (profile.apiMode == "responses" || runCatching { ApiUrls.isSiliconFlow(profile) }.getOrDefault(false)) Text("当前接口：每张图片拆成一个独立任务", style = MaterialTheme.typography.bodySmall)
                if (profile.apiMode == "h3-video") {
                    H3ParameterFields(profile.model, state.params, state.videoInputs.sourceDurationMs,
                        !state.generating && !state.importing, vm::setParams)
                } else if (profile.apiMode == "xai-video") {
                    ChoiceField(stringResource(R.string.video_resolution), state.params.videoResolution, VideoApi.xaiResolutions(profile.model).map { it to it }, Modifier.fillMaxWidth()) {
                        vm.setParams(state.params.copy(count = 1, size = "auto", videoResolution = it))
                    }
                    ChoiceField(stringResource(R.string.video_duration), state.params.videoDuration.toString(), (1..15).map { it.toString() to stringResource(R.string.video_seconds, it) }, Modifier.fillMaxWidth()) {
                        vm.setParams(state.params.copy(count = 1, size = "auto", videoDuration = it.toInt()))
                    }
                    ChoiceField(stringResource(R.string.video_aspect_ratio), state.params.videoAspectRatio, VideoApi.xaiAspectRatios.map { it to it }, Modifier.fillMaxWidth()) {
                        vm.setParams(state.params.copy(count = 1, size = "auto", videoAspectRatio = it))
                    }
                    Text(stringResource(R.string.grok_parameters_hint), style = MaterialTheme.typography.bodySmall)
                    if (state.params.count != 1 || state.params.size != "auto" || state.params.videoResolution !in VideoApi.xaiResolutions(profile.model)) {
                        TextButton(onClick = { vm.setParams(GenerationParams()) }) { Text(stringResource(R.string.reset_video_parameters)) }
                    }
                } else if (profile.isVideo) {
                    ChoiceField("视频分辨率", state.params.size, listOf("auto" to "自动（1280×720）") + VideoApi.sizes.map { it to it }, Modifier.fillMaxWidth()) { vm.setParams(GenerationParams(size = it)) }
                    Text("每次生成 1 个视频，时长由服务商决定。T2V 使用文字；I2V 请添加且仅添加一张首帧图。", style = MaterialTheme.typography.bodySmall)
                    if (state.params.count != 1 || state.params.size !in VideoApi.sizes + "auto") TextButton(onClick = { vm.setParams(GenerationParams()) }) { Text("重置视频参数为自动") }
                } else {
                    ParameterFields(state.params, profile.advancedParameters || profile.apiMode == "chatgpt2api", vm::setParams, outputOptions = profile.apiMode != "chatgpt2api")
                    if (profile.apiMode == "chatgpt2api") {
                        Text(stringResource(R.string.chatgpt2api_parameters_hint), style = MaterialTheme.typography.bodySmall)
                        if (state.params.format != "auto" || state.params.transparent) {
                            TextButton(onClick = { vm.setParams(state.params.copy(format = "auto", transparent = false)) }) { Text(stringResource(R.string.chatgpt2api_reset_output)) }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { if (profile.apiKey.isBlank()) onSettings() else vm.generate() },
                    enabled = !state.generating && !state.importing && !state.dataBusy,
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                    Text(if (upscale && !state.generating && profile.apiKey.isNotBlank()) stringResource(R.string.h3_upscale_button) else if (state.generating && profile.isVideo) stringResource(R.string.video_generating_button) else if (state.generating) "正在生成画面…" else if (profile.apiKey.isBlank()) "配置 API，开始创作" else if (profile.isVideo) "生成视频" else if (state.batchMode) "提交 ${splitBatchPrompts(state.prompt).size} 条提示词" else if (state.references.isEmpty()) "生成图片" else "使用参考图生成", fontWeight = FontWeight.SemiBold)
                }
                Text(if (profile.apiMode == "h3-video") stringResource(R.string.h3_local_media_hint) else "图片保存在本机 · 提示词与参考图直接发送给你配置的服务商", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        if (state.generating) item {
            val current = state.tasks.firstOrNull { it.id == state.currentTaskId }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp)); Text(if (current?.isVideo == true) videoStageLabel(current.videoStatus) else "灵感正在成形 · 等待 ${state.queuedCount} 个任务", fontWeight = FontWeight.SemiBold)
                    }
                    if (current?.isVideo == true) {
                        Text(stringResource(R.string.video_local_queue, state.queuedCount), style = MaterialTheme.typography.bodySmall)
                        VideoProgressUpdate(current.videoStatusUpdatedAt)
                        H3Progress(current.videoProgress)
                    }
                    if (state.previewPath != null) {
                        Text("生成中预览 · 最终结果可能不同", style = MaterialTheme.typography.labelSmall)
                        AsyncImage(File(state.previewPath), "生成中的中间预览", Modifier.fillMaxWidth().heightIn(max = 280.dp).aspectRatio(1f), contentScale = ContentScale.Fit)
                    }
                    Text(if (current?.isVideo == true) stringResource(R.string.video_wait_hint) else "生成可能需要几分钟，可切换应用；任务会在后台继续。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = vm::cancelGeneration) { Text("停止整个队列") }
                        if (current != null) TextButton(onClick = { onDetail(current.id) }) { Text("查看任务") }
                    }
                }
            }
        }
        val latest = state.tasks.firstOrNull { it.status !in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED) }
        if (latest != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("↗", "最近一次创作")
                TaskCard(latest, vm, onClick = { onDetail(latest.id) })
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ParameterFields(params: GenerationParams, advanced: Boolean, onChange: (GenerationParams) -> Unit, outputOptions: Boolean = true) {
    var custom by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf("1024") }
    var height by remember { mutableStateOf("1024") }
    var error by remember { mutableStateOf<String?>(null) }
    if (custom) AlertDialog(onDismissRequest = { custom = false }, title = { Text("自定义尺寸") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(width, { width = it.filter(Char::isDigit).take(4) }, label = { Text("宽度 px") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(height, { height = it.filter(Char::isDigit).take(4) }, label = { Text("高度 px") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("宽高为 16 的倍数，最长边 3840；具体尺寸支持取决于模型。", style = MaterialTheme.typography.bodySmall)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val updated = params.copy(size = "${width}x$height")
        runCatching { updated.validate() }.onSuccess { onChange(updated); custom = false }.onFailure { error = it.message }
    }) { Text("应用尺寸") } }, dismissButton = { TextButton(onClick = { custom = false }) { Text("取消") } })
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceField("画幅", params.size, listOf("auto" to "自动", "1024x1024" to "方形 · 1:1", "1536x1024" to "横向 · 3:2", "1024x1536" to "纵向 · 2:3", "2048x2048" to "2K · 方形", "3840x2160" to "4K · 横向", "custom" to "自定义…"), Modifier.weight(1f)) {
            if (it == "custom") { val parts = params.size.split("x"); width = parts.getOrNull(0)?.takeIf { v -> v.toIntOrNull() != null } ?: "1024"; height = parts.getOrNull(1) ?: "1024"; error = null; custom = true }
            else onChange(params.copy(size = it))
        }
        ChoiceField("数量", params.count.toString(), (1..4).map { it.toString() to "$it 张" }, Modifier.weight(1f)) { onChange(params.copy(count = it.toInt())) }
    }
    if (advanced) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceField("质量", params.quality, listOf("auto" to "自动", "low" to "草稿 · Low", "medium" to "标准 · Medium", "high" to "精细 · High", "xhigh" to "超精细 · XHigh", "max" to "极致 · Max"), Modifier.weight(1f)) { onChange(params.copy(quality = it)) }
            if (outputOptions) ChoiceField("格式", params.format, listOf("auto" to "自动", "png" to "PNG", "jpeg" to "JPEG", "webp" to "WebP"), Modifier.weight(1f)) { onChange(params.copy(format = it, transparent = params.transparent && it != "jpeg")) }
        }
        if (outputOptions) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("透明背景", style = MaterialTheme.typography.bodyMedium)
                Text("需要模型支持；PNG / WebP 可用", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Switch(checked = params.transparent, onCheckedChange = { onChange(params.copy(transparent = it)) }, enabled = params.format != "jpeg")
        }
        if (params.quality == "xhigh" || params.quality == "max") Text("XHigh / Max 仅适用于支持这些质量参数的模型。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else Text("当前配置使用精简参数：仅发送模型、提示词、尺寸与数量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ChoiceField(label: String, value: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(value = options.firstOrNull { it.first == value }?.second ?: value, onValueChange = {}, readOnly = true,
            label = { Text(label) }, enabled = enabled, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyMedium)
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}

@Composable
private fun LibraryScreen(state: StudioState, vm: StudioViewModel, modifier: Modifier, onCreate: () -> Unit, onDetail: (String) -> Unit) {
    var folderFilter by rememberSaveable { mutableStateOf("all") }
    var managingFolders by remember { mutableStateOf(false) }
    var addingFolder by remember { mutableStateOf(false) }
    LaunchedEffect(state.collections) { if (folderFilter != "all" && state.collections.none { it.id == folderFilter }) folderFilter = "all" }
    if (managingFolders) CollectionManager(state, vm) { managingFolders = false }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var exportIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val selectedBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.backup(uri, false, exportIds.toSet()) }
    if (confirmBulkDelete) AlertDialog(onDismissRequest = { confirmBulkDelete = false }, title = { Text("删除所选作品？") }, text = { Text("将永久删除选中的 ${selected.size} 个任务及其图片，相册中已保存的图片不受影响。") },
        confirmButton = { TextButton(onClick = { confirmBulkDelete = false; vm.bulkTasks(selected, "delete"); selected = emptySet() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("取消") } })
    val folder = state.collections.firstOrNull { it.id == folderFilter }
    val tasks = state.tasks.filter { task ->
        (folder == null || task.id in folder.taskIds) && (query.isBlank() || task.prompt.contains(query, true) || task.model.contains(query, true)) && when (filter) {
            "已收藏" -> task.favorite
            "已完成" -> task.status == TaskStatus.SUCCEEDED
            "未完成" -> task.status != TaskStatus.SUCCEEDED
            else -> true
        }
    }
    if (addingFolder) AlertDialog(onDismissRequest = { addingFolder = false }, title = { Text("加入收藏夹") }, text = {
        Column {
            if (state.collections.isEmpty()) Text("请先在“管理”中新建收藏夹")
            else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(state.collections, key = { it.id }) { group -> TextButton(onClick = { vm.editCollection("add", group.id, taskIds = selected); addingFolder = false }) { Text(group.name) } }
            }
        }
    }, confirmButton = { TextButton(onClick = { addingFolder = false }) { Text("关闭") } })
    Column(modifier.padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(22.dp))
        PageHeader("YOUR COLLECTION", "灵感的留存", "${state.tasks.sumOf { it.outputs.size }} 张图片 · ${state.tasks.size} 次创作")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceField("收藏夹", folderFilter, listOf("all" to "全部作品") + state.collections.map { group -> group.id to "${group.name} (${state.tasks.count { it.id in group.taskIds }})" }, Modifier.weight(1f)) { folderFilter = it }
            TextButton(onClick = { managingFolders = true }) { Text("管理") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索提示词或模型") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp),
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清除搜索") } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "已收藏", "已完成", "未完成").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selecting = !selecting; selected = emptySet() }, enabled = !state.dataBusy) { Text(if (selecting) "退出多选" else "批量管理") }
            if (selecting) {
                TextButton(onClick = { selected = tasks.filter { it.status !in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED) }.map { it.id }.toSet() }) { Text("选择当前结果") }
                TextButton(onClick = { addingFolder = true }, enabled = selected.isNotEmpty() && !state.dataBusy && !state.generating) { Text("加入收藏夹") }
                if (folder != null) TextButton(onClick = { vm.editCollection("remove", folder.id, taskIds = selected) }, enabled = selected.isNotEmpty() && !state.dataBusy && !state.generating) { Text("移出此收藏夹") }
                TextButton(onClick = { vm.bulkTasks(selected, "favorite") }, enabled = selected.isNotEmpty() && !state.dataBusy && !state.generating) { Text("收藏") }
                TextButton(onClick = { vm.bulkTasks(selected, "unfavorite") }, enabled = selected.isNotEmpty() && !state.dataBusy && !state.generating) { Text("取消收藏") }
                TextButton(onClick = { exportIds = selected.toList(); selectedBackup.launch("新生图-选中作品.zip") }, enabled = selected.isNotEmpty() && !state.dataBusy && !state.generating) { Text("备份选中") }
                TextButton(onClick = { confirmBulkDelete = true }, enabled = selected.isNotEmpty() && !state.dataBusy && !state.generating) { Text("删除 ${selected.size} 项") }
            }
        }
        if (state.dataBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (tasks.isEmpty()) {
            EmptyLibrary(state.tasks.isNotEmpty(), onCreate, Modifier.weight(1f))
        } else LazyVerticalGrid(columns = GridCells.Adaptive(280.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(tasks, key = { it.id }) { task ->
                val selectable = task.status !in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED)
                fun toggle() { if (selectable) selected = if (task.id in selected) selected - task.id else selected + task.id }
                Column {
                    if (selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(task.id in selected, { toggle() }, enabled = selectable && !state.dataBusy)
                        Text(if (task.id in selected) "已选中" else "选择此任务", style = MaterialTheme.typography.labelMedium)
                    }
                    TaskCard(task, vm) { if (selecting) toggle() else onDetail(task.id) }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(filtered: Boolean, onCreate: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(170.dp, 140.dp)) {
            drawRoundRect(Color(0xFFECE9DE), Offset(size.width * .17f, size.height * .12f), Size(size.width * .68f, size.height * .75f), androidx.compose.ui.geometry.CornerRadius(18f))
            drawRoundRect(Color(0xFFF5EDDE), Offset(size.width * .1f, size.height * .02f), Size(size.width * .68f, size.height * .75f), androidx.compose.ui.geometry.CornerRadius(18f))
            drawCircle(Color(0xFFDDB88C), size.width * .09f, Offset(size.width * .58f, size.height * .22f))
            val path = Path().apply { moveTo(size.width * .14f, size.height * .69f); lineTo(size.width * .36f, size.height * .34f); lineTo(size.width * .52f, size.height * .53f); lineTo(size.width * .62f, size.height * .43f); lineTo(size.width * .75f, size.height * .69f); close() }
            drawPath(path, Color(0xFF839078))
        }
        Text(if (filtered) "没有找到相关作品" else "第一张作品，等待你的想象", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(if (filtered) "试着更换关键词或筛选条件" else "生成的图片和每一次尝试，都会留在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onCreate) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("开始创作") }
    }
}

@Composable
private fun TaskCard(task: ImageTask, vm: StudioViewModel, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        if (task.outputs.isNotEmpty()) Box {
            if (task.isVideo) Text("▶ 视频 · 点按查看播放", Modifier.fillMaxWidth().padding(24.dp)) else AsyncImage(vm.store.file(task, task.outputs.first()), "生成结果：${task.prompt}", Modifier.fillMaxWidth().aspectRatio(1.35f), contentScale = ContentScale.Crop)
            if (task.outputs.size > 1) Surface(Modifier.align(Alignment.BottomEnd).padding(10.dp), shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = .6f), contentColor = Color.White) {
                Text("${task.outputs.size} 张", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(task.status)
                Spacer(Modifier.weight(1f))
                Text(date(task.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { vm.favorite(task) }, enabled = task.status !in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED), modifier = Modifier.size(32.dp)) {
                    Icon(if (task.favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, if (task.favorite) "取消收藏" else "收藏", Modifier.size(20.dp), tint = if (task.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(task.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text("${task.model} · ${task.params.size}${if (task.durationMs > 0) " · ${task.durationMs / 1000}s" else ""}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusBadge(status: TaskStatus) {
    val label = when (status) { TaskStatus.QUEUED -> "排队中"; TaskStatus.RUNNING -> "生成中"; TaskStatus.SUCCEEDED -> "已完成"; TaskStatus.FAILED -> "失败"; TaskStatus.CANCELLED -> "已停止"; TaskStatus.INTERRUPTED -> "已中断" }
    val color = when (status) { TaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.secondaryContainer; TaskStatus.FAILED -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.surfaceVariant }
    Surface(shape = RoundedCornerShape(6.dp), color = color) { Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun SettingsScreen(state: StudioState, vm: StudioViewModel, modifier: Modifier) {
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    val context = LocalContext.current
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.backup(uri, false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    if (restoreUri != null) AlertDialog(onDismissRequest = { restoreUri = null }, title = { Text("恢复作品备份？") },
        text = { Text("恢复后保留已有作品，为导入任务创建新记录。重复恢复同一备份会产生重复记录。备份不包含 API 密钥。") },
        confirmButton = { TextButton(onClick = { restoreUri?.let { vm.backup(it, true) }; restoreUri = null }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("取消") } })
    LazyColumn(modifier, contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeader("MAKE IT YOURS", "连接你的创造力", "管理服务商与密钥，让灵感自由流动。") }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("API 配置", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { editing = ApiProfile(name = "新配置") }, enabled = !state.configBusy) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("添加配置") }
            }
        }
        items(state.settings.profiles, key = { it.id }) { profile ->
            val selected = profile.id == state.settings.selected.id
            Card(onClick = { editing = profile }, enabled = !state.configBusy, shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { vm.selectProfile(profile.id) }, enabled = !state.configBusy)
                    Column(Modifier.weight(1f).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                        Text(profile.model, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (profile.apiKey.isBlank()) "尚未填写 API Key" else "API Key 已加密保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Edit, "编辑配置", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Text("你的密钥，你的作品", fontWeight = FontWeight.SemiBold)
                    Text("API 配置通过 Android Keystore 加密后保存在本机。作品库仅保存在应用私有目录，不会上传到应用服务器。卸载应用会删除配置和作品，请及时保存重要图片。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("支持 OpenAI 兼容的 Images API 与 Responses API。服务地址可包含 /v1 或自定义路径；只填写域名时自动补 /v1。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("作品备份与恢复", fontWeight = FontWeight.SemiBold)
                Text("备份作品、参考图和任务参数，不包含 API 配置与密钥。恢复上限 512MB。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { exportBackup.launch("新生图备份-${System.currentTimeMillis()}.zip") }, enabled = !state.dataBusy && !state.generating, modifier = Modifier.fillMaxWidth()) { Text("导出作品备份") }
                OutlinedButton(onClick = { restoreBackup.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !state.dataBusy && !state.generating, modifier = Modifier.fillMaxWidth()) { Text("恢复作品备份") }
                if (state.dataBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        item {
            Text("新生图 1.12.0", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/CookSleep/gpt_image_playground"))) }
                    .onFailure { vm.notify("未找到可用的浏览器") }
            }) {
                Text("灵感来源 · GPT Image Playground"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(14.dp))
            }
        }
    }
    editing?.let { profile -> ProfileEditor(profile, state.configBusy, state.profileMessage,
        state.settings.profiles.size > 1 && state.settings.profiles.any { it.id == profile.id }, vm, onDismiss = { editing = null }) }
}

@Composable
private fun ProfileEditor(profile: ApiProfile, busy: Boolean, message: String?, canDelete: Boolean, vm: StudioViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(profile.id) { vm.clearProfileMessage() }
    var draft by remember(profile.id) { mutableStateOf(profile) }
    var presetId by remember(profile.id) { mutableStateOf(ApiPresets.matching(profile)?.id ?: "custom") }
    var timeout by remember(profile.id) { mutableStateOf(profile.timeoutSeconds.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var models by remember(profile.id) { mutableStateOf<List<String>?>(null) }
    var modelQuery by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("API 配置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            ChoiceField("服务商预设", presetId, ApiPresets.all.map { it.id to it.title } + listOf("custom" to "自定义接口"), Modifier.fillMaxWidth()) { id ->
                if (!busy) {
                    presetId = id
                    ApiPresets.all.firstOrNull { it.id == id }?.let { preset -> draft = preset.applyTo(draft); models = null; vm.clearProfileMessage() }
                }
            }
            Text("预设自动填写地址、接口类型与模型，可继续修改。切换到不同服务商会清空 Key，请填写该平台的 Key。", style = MaterialTheme.typography.bodySmall)
            ApiPresets.all.firstOrNull { it.id == presetId }?.let { preset -> Text(preset.description, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth(), label = { Text("配置名称") }, singleLine = true, enabled = !busy)
            OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it); presetId = "custom" }, Modifier.fillMaxWidth(), label = { Text("API 基础地址") },
                placeholder = { Text("https://api.example.com/v1") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text(if (draft.baseUrl.startsWith("http://", true)) "HTTP 会明文传输密钥和图片，仅建议用于受信任的本地网络。" else "填写基础地址，无需添加 /images/generations") })
            OutlinedTextField(draft.apiKey, { draft = draft.copy(apiKey = it.trim()) }, Modifier.fillMaxWidth(), label = { Text(if (draft.apiMode == "h3-video") stringResource(R.string.h3_card_key) else if (draft.apiMode == "chatgpt2api") stringResource(R.string.chatgpt2api_auth_key) else "API Key") }, singleLine = true, enabled = !busy,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "隐藏密钥" else "显示密钥") } })
            ChoiceField("接口模式", draft.apiMode, listOf("images" to "Images API", "chatgpt2api" to stringResource(R.string.chatgpt2api_mode), "responses" to "Responses API", "siliconflow" to "硅基流动原生接口", "video" to "硅基流动视频", "xai-video" to stringResource(R.string.grok_video_api), "h3-video" to stringResource(R.string.h3_video_api)), Modifier.fillMaxWidth()) { if (!busy) {
                presetId = "custom"
                draft = when (it) {
                    "chatgpt2api" -> draft.copy(apiMode = it, model = "gpt-image-2", advancedParameters = true, streamImages = false, forceBase64 = true)
                    "video" -> draft.copy(apiMode = it, model = VideoApi.models.first(), streamImages = false, forceBase64 = false)
                    "xai-video" -> draft.copy(apiMode = it, model = VideoApi.xaiModels.first(), streamImages = false, forceBase64 = false)
                    "h3-video" -> draft.copy(apiMode = it, model = "reference", streamImages = false, forceBase64 = false)
                    else -> draft.copy(apiMode = it)
                }
                models = null
            } }
            if (draft.apiMode == "chatgpt2api") {
                Text(stringResource(R.string.chatgpt2api_profile_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.chatgpt2api_model_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (draft.apiMode == "siliconflow" || (draft.apiMode == "images" && draft.baseUrl.trim().startsWith("https://api.siliconflow.cn", true))) {
                Text("硅基流动文生图：质量和格式选择自动，关闭流式、Base64 与透明背景；多图逐张生成。", style = MaterialTheme.typography.bodySmall)
            }
            if (draft.apiMode == "h3-video") {
                Text(stringResource(R.string.h3_profile_hint), style = MaterialTheme.typography.bodySmall)
                ChoiceField(stringResource(R.string.h3_preset), draft.model, h3PresetOptions(), Modifier.fillMaxWidth()) {
                    if (!busy) draft = draft.copy(model = it)
                }
            } else if (draft.apiMode == "xai-video") {
                Text(stringResource(R.string.grok_profile_hint), style = MaterialTheme.typography.bodySmall)
                ChoiceField(stringResource(R.string.video_model), draft.model, VideoApi.xaiModels.map { it to it }, Modifier.fillMaxWidth()) { if (!busy) draft = draft.copy(model = it) }
            } else if (draft.isVideo) {
                Text("支持文生视频和单张首帧图生视频。使用硅基流动 /v1 地址；生成时会提交一次任务并轮询结果。", style = MaterialTheme.typography.bodySmall)
                ChoiceField("视频模型", draft.model, VideoApi.models.map { it to it }, Modifier.fillMaxWidth()) { draft = draft.copy(model = it) }
            }
            if (draft.apiMode == "responses") {
                OutlinedTextField(draft.responsesModel, { draft = draft.copy(responsesModel = it) }, label = { Text("Responses 主模型 ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    supportingText = { Text("填写支持 image_generation 工具的主模型；下方仍填写生图模型。") })
                Text("多图数量会拆成逐张任务；最多 20 张。主模型与图片模型都可能产生费用。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(draft.model, { draft = draft.copy(model = it) }, Modifier.fillMaxWidth(), label = { Text("模型 ID") }, singleLine = true, enabled = !busy,
                supportingText = { Text("可手动输入，或获取服务商提供的模型列表") })
            OutlinedButton(onClick = {
                vm.fetchModels(draft.copy(timeoutSeconds = timeout.toIntOrNull() ?: 0)) { models = it; modelQuery = "" }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (busy) "正在处理…" else "获取模型列表")
            }
            OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label = { Text("超时时间（秒）") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("30–1200 秒，建议至少 300 秒") })
            if (!draft.isVideo && draft.apiMode != "chatgpt2api") ToggleRow("发送高级参数", "发送 quality、output_format 与透明背景。兼容性较弱的中转可关闭。", draft.advancedParameters, !busy) { draft = draft.copy(advancedParameters = it) }
            if (draft.isVideo) ToggleRow(stringResource(R.string.allow_http_video), stringResource(R.string.allow_http_video_hint), draft.allowHttpVideoDownload, !busy) { draft = draft.copy(allowHttpVideoDownload = it) }
            if (!draft.isVideo && draft.apiMode != "chatgpt2api") ToggleRow("流式生成预览", "需服务商支持所选接口的流式响应；不支持时请关闭。", draft.streamImages, !busy) { draft = draft.copy(streamImages = it) }
            if (draft.apiMode == "images") ToggleRow("显式请求 Base64", "额外发送 response_format=b64_json。原生 GPT Image 默认无需开启。", draft.forceBase64, !busy) { draft = draft.copy(forceBase64 = it) }
            if (message != null) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.testConnection(draft.copy(timeoutSeconds = timeout.toIntOrNull() ?: 0)) }, Modifier.weight(1f), enabled = !busy) { Text("测试连接") }
                Button(onClick = { vm.saveProfile(draft.copy(timeoutSeconds = timeout.toIntOrNull() ?: 0), onDismiss) }, Modifier.weight(1f), enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("保存并使用")
                }
            }
            if (canDelete) TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("删除此配置", color = MaterialTheme.colorScheme.error) }
        }
    }
    models?.let { available ->
        AlertDialog(onDismissRequest = { models = null }, title = { Text("选择模型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("根据模型名称或服务商返回的能力信息筛选；实际生图支持情况以服务商为准。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(modelQuery, { modelQuery = it }, label = { Text("搜索模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val filtered = available.filter { it.contains(modelQuery, ignoreCase = true) }
                    if (filtered.isEmpty()) Text("没有匹配的模型", style = MaterialTheme.typography.bodySmall)
                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(filtered, key = { it }) { id ->
                            TextButton(onClick = { draft = draft.copy(model = id); models = null }, modifier = Modifier.fillMaxWidth()) {
                                Text(id, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { models = null }) { Text("关闭") } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除 API 配置？") }, text = { Text("将删除本机保存的“${profile.name}”配置及密钥。已有作品会保留。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteProfile(profile.id, onDismiss) }) { Text("删除") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun TaskDetail(task: ImageTask, vm: StudioViewModel, onDismiss: () -> Unit, onReuse: () -> Unit) {
    val studio by vm.state.collectAsStateWithLifecycle()
    var chooseFolders by remember { mutableStateOf(false) }
    var chosenFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    if (chooseFolders) AlertDialog(onDismissRequest = { chooseFolders = false }, title = { Text("作品收藏夹") }, text = {
        Column {
            if (studio.collections.isEmpty()) Text("请先到作品库的“管理”中新建收藏夹")
            else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(studio.collections, key = { it.id }) { group ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(group.id in chosenFolders, { checked -> chosenFolders = if (checked) chosenFolders + group.id else chosenFolders - group.id })
                        Text(group.name)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { vm.editCollection("assign", task.id, folderIds = chosenFolders); chooseFolders = false }, enabled = !studio.dataBusy && !studio.generating) { Text("保存") } }, dismissButton = { TextButton(onClick = { chooseFolders = false }) { Text("取消") } })
    var delete by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    val pager = rememberPagerState { task.outputs.size }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val output = task.outputs.getOrNull(pager.currentPage)?.let { vm.store.file(task, it) }
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(output?.imageMime() ?: "image/png")) { uri ->
        val file = pendingSave?.let(::File)
        if (uri != null && file != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法写入文件") } }
                .onSuccess { vm.notify("图片已保存") }.onFailure { vm.notify("保存失败，请检查存储空间") }
            pendingSave = null
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(topBar = {
                TopAppBar(title = { Text("创作详情", style = MaterialTheme.typography.titleMedium) }, navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                    actions = {
                        IconButton(onClick = { vm.favorite(task) }, enabled = task.status !in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED)) { Icon(if (task.favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, "收藏") }
                        IconButton(onClick = { delete = true }, enabled = task.status !in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED)) { Icon(Icons.Outlined.DeleteOutline, "删除任务") }
                    })
            }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("detail-scroll"), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { StatusBadge(task.status); Text(date(task.createdAt), style = MaterialTheme.typography.bodySmall) } }
                    if (task.outputs.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                HorizontalPager(pager, Modifier.fillMaxWidth().heightIn(max = 460.dp).aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) { index ->
                                    if (task.isVideo) VideoPlayer(vm.store.file(task, task.outputs[index])) else AsyncImage(vm.store.file(task, task.outputs[index]), "生成图片 ${index + 1}，点按放大", Modifier.fillMaxSize().clickable { fullScreen = true }, contentScale = ContentScale.Fit)
                                }
                                Text(if (task.isVideo) "视频已保存到本机 · 点击播放控件播放" else "${pager.currentPage + 1} / ${task.outputs.size} · 左右滑动切换，点按查看大图", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = {
                                        output?.let { file ->
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) scope.launch {
                                                runCatching { withContext(Dispatchers.IO) { saveToGallery(context, file) } }.onSuccess { vm.notify("已保存到相册/新生图") }.onFailure { vm.notify("保存到相册失败，请检查存储空间") }
                                            } else { pendingSave = file.path; saveDocument.launch("新生图_${System.currentTimeMillis()}.${file.extension}") }
                                        }
                                    }, Modifier.weight(1f)) { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (task.isVideo) "保存视频" else "保存图片") }
                                    OutlinedButton(onClick = { output?.let { runCatching { shareImage(context, it) }.onFailure { vm.notify("未找到可用的分享应用") } } }, Modifier.weight(1f)) { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("分享") }
                                }
                            }
                        }
                    }
                    if (task.status in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED)) item {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (task.status == TaskStatus.QUEUED) "排队等待中…" else if (task.isVideo) videoStageLabel(task.videoStatus) else "正在等待服务商返回结果…", Modifier.padding(top = 12.dp))
                        if (task.isVideo) {
                            VideoProgressUpdate(task.videoStatusUpdatedAt)
                            H3Progress(task.videoProgress)
                            Text(stringResource(R.string.video_wait_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (task.error.isNotBlank()) item {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) { Text(task.error, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("提示词", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { clipboard.setText(AnnotatedString(task.prompt)); vm.notify("提示词已复制") }) { Text("复制") }
                        }
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(task.prompt, style = MaterialTheme.typography.bodyLarge) }
                    }
                    item {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                InfoRow("接口", when (task.apiMode) { "h3-video" -> stringResource(R.string.h3_video_api); "chatgpt2api" -> stringResource(R.string.chatgpt2api_mode); "xai-video" -> stringResource(R.string.grok_video_api); "video" -> "硅基流动视频"; "responses" -> "Responses API"; else -> "Images API" })
                                if (task.apiMode == "xai-video") {
                                    InfoRow(stringResource(R.string.video_duration), stringResource(R.string.video_seconds, task.params.videoDuration))
                                    InfoRow(stringResource(R.string.video_resolution), task.params.videoResolution)
                                    InfoRow(stringResource(R.string.video_aspect_ratio), if (task.references.isEmpty()) task.params.videoAspectRatio else stringResource(R.string.video_match_image))
                                }
                                if (task.apiMode == "h3-video" && task.model != "flashvsr_upscale") {
                                    InfoRow(stringResource(R.string.video_duration), stringResource(R.string.video_seconds, H3VideoSpec.seconds(task.model, task.params)))
                                    InfoRow(stringResource(R.string.video_resolution), H3VideoSpec.size(task.model, task.params))
                                }
                                if (task.apiMode == "h3-video" && task.model == "flashvsr_upscale") {
                                    InfoRow(stringResource(R.string.h3_short_edge), "${task.params.upscaleShortEdge}p")
                                }
                                if (task.videoInputs.videos.isNotEmpty()) InfoRow(stringResource(R.string.h3_reference_videos), task.videoInputs.videos.size.toString())
                                if (task.videoInputs.audios.isNotEmpty()) InfoRow(stringResource(R.string.h3_reference_audios), task.videoInputs.audios.size.toString())
                                if (task.videoInputs.sourceVideo.isNotBlank()) InfoRow(stringResource(R.string.h3_source_video), stringResource(R.string.video_seconds, (task.videoInputs.sourceDurationMs / 1000).toInt()))
                                if (task.videoRequestId.isNotBlank()) InfoRow("视频任务 ID", task.videoRequestId)
                                if (task.videoStatus.isNotBlank()) InfoRow("视频状态", stringResource(R.string.video_stage_raw, videoStageLabel(task.videoStatus), task.videoStatus))
                                if (task.responsesModel.isNotBlank()) InfoRow("主模型", task.responsesModel)
                                InfoRow("模型", task.model); InfoRow("API 配置", task.profileName)
                                InfoRow("请求尺寸", task.params.size)
                                if (task.actualSize.isNotBlank()) InfoRow("返回尺寸", task.actualSize)
                                if (task.actualQuality.isNotBlank()) InfoRow("返回质量", task.actualQuality)
                                if (task.actualFormat.isNotBlank()) InfoRow("返回格式", task.actualFormat.uppercase())
                                if (task.actualBackground.isNotBlank()) InfoRow("返回背景", task.actualBackground)
                                if (task.advancedParameters) InfoRow("请求质量 / 格式", "${task.params.quality} / ${task.params.format.uppercase()}")
                                else InfoRow("高级参数", "未发送（精简模式）")
                                InfoRow("数量", if (task.isVideo) "${task.outputs.size} 个视频" else "请求 ${task.params.count} 张 · 返回 ${task.outputs.size} 张")
                                InfoRow("耗时", "${task.durationMs / 1000} 秒")
                                if (task.references.isNotEmpty()) InfoRow("参考图", "${task.references.size} 张")
                                if (task.mask.isNotBlank()) InfoRow("编辑选区", "第一张参考图")
                            }
                        }
                    }
                    if (task.references.isNotEmpty()) item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            task.references.forEach { name -> AsyncImage(vm.store.file(task, name), "使用的参考图", Modifier.size(70.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                        }
                    }
                    if (task.revisedPrompt.isNotBlank()) item {
                        Text("服务商返回的改写提示词", fontWeight = FontWeight.SemiBold)
                        Text(task.revisedPrompt, style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (task.status in listOf(TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.INTERRUPTED)) {
                                if (task.isVideo && task.videoRequestId.isNotBlank()) {
                                    Button(onClick = { vm.resumeVideo(task) }, enabled = !studio.generating && !studio.dataBusy, modifier = Modifier.fillMaxWidth()) { Text("重新拉取原任务") }
                                    Text("使用已保存的任务 ID 查询并下载，不会重新提交生成。查询和下载是否收费取决于服务商。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (!task.isVideo && vm.store.imageResponse(task) != null) {
                                    Button(onClick = { vm.resumeImage(task) }, enabled = !studio.generating && !studio.dataBusy, modifier = Modifier.fillMaxWidth()) { Text("重新下载原图片") }
                                    Text("使用服务端已返回的地址下载，不会重新提交生成；地址可能过期。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text(if (task.isVideo) "未收到服务端任务 ID，无法查询原任务。请先检查服务商记录。" else "此图片接口未保存可查询的服务端任务 ID，无法重新拉取原任务。请先检查服务商记录。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(onClick = { retry = true }, enabled = !studio.generating && !studio.dataBusy, modifier = Modifier.fillMaxWidth()) { Text("重新提交生成") }
                            }
                            OutlinedButton(onClick = { chosenFolders = studio.collections.filter { task.id in it.taskIds }.map { it.id }.toSet(); chooseFolders = true }, enabled = !studio.generating && !studio.dataBusy, modifier = Modifier.fillMaxWidth()) { Text("管理作品收藏夹") }
                            Button(onClick = { vm.reuse(task, onReady = onReuse) }, enabled = task.status !in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED), modifier = Modifier.fillMaxWidth()) { Text("复用提示词与参数") }
                            if (output != null && !task.isVideo) OutlinedButton(onClick = { vm.reuse(task, task.outputs[pager.currentPage], onReuse) }, modifier = Modifier.fillMaxWidth()) { Text("以这张图继续创作") }
                            Text("复用后可先修改，再手动提交生成。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (retry) AlertDialog(onDismissRequest = { retry = false }, title = { Text("重新提交生成？") }, text = { Text("将使用当前 API 配置和原任务参数新建任务，可能产生费用。超时或中断的原请求可能已被服务商处理，请先确认服务商记录。") },
        confirmButton = { TextButton(onClick = { retry = false; vm.retry(task, onReuse) }) { Text("确认重试") } }, dismissButton = { TextButton(onClick = { retry = false }) { Text("取消") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除这次创作？") }, text = { Text("将永久删除此任务、生成图片和任务内的参考图。已保存到相册的图片会保留。") },
        confirmButton = { TextButton(onClick = { delete = false; vm.delete(task, onDismiss) }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
    if (fullScreen && output != null) FullImage(output, onDismiss = { fullScreen = false })
}

@Composable
private fun videoStageLabel(status: String): String = stringResource(when (status) {
    "Uploading" -> R.string.video_stage_uploading
    "cancelled" -> R.string.video_stage_cancelled
    "Submitting" -> R.string.video_stage_submitting
    "Querying" -> R.string.video_stage_querying
    "InQueue" -> R.string.video_stage_queued
    "InProgress", "pending" -> R.string.video_stage_generating
    "Downloading" -> R.string.video_stage_downloading
    "Succeed", "done" -> R.string.video_stage_ready
    "Failed", "failed" -> R.string.video_stage_failed
    "expired" -> R.string.video_stage_expired
    else -> R.string.video_stage_unknown
})

@Composable
private fun VideoProgressUpdate(updatedAt: Long, modifier: Modifier = Modifier) {
    if (updatedAt > 0) {
        Text(stringResource(R.string.video_progress_updated, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(updatedAt))),
            modifier, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(105.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FullImage(file: File, onDismiss: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTransformGestures { _, pan, scale, _ ->
                zoom = (zoom * scale).coerceIn(1f, 5f)
                offset = if (zoom == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2), (offset.y + pan.y).coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2))
            }
        }) {
            AsyncImage(file, "全屏图片，双指缩放", Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
            IconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(16.dp).align(Alignment.TopEnd).background(Color.Black.copy(alpha = .5f), CircleShape)) { Icon(Icons.Outlined.Close, "关闭大图", tint = Color.White) }
            Text("双指缩放 · 拖动查看", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp), color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun date(time: Long): String = SimpleDateFormat("MM.dd  HH:mm", Locale.CHINA).format(Date(time))

@Composable
internal fun VideoPlayer(file: File) {
    key(file.path) {
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
        var view by remember(file.path) { mutableStateOf<android.widget.VideoView?>(null) }
        androidx.compose.runtime.DisposableEffect(lifecycle, file.path) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) view?.pause() }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer); view?.stopPlayback() }
        }
        androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
            android.widget.VideoView(context).apply {
                setVideoPath(file.path)
                val controls = android.widget.MediaController(context)
                controls.setAnchorView(this)
                setMediaController(controls)
                setOnPreparedListener { seekTo(1) }
                view = this
            }
        }, modifier = Modifier.fillMaxSize())
    }
}
