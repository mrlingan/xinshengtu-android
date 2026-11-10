package com.lingan.imagestudio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lingan.imagestudio.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.SocketTimeoutException

/** Holds a single in-flight request in the app process. Secrets are never put in an Intent. */
class GenerationService : Service() {
    data class Status(val running: Boolean = false, val task: ImageTask? = null, val message: String? = null, val tasks: List<ImageTask> = emptyList(), val previewPath: String? = null)
    private data class Request(val profile: ApiProfile, val prompts: List<String>, val params: GenerationParams, val references: List<String>, val mask: String?, val resume: ImageTask? = null, val videoInputs: VideoInputPaths = VideoInputPaths())
    companion object {
        val status = MutableStateFlow(Status())
        private var pending: Request? = null
        private var job: Job? = null
        private var cancelRequested = false
        fun start(context: Context, profile: ApiProfile, prompt: String, params: GenerationParams, references: List<String>, mask: String? = null, videoInputs: VideoInputPaths = VideoInputPaths()) =
            startBatch(context, profile, listOf(prompt), params, references, mask, videoInputs)
        fun startBatch(context: Context, profile: ApiProfile, prompts: List<String>, params: GenerationParams, references: List<String>, mask: String? = null, videoInputs: VideoInputPaths = VideoInputPaths()) {
            check(!status.value.running) { "已有任务正在生成" }
            require(prompts.size in 1..20 && prompts.all { it.isNotBlank() }) { "一次最多排队 20 条非空提示词" }
            val selected = if (profile.apiMode == "h3-video") profile.copy(model = H3VideoSpec.preset(profile, params)) else profile
            ApiUrls.validate(selected)
            if (profile.apiMode != "h3-video") params.validate()
            if (profile.isVideo) VideoApi.validate(selected, params, references.map { java.io.File(it) }, mask?.let { java.io.File(it) }, videoInputs.files())
            else require(videoInputs.names.isEmpty()) { "当前图片接口不接收音视频素材，请先移除" }
            cancelRequested = false
            val expanded = if (profile.apiMode == "responses" || ApiUrls.isSiliconFlow(profile)) prompts.flatMap { prompt -> List(params.count) { prompt } } else prompts.toList()
            require(expanded.size <= 20) { "当前接口每张图独立任务，一批最多 20 张" }
            pending = Request(selected, expanded, if (profile.apiMode == "responses" || ApiUrls.isSiliconFlow(profile)) params.copy(count = 1) else params, references.toList(), mask, videoInputs = videoInputs)
            status.value = Status(running = true)
            try { ContextCompat.startForegroundService(context, Intent(context, GenerationService::class.java)) }
            catch (e: Exception) { pending = null; status.value = Status(); throw e }
        }
        fun resumeVideo(context: Context, profile: ApiProfile, task: ImageTask) {
            check(!status.value.running)
            require(task.isVideo && task.videoRequestId.isNotBlank())
            require(profile.isVideo && profile.apiMode == task.apiMode && profile.name == task.profileName && (profile.apiMode == "h3-video" || profile.model == task.model)) { "请选择原视频 API 配置与模型，再继续查询" }
            ApiUrls.validate(profile)
            cancelRequested = false
            pending = Request(if (profile.apiMode == "h3-video") profile.copy(model = task.model) else profile, emptyList(), task.params, emptyList(), null, task)
            status.value = Status(running = true)
            try { ContextCompat.startForegroundService(context, Intent(context, GenerationService::class.java)) }
            catch (e: Exception) { pending = null; status.value = Status(); throw e }
        }
        fun resumeImage(context: Context, profile: ApiProfile, task: ImageTask) {
            check(!status.value.running) { "已有任务正在生成" }
            require(!task.isVideo && LocalStore(context).imageResponse(task) != null) { "没有可重新下载的图片地址" }
            cancelRequested = false
            pending = Request(profile, emptyList(), task.params, emptyList(), null, task)
            status.value = Status(running = true)
            try { ContextCompat.startForegroundService(context, Intent(context, GenerationService::class.java)) }
            catch (e: Exception) { pending = null; status.value = Status(); throw e }
        }
        fun cancel() { cancelRequested = true; job?.cancel() }
    }
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ImageStudio:Generation").apply { setReferenceCounted(false); acquire(25 * 60 * 1000L) }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("generation", "图片生成", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "generation")
            .setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("新生图正在创作")
            .setContentText("可以切换应用，完成后作品会保存到作品库")
            .setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1001, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = pending
        pending = null
        if (request == null) { stopSelf(startId); return START_NOT_STICKY }
        job = scope.launch {
            val store = LocalStore(applicationContext)
            val tasks = mutableListOf<ImageTask>()
            var message: String? = null
            var active: ImageTask? = null
            var previewPath: String? = null
            val previewDir = java.io.File(cacheDir, "stream-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            fun publish() { status.value = Status(true, active, tasks = tasks.toList(), previewPath = previewPath) }
            try {
                // Copy every queued task and its references before making any paid requests.
                request.resume?.let { tasks.add(it.copy(status = TaskStatus.QUEUED, error = "")) }
                for (prompt in request.prompts) {
                    if (cancelRequested) throw CancellationException()
                    withContext(Dispatchers.IO) {
                        tasks.add(store.prepare(ImageTask(prompt = prompt, profileName = request.profile.name,
                            model = request.profile.model, params = request.params, advancedParameters = request.profile.advancedParameters,
                            status = TaskStatus.QUEUED, apiMode = request.profile.apiMode, responsesModel = request.profile.responsesModel), request.references, request.mask, request.videoInputs))
                    }
                    publish()
                }
                for (index in tasks.indices) {
                    if (cancelRequested) throw CancellationException()
                    wakeLock?.acquire((if (request.profile.isVideo) 3720 else request.profile.timeoutSeconds + 120) * 1000L)
                    val started = android.os.SystemClock.elapsedRealtime()
                    var task = tasks[index].copy(status = TaskStatus.RUNNING)
                    active = task
                    previewPath = null
                    tasks[index] = task
                    withContext(Dispatchers.IO) { store.save(task) }
                    publish()
                    try {
                        if (task.isVideo) {
                            withContext(Dispatchers.IO) {
                                VideoApi().generate(request.profile, task.prompt, task.params, task.references.map { store.file(task, it) }, store.file(task, "output-1.mp4"), task.videoRequestId,
                                    onSubmitted = { id -> task = task.copy(videoRequestId = id); tasks[index] = task; store.save(task); publish() },
                                    onStatus = { state -> task = task.copy(videoStatus = state, videoStatusUpdatedAt = System.currentTimeMillis()); tasks[index] = task; store.save(task); publish() },
                                    cachedDownloadUrl = store.videoDownloadUrl(task),
                                    onDownloadReady = { url -> store.saveVideoDownload(task, url) },
                                    inputs = task.videoInputs.files { store.file(task, it) },
                                    onProgress = { progress -> task = task.copy(videoProgress = progress); tasks[index] = task })
                                val retriever = android.media.MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(store.file(task, "output-1.mp4").path)
                                    require((retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0) { "返回的视频不可播放" }
                                    val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                    val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                    if (width != null && height != null) task = task.copy(actualSize = "${width}x${height}")
                                } finally { retriever.release() }
                                tasks[index] = task.copy(status = TaskStatus.SUCCEEDED, videoStatus = if (request.profile.apiMode in listOf("xai-video", "h3-video")) "done" else "Succeed", outputs = listOf("output-1.mp4"), actualFormat = "mp4", durationMs = android.os.SystemClock.elapsedRealtime() - started).also { store.save(it) }
                            }
                        } else {
                        val cached = if (request.resume != null) withContext(Dispatchers.IO) { requireNotNull(store.imageResponse(task)) { "图片下载信息已丢失，无法重新拉取" } } else null
                        val result = if (cached != null) ImageApi().downloadResult(org.json.JSONObject(cached)) else ImageApi().generate(request.profile, task.prompt, task.params, task.references.map { store.file(task, it) }, task.mask.takeIf { it.isNotBlank() }?.let { store.file(task, it) }, onResponse = { response -> withContext(Dispatchers.IO) { store.saveImageResponse(task, response) } }) { bytes ->
                            val file = java.io.File(previewDir, "${java.util.UUID.randomUUID()}.png")
                            file.writeBytes(bytes)
                            scope.launch {
                                if (active?.id == task.id && active?.status == TaskStatus.RUNNING && status.value.running) {
                                    previewPath = file.path
                                    publish()
                                }
                            }
                        }
                        withContext(Dispatchers.IO) { tasks[index] = store.saveResult(task, result, android.os.SystemClock.elapsedRealtime() - started) }
                        }
                    } catch (e: Exception) {
                        if (tasks[index].status != TaskStatus.SUCCEEDED) {
                            val error = when (e) {
                                is CancellationException -> "已停止等待。服务商可能已开始生成并计费，请先检查服务商记录。"
                                is SocketTimeoutException -> "等待超时，服务商可能仍在处理，请检查服务商记录。"
                                else -> (e.message ?: "生成失败").replace(request.profile.apiKey, "[已隐藏]")
                            }
                            tasks[index] = task.copy(status = if (e is CancellationException) TaskStatus.CANCELLED else TaskStatus.FAILED,
                                error = error, durationMs = android.os.SystemClock.elapsedRealtime() - started)
                            withContext(NonCancellable + Dispatchers.IO) { store.save(tasks[index]) }
                        }
                        if (e is CancellationException) throw e
                    }
                    active = tasks[index]
                    previewPath = null
                    publish()
                    withContext(Dispatchers.IO) { previewDir.listFiles().orEmpty().forEach { it.delete() } }
                }
            } catch (e: Exception) {
                message = if (e is CancellationException) "已停止队列，尚未开始的任务不会提交" else (e.message ?: "队列准备失败").replace(request.profile.apiKey, "[已隐藏]")
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        tasks.indices.filter { tasks[it].status in listOf(TaskStatus.QUEUED, TaskStatus.RUNNING) }.forEach { index ->
                            tasks[index] = tasks[index].copy(status = TaskStatus.CANCELLED, error = message ?: "队列已停止")
                            runCatching { store.save(tasks[index]) }
                        }
                    }
                    withContext(Dispatchers.IO) { previewDir.deleteRecursively() }
                    pending = null
                    status.value = Status(false, tasks.lastOrNull(), message, tasks.toList())
                    job = null
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); wakeLock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf(startId) }
}
