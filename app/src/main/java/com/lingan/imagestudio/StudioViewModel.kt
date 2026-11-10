package com.lingan.imagestudio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingan.imagestudio.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

data class StudioState(
    val loading: Boolean = true,
    val dataBusy: Boolean = false,
    val settings: Settings = Settings(),
    val tasks: List<ImageTask> = emptyList(),
    val collections: List<ArtworkCollection> = emptyList(),
    val collectionMessage: String? = null,
    val prompt: String = "",
    val batchMode: Boolean = false,
    val queuedCount: Int = 0,
    val previewPath: String? = null,
    val params: GenerationParams = GenerationParams(),
    val references: List<String> = emptyList(),
    val mask: String? = null,
    val videoInputs: VideoInputPaths = VideoInputPaths(),
    val generating: Boolean = false,
    val importing: Boolean = false,
    val configBusy: Boolean = false,
    val profileMessage: String? = null,
    val currentTaskId: String? = null,
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    val store = LocalStore(application)
    private val secureSettings = SecureSettings(application)
    private val api = ImageApi()
    private val mutable = MutableStateFlow(StudioState())
    val state = mutable.asStateFlow()
    private val messages = Channel<String>(Channel.UNLIMITED)
    val notices = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { runCatching { secureSettings.read() } }
            val history = withContext(Dispatchers.IO) { runCatching { store.load(preserveRunning = GenerationService.status.value.running) } }
            mutable.update { it.copy(loading = false, settings = settings.getOrDefault(Settings()), tasks = history.getOrNull()?.first.orEmpty()) }
            val folders = withContext(Dispatchers.IO) { runCatching { CollectionStore(getApplication()).read() } }
            mutable.update { it.copy(collections = folders.getOrDefault(emptyList())) }
            if (folders.isFailure) notify("收藏夹读取失败，原文件已保留")
            if (settings.isFailure) notify("无法解密 API 配置，请重新填写并保存。原加密数据保留至重新保存。")
            if (history.isFailure) notify("历史记录读取失败，请检查存储空间")
            if ((history.getOrNull()?.second ?: 0) > 0) notify("部分历史记录损坏，已保留原文件供恢复")
            launch {
                GenerationService.status.collect { run ->
                    if (run.message != null) notify(run.message)
                    val finalTasks = if (!run.running) withContext(Dispatchers.IO) { store.load().first } else null
                    mutable.update { current ->
                        val updates = (run.tasks + listOfNotNull(run.task)).distinctBy { it.id }.filter { store.directory(it.id).exists() }
                        current.copy(generating = run.running, previewPath = run.previewPath, queuedCount = updates.count { it.status == TaskStatus.QUEUED }, currentTaskId = run.task?.id ?: current.currentTaskId,
                            tasks = finalTasks ?: (current.tasks.filterNot { old -> updates.any { it.id == old.id } } + updates).sortedByDescending { it.createdAt })
                    }
                }
            }
        }
    }

    fun notify(message: String) { messages.trySend(message) }
    fun clearProfileMessage() { mutable.update { it.copy(profileMessage = null) } }
    fun setBatchMode(value: Boolean) { mutable.update { it.copy(batchMode = value) } }
    fun setPrompt(value: String) { mutable.update { it.copy(prompt = value) } }
    fun setParams(value: GenerationParams) { mutable.update { it.copy(params = value) } }
    fun removeReference(path: String) { mutable.update { it.copy(references = it.references - path, mask = if (it.references.firstOrNull() == path) null else it.mask) } }

    fun applyAnnotation(path: String, original: String?): Boolean {
        val current = state.value
        val limit = if (current.settings.selected.apiMode == "h3-video") H3VideoSpec.maxImages(H3VideoSpec.preset(current.settings.selected, current.params)) else 16
        if (original == null && current.references.size >= limit) { File(path).delete(); notify("当前模式最多添加 $limit 张参考图"); return false }
        if (original != null && original !in current.references) { File(path).delete(); notify("原参考图已移除，请重新选择"); return false }
        mutable.update { it.copy(references = if (original == null) it.references + path else it.references.map { ref -> if (ref == original) path else ref },
            mask = if (original != null && original == it.references.firstOrNull()) null else it.mask) }
        notify(if (original == null) "画板已添加为参考图" else "已替换为标注后的参考图")
        return true
    }

    fun swapVideoFrames() { mutable.update { if (it.references.size == 2) it.copy(references = it.references.reversed(), mask = null) else it } }

    fun removeVideoInput(kind: String, path: String = "") { mutable.update { current ->
        val inputs = current.videoInputs
        current.copy(videoInputs = when (kind) {
            "video" -> inputs.copy(videos = inputs.videos - path)
            "audio" -> inputs.copy(audios = inputs.audios - path)
            "source" -> inputs.copy(sourceVideo = "", sourceDurationMs = 0)
            else -> VideoInputPaths()
        })
    } }

    fun importVideoInputs(uris: List<Uri>, kind: String) {
        if (state.value.importing || state.value.generating || uris.isEmpty()) return
        val inputs = state.value.videoInputs
        val capacity = when (kind) { "source" -> 1; "audio" -> 3 - inputs.audios.size; else -> 3 - inputs.videos.size }
        if (capacity <= 0) { notify("参考视频和音频各最多 3 个"); return }
        mutable.update { it.copy(importing = true) }
        viewModelScope.launch {
            val imported = mutableListOf<MediaInputStore.Imported>()
            try {
                withContext(Dispatchers.IO) { uris.take(capacity).forEach { imported.add(MediaInputStore(getApplication()).import(it, kind == "audio")) } }
                mutable.update { current -> current.copy(videoInputs = when (kind) {
                    "source" -> current.videoInputs.copy(sourceVideo = imported.first().path, sourceDurationMs = imported.first().durationMs)
                    "audio" -> current.videoInputs.copy(audios = current.videoInputs.audios + imported.map { it.path })
                    else -> current.videoInputs.copy(videos = current.videoInputs.videos + imported.map { it.path })
                }) }
                if (uris.size > capacity) notify("已添加前 $capacity 个素材")
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { imported.forEach { File(it.path).delete() } }
                if (e is CancellationException) throw e
                notify(e.message ?: "素材导入失败")
            } finally { mutable.update { it.copy(importing = false) } }
        }
    }

    fun setMask(path: String?) { mutable.update { it.copy(mask = path) } }

    fun importImages(uris: List<Uri>) {
        if (state.value.importing || state.value.loading) return
        val current = state.value
        val limit = if (current.settings.selected.apiMode == "h3-video") H3VideoSpec.maxImages(H3VideoSpec.preset(current.settings.selected, current.params)) else 16
        val capacity = limit - current.references.size
        if (capacity <= 0) { notify("当前模式最多添加 $limit 张参考图"); return }
        mutable.update { it.copy(importing = true) }
        viewModelScope.launch {
            val files = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) { uris.take(capacity).forEach { files.add(store.importImage(it)) } }
                mutable.update { it.copy(references = it.references + files) }
                if (uris.size > capacity) notify("已添加前 $capacity 张图片，最多支持 $limit 张")
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { files.forEach { File(it).delete() } }
                if (e is CancellationException) throw e
                notify(e.message ?: "参考图导入失败")
            } finally { mutable.update { it.copy(importing = false) } }
        }
    }

    fun saveProfile(profile: ApiProfile, onSaved: () -> Unit) {
        if (state.value.configBusy) return
        try { ApiUrls.validate(profile) } catch (e: Exception) {
            mutable.update { it.copy(profileMessage = e.message ?: "配置无效") }; return
        }
        val cleaned = profile.copy(name = profile.name.trim(), baseUrl = profile.baseUrl.trim().trimEnd('/'), model = profile.model.trim())
        val settings = state.value.settings
        val list = if (settings.profiles.any { it.id == cleaned.id }) settings.profiles.map { if (it.id == cleaned.id) cleaned else it }
            else settings.profiles + cleaned
        persistSettings(Settings(list, cleaned.id), onSaved)
    }

    fun selectProfile(id: String) {
        if (state.value.settings.profiles.none { it.id == id }) return
        persistSettings(state.value.settings.copy(selectedId = id))
    }

    fun deleteProfile(id: String, onSaved: () -> Unit) {
        val settings = state.value.settings
        if (settings.profiles.size == 1) { notify("至少保留一个 API 配置"); return }
        val profiles = settings.profiles.filterNot { it.id == id }
        persistSettings(Settings(profiles, if (settings.selected.id == id) profiles.first().id else settings.selected.id), onSaved)
    }

    private fun persistSettings(settings: Settings, onSaved: () -> Unit = {}) {
        if (state.value.configBusy) return
        mutable.update { it.copy(configBusy = true, profileMessage = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { secureSettings.write(settings) }
                mutable.update { it.copy(settings = settings) }
                onSaved()
            } catch (_: Exception) { mutable.update { it.copy(profileMessage = "配置保存失败，请检查设备存储空间") } }
            finally { mutable.update { it.copy(configBusy = false) } }
        }
    }

    fun testConnection(profile: ApiProfile) {
        if (state.value.configBusy) return
        mutable.update { it.copy(configBusy = true, profileMessage = null) }
        viewModelScope.launch {
            try {
                val result = api.testConnection(profile)
                mutable.update { it.copy(profileMessage = result) }
            }
            catch (e: Exception) { mutable.update { it.copy(profileMessage = friendlyError(e, profile.apiKey) + if (profile.apiMode == "h3-video") "\n瞬映连接测试使用 GET /api/v1/presets，不提交生成任务。" else "\n连接测试使用 GET /models；不支持该接口的服务商仍可能支持生图。") } }
            finally { mutable.update { it.copy(configBusy = false) } }
        }
    }

    fun fetchModels(profile: ApiProfile, onResult: (List<String>) -> Unit) {
        if (state.value.configBusy) return
        mutable.update { it.copy(configBusy = true, profileMessage = null) }
        viewModelScope.launch {
            try { onResult(api.listModels(profile)) }
            catch (e: Exception) {
                mutable.update { it.copy(profileMessage = friendlyError(e, profile.apiKey) + "\n如服务商不支持模型列表，可手动填写模型 ID。") }
            } finally { mutable.update { it.copy(configBusy = false) } }
        }
    }

    fun backup(uri: Uri, restore: Boolean, selectedIds: Set<String>? = null) {
        if (state.value.dataBusy || GenerationService.status.value.running) { notify("请等待当前任务完成"); return }
        val tasks = state.value.tasks.filter { selectedIds == null || it.id in selectedIds }
        mutable.update { it.copy(dataBusy = true) }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val backup = BackupStore(getApplication(), store)
                    if (restore) backup.restore(uri) else { backup.export(uri, tasks, includeEmptyCollections = selectedIds == null); tasks.size }
                }
                if (restore) {
                    val loaded = withContext(Dispatchers.IO) { store.load().first }
                    mutable.update { it.copy(tasks = loaded) }
                    refreshCollections()
                }
                notify(if (restore) "已恢复 $count 个任务，已有作品保留" else "已备份 $count 个任务（不含 API 密钥）")
            } catch (e: Exception) { notify(e.message ?: "备份操作失败") }
            finally { mutable.update { it.copy(dataBusy = false) } }
        }
    }

    fun bulkTasks(ids: Set<String>, operation: String) {
        if (state.value.dataBusy || GenerationService.status.value.running || ids.isEmpty()) return
        val selected = state.value.tasks.filter { it.id in ids && it.status !in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED) }
        mutable.update { it.copy(dataBusy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    selected.forEach { task ->
                        when (operation) {
                            "delete" -> store.delete(task)
                            "favorite" -> store.save(task.copy(favorite = true))
                            "unfavorite" -> store.save(task.copy(favorite = false))
                            else -> error("不支持的批量操作")
                        }
                    }
                }
                notify("已处理 ${selected.size} 个任务")
            } catch (e: Exception) { notify(e.message ?: "部分操作未完成，请检查作品库") }
            finally {
                val loaded = withContext(Dispatchers.IO) { store.load().first }
                withContext(Dispatchers.IO) { runCatching { CollectionStore(getApplication()).prune(loaded.map { it.id }.toSet()) }.onFailure { notify("作品已处理，但收藏夹关联清理失败") } }
                refreshCollections()
                mutable.update { it.copy(tasks = loaded, dataBusy = false) }
            }
        }
    }

    private suspend fun refreshCollections() {
        val folders = withContext(Dispatchers.IO) { runCatching { CollectionStore(getApplication()).read() } }
        folders.onSuccess { list -> mutable.update { it.copy(collections = list) } }.onFailure { notify("收藏夹读取失败，原文件已保留") }
    }

    fun editCollection(action: String, id: String = "", name: String = "", taskIds: Set<String> = emptySet(), folderIds: Set<String> = emptySet()) {
        if (state.value.dataBusy || GenerationService.status.value.running) { notify("请等待当前任务完成"); return }
        val validTasks = state.value.tasks.map { it.id }.toSet()
        mutable.update { it.copy(dataBusy = true, collectionMessage = null) }
        viewModelScope.launch {
            try {
                val folders = withContext(Dispatchers.IO) {
                    val store = CollectionStore(getApplication())
                    when (action) {
                        "create" -> store.create(name)
                        "rename" -> store.rename(id, name)
                        "delete" -> store.delete(id)
                        "add" -> store.membership(id, taskIds.intersect(validTasks), true)
                        "remove" -> store.membership(id, taskIds.intersect(validTasks), false)
                        "assign" -> { require(id in validTasks); store.assign(id, folderIds) }
                        else -> error("不支持的收藏夹操作")
                    }
                }
                mutable.update { it.copy(collections = folders) }
            } catch (e: Exception) { mutable.update { it.copy(collectionMessage = e.message ?: "收藏夹操作失败") }; notify(e.message ?: "收藏夹操作失败") }
            finally { mutable.update { it.copy(dataBusy = false) } }
        }
    }

    fun resumeVideo(task: ImageTask) {
        try { GenerationService.resumeVideo(getApplication(), state.value.settings.selected, task) }
        catch (e: Exception) { notify(e.message ?: "继续查询失败") }
    }

    fun resumeImage(task: ImageTask) {
        try { GenerationService.resumeImage(getApplication(), state.value.settings.selected, task) }
        catch (e: Exception) { notify(e.message ?: "重新下载失败") }
    }

    fun retry(task: ImageTask, onStarted: () -> Unit) {
        if (state.value.generating || state.value.dataBusy) { notify("请等待当前任务完成"); return }
        viewModelScope.launch {
            try {
                val refs = withContext(Dispatchers.IO) { store.copyReferences(task) }
                check(!state.value.dataBusy) { "请等待备份操作完成" }
                val inputs = withContext(Dispatchers.IO) { store.copyVideoInputs(task) }
                val params = if (task.apiMode == "h3-video") task.params.copy(h3Preset = task.model) else task.params
                GenerationService.start(getApplication(), state.value.settings.selected, task.prompt, params, refs, task.mask.takeIf { it.isNotBlank() }?.let { store.file(task, it).path }, inputs)
                onStarted()
            } catch (e: Exception) { notify(e.message ?: "重试失败") }
        }
    }

    fun generate() {
        val snapshot = state.value
        if (snapshot.generating || snapshot.importing || snapshot.loading || snapshot.dataBusy) return
        val profile = snapshot.settings.selected
        try {
            ApiUrls.validate(profile)
            if (profile.apiMode != "h3-video") snapshot.params.validate()
            require(snapshot.prompt.isNotBlank() || H3VideoSpec.isUpscale(profile, snapshot.params)) { "请先描述你想生成的画面" }
        } catch (e: Exception) { notify(e.message ?: "请检查参数"); return }
        try {
            val prompts = if (H3VideoSpec.isUpscale(profile, snapshot.params)) listOf("视频变清晰") else if (snapshot.batchMode) splitBatchPrompts(snapshot.prompt) else listOf(snapshot.prompt.trim())
            GenerationService.startBatch(getApplication(), profile, prompts, snapshot.params, snapshot.references, snapshot.mask, snapshot.videoInputs)
        } catch (e: Exception) { notify("无法启动后台任务：${e.message ?: "请重试"}") }
    }

    fun cancelGeneration() { GenerationService.cancel() }

    private fun updateTask(task: ImageTask) {
        mutable.update { current -> current.copy(tasks = (current.tasks.filterNot { it.id == task.id } + task).sortedByDescending { it.createdAt }) }
    }

    fun favorite(task: ImageTask) {
        if (task.status in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED) || state.value.dataBusy || GenerationService.status.value.running) return
        viewModelScope.launch {
            try {
                val latest = state.value.tasks.firstOrNull { it.id == task.id } ?: return@launch
                val updated = latest.copy(favorite = !latest.favorite)
                withContext(Dispatchers.IO) { store.save(updated) }
                updateTask(updated)
            } catch (_: Exception) { notify("收藏保存失败") }
        }
    }

    fun delete(task: ImageTask, onDeleted: () -> Unit) {
        if (task.status in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED) || state.value.dataBusy || GenerationService.status.value.running) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.delete(task) }
                mutable.update { it.copy(tasks = it.tasks.filterNot { entry -> entry.id == task.id }) }
                withContext(Dispatchers.IO) { runCatching { CollectionStore(getApplication()).prune(state.value.tasks.map { it.id }.toSet()) }.onFailure { notify("作品已删除，但收藏夹关联清理失败") } }
                refreshCollections()
                onDeleted()
            } catch (_: Exception) { notify("删除失败，请重试") }
        }
    }

    fun reuse(task: ImageTask, outputAsReference: String? = null, onReady: () -> Unit) {
        viewModelScope.launch {
            try {
                val refs = withContext(Dispatchers.IO) {
                    if (outputAsReference != null) listOf(store.importImage(Uri.fromFile(store.file(task, outputAsReference))))
                    else store.copyReferences(task)
                }
                val maskCopy = withContext(Dispatchers.IO) {
                    if (outputAsReference == null && task.mask.isNotBlank()) {
                        store.file(task, task.mask).copyTo(File(getApplication<Application>().cacheDir, "mask-${java.util.UUID.randomUUID()}.png")).path
                    } else null
                }
                val inputs = withContext(Dispatchers.IO) { if (outputAsReference == null) store.copyVideoInputs(task) else VideoInputPaths() }
                val params = if (task.apiMode == "h3-video") task.params.copy(h3Preset = task.model) else task.params
                mutable.update { it.copy(prompt = task.prompt, params = params, references = refs, mask = maskCopy, videoInputs = inputs) }
                if (state.value.settings.selected.model != task.model) notify("已复用提示词和参数；生成将使用当前 API 配置及模型")
                onReady()
            } catch (_: Exception) { notify("参考图不存在，无法复用，请重新选择") }
        }
    }

    private fun friendlyError(e: Exception, apiKey: String): String {
        val message = when (e) {
            is SocketTimeoutException -> "生成等待超时，服务商可能仍在处理。请先检查服务商记录，再决定是否重试。"
            is java.net.UnknownHostException -> "无法解析 API 域名，请检查地址和网络"
            is javax.net.ssl.SSLException -> "HTTPS 连接失败，请检查服务商证书和设备时间"
            else -> e.message ?: "请求失败，请检查网络和服务商配置"
        }
        return if (apiKey.isEmpty()) message else message.replace(apiKey, "[已隐藏]")
    }
}
