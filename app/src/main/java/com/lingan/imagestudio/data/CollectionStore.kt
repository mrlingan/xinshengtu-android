package com.lingan.imagestudio.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ArtworkCollection(val id: String = UUID.randomUUID().toString(), val name: String, val taskIds: Set<String> = emptySet()) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("taskIds", JSONArray(taskIds.sorted()))
    companion object {
        fun fromJson(json: JSONObject) = ArtworkCollection(json.getString("id"), json.getString("name"), json.optJSONArray("taskIds").strings().toSet())
    }
}

class CollectionStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "collections.json"))
    companion object { private val lock = Any() }
    fun read(): List<ArtworkCollection> = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized emptyList()
        val array = JSONObject(file.readFully().toString(Charsets.UTF_8)).getJSONArray("collections")
        List(array.length()) { ArtworkCollection.fromJson(array.getJSONObject(it)) }.also(::validate)
    }
    private fun validate(collections: List<ArtworkCollection>) {
        require(collections.size <= 100 && collections.map { it.id }.distinct().size == collections.size) { "收藏夹数量超出上限或 ID 重复" }
        require(collections.sumOf { it.taskIds.size } <= 50000) { "收藏夹中的作品关联过多" }
        collections.forEach { folder ->
            require(folder.id.matches(Regex("[a-zA-Z0-9-]+")) && folder.name.isNotBlank() && folder.name.length <= 40) { "收藏夹名称需为 1–40 个字符" }
            require(folder.taskIds.all { it.matches(Regex("[a-zA-Z0-9-]+")) }) { "无效作品 ID" }
        }
    }
    fun update(transform: (List<ArtworkCollection>) -> List<ArtworkCollection>): List<ArtworkCollection> = synchronized(lock) {
        val updated = transform(read())
        validate(updated)
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("collections", JSONArray(updated.map { it.toJson() })).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
        updated
    }
    fun create(name: String) = update { existing ->
        val trimmed = name.trim()
        require(existing.none { it.name.equals(trimmed, true) }) { "收藏夹名称已存在" }
        existing + ArtworkCollection(name = trimmed)
    }
    fun rename(id: String, name: String) = update { existing ->
        val trimmed = name.trim()
        require(existing.any { it.id == id }) { "收藏夹不存在" }
        require(existing.none { it.id != id && it.name.equals(trimmed, true) }) { "收藏夹名称已存在" }
        existing.map { if (it.id == id) it.copy(name = trimmed) else it }
    }
    fun delete(id: String) = update { it.filterNot { folder -> folder.id == id } }
    fun membership(folderId: String, taskIds: Set<String>, add: Boolean) = update { existing ->
        require(existing.any { it.id == folderId }) { "收藏夹不存在" }
        existing.map { if (it.id == folderId) it.copy(taskIds = if (add) it.taskIds + taskIds else it.taskIds - taskIds) else it }
    }
    fun assign(taskId: String, folderIds: Set<String>) = update { existing ->
        require(existing.map { it.id }.containsAll(folderIds)) { "收藏夹不存在" }
        existing.map { it.copy(taskIds = if (it.id in folderIds) it.taskIds + taskId else it.taskIds - taskId) }
    }
    fun prune(validIds: Set<String>) = update { existing -> existing.map { it.copy(taskIds = it.taskIds.intersect(validIds)) } }
}
