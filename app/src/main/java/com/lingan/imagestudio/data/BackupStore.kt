package com.lingan.imagestudio.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable artwork backup. API credentials are deliberately excluded. */
class BackupStore(private val context: Context, private val store: LocalStore) {
    fun export(uri: Uri, tasks: List<ImageTask>, includeEmptyCollections: Boolean = true) {
        require(tasks.none { it.status in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED) }) { "请等待生成完成后再备份" }
        require(tasks.size <= 2000) { "单次最多备份 2000 个任务" }
        val ids = tasks.map { it.id }.toSet()
        val folders = CollectionStore(context).read().map { it.copy(taskIds = it.taskIds.intersect(ids)) }.filter { includeEmptyCollections || it.taskIds.isNotEmpty() }
        val manifest = JSONObject().put("version", 3).put("collections", JSONArray(folders.map { it.toJson() })).put("tasks", JSONArray(tasks.map { it.toJson() })).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= 4 * 1024 * 1024) { "任务清单过大" }
        var total = manifest.size.toLong()
        tasks.forEach { task -> task.assetNames.forEach { name ->
            val file = store.file(task, name)
            require(file.isFile && file.length() <= task.assetLimit(name)) { "图片缺失或超过备份上限" }
            total += file.length()
        } }
        require(total <= 512L * 1024 * 1024) { "备份总量超过 512MB" }
        val output = context.contentResolver.openOutputStream(uri) ?: error("无法创建备份文件")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(manifest)
            zip.closeEntry()
            tasks.forEach { task ->
                task.assetNames.forEach { name ->
                    require(name.matches(Regex("[a-zA-Z0-9._-]+"))) { "图片路径格式不支持备份" }
                    val file = store.file(task, name)
                    require(file.isFile) { "任务图片缺失，无法完整备份" }
                    zip.putNextEntry(ZipEntry("${task.id}/$name"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun restore(uri: Uri): Int {
        val stage = File(context.filesDir, ".restore-${UUID.randomUUID()}").apply { mkdirs() }
        val committed = mutableListOf<File>()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份")
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name.matches(Regex("backup\\.json|[a-zA-Z0-9-]+/[a-zA-Z0-9._-]+"))) { "备份包含无效文件路径" }
                    require(names.add(entry.name) && names.size <= 20000) { "备份包含重复文件或文件过多" }
                    val target = File(stage, entry.name).canonicalFile
                    require(target.path.startsWith(stage.canonicalPath + File.separator)) { "无效备份路径" }
                    target.parentFile!!.mkdirs()
                    var size = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            size += n; total += n
                            require(size <= (if (entry.name == "backup.json") 4 else if (entry.name.substringAfterLast('.') in MediaInputFormats.video.keys + MediaInputFormats.audio.keys) 256 else 24) * 1024L * 1024 && total <= 512L * 1024 * 1024) { "备份过大：单图上限 24MB，恢复总量上限 512MB" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            val manifest = JSONObject(File(stage, "backup.json").readText())
            require(manifest.getInt("version") in 1..3) { "不支持的备份版本" }
            val list = manifest.getJSONArray("tasks")
            require(list.length() <= 2000) { "单次最多恢复 2000 个任务" }
            val tasks = List(list.length()) { ImageTask.fromJson(list.getJSONObject(it)) }
            require(tasks.map { it.id }.distinct().size == tasks.size) { "备份任务 ID 重复" }
            val expected = mutableSetOf("backup.json")
            tasks.forEach { task ->
                require(task.id.matches(Regex("[a-zA-Z0-9-]+"))) { "无效任务 ID" }
                if (task.apiMode == "h3-video") H3VideoSpec.validateParams(task.model, task.params) else task.params.validate()
                require(task.videoInputs.videos.size <= 3 && task.videoInputs.audios.size <= 3) { "备份中的音视频素材数量无效" }
                require(task.references.size <= 16 && task.outputs.size <= 4) { "备份中的图片数量无效" }
                task.assetNames.forEach { name ->
                    require(name.matches(Regex("[a-zA-Z0-9._-]+")) && name !in listOf(".", "..", "task.json")) { "无效图片名称" }
                    val path = "${task.id}/$name"
                    expected.add(path)
                    val file = File(stage, path)
                    require(file.isFile && file.length() <= task.assetLimit(name)) { "备份素材缺失或超过大小上限" }
                    if (task.isVideo && name in task.outputs) {
                        require(file.extension == "mp4") { "视频备份必须为 MP4" }
                        val retriever = android.media.MediaMetadataRetriever()
                        try { retriever.setDataSource(file.path); require((retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0) { "备份包含无效视频" } } finally { retriever.release() }
                    } else if (name in task.videoInputs.names) {
                        MediaInputStore.duration(file, name in task.videoInputs.audios)
                    } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "备份包含损坏图片" }
                    }
                }
            }
            require(names == expected) { "备份内容与清单不一致" }
            val foldersJson = manifest.optJSONArray("collections") ?: JSONArray()
            require(foldersJson.length() <= 100) { "备份收藏夹过多" }
            val folders = List(foldersJson.length()) { ArtworkCollection.fromJson(foldersJson.getJSONObject(it)) }
            require(folders.map { it.id }.distinct().size == folders.size) { "收藏夹 ID 重复" }
            val originalIds = tasks.map { it.id }.toSet()
            require(folders.all { originalIds.containsAll(it.taskIds) }) { "收藏夹关联了备份外的作品" }
            val restoredIds = tasks.associate { it.id to UUID.randomUUID().toString() }
            tasks.forEach { original ->
                val task = original.copy(id = restoredIds.getValue(original.id), status = if (original.status in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED)) TaskStatus.INTERRUPTED else original.status)
                val directory = File(stage, original.id).apply { mkdirs() }
                File(directory, "task.json").writeText(task.toJson().toString())
                val destination = store.directory(task.id)
                check(directory.renameTo(destination)) { "恢复写入失败" }
                committed.add(destination)
            }
            if (folders.isNotEmpty()) CollectionStore(context).update { existing ->
                val usedNames = existing.map { it.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
                existing + folders.map { folder ->
                    var name = folder.name.trim()
                    require(name.isNotBlank() && name.length <= 40) { "备份收藏夹名称无效" }
                    var suffix = 1
                    while (name.lowercase(java.util.Locale.ROOT) in usedNames) { name = folder.name.take(28) + "（恢复${suffix++}）" }
                    usedNames.add(name.lowercase(java.util.Locale.ROOT))
                    folder.copy(id = UUID.randomUUID().toString(), name = name, taskIds = folder.taskIds.map { restoredIds.getValue(it) }.toSet())
                }
            }
            return tasks.size
        } catch (e: Exception) { committed.forEach { it.deleteRecursively() }; throw e }
        finally { stage.deleteRecursively() }
    }
}
