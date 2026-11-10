package com.lingan.imagestudio.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

object MediaInputFormats {
    val video = mapOf("mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm")
    val audio = mapOf("mp3" to "audio/mpeg", "wav" to "audio/wav", "m4a" to "audio/mp4",
        "ogg" to "audio/ogg", "flac" to "audio/flac", "aac" to "audio/aac")
    fun mime(file: File): String = (video + audio)[file.extension.lowercase()]
        ?: error("不支持的音视频格式")
}

/** Copies selected media locally without changing its encoding or reading the whole file into memory. */
class MediaInputStore(private val context: Context) {
    data class Imported(val path: String, val durationMs: Long)
    fun import(uri: Uri, audio: Boolean): Imported {
        val resolver = context.contentResolver
        val name = if (uri.scheme == "file") File(requireNotNull(uri.path)).name else resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }.orEmpty()
        val formats = if (audio) MediaInputFormats.audio else MediaInputFormats.video
        val mime = resolver.getType(uri)
        val extension = name.substringAfterLast('.', "").lowercase().takeIf { it in formats }
            ?: formats.entries.firstOrNull { it.value == mime }?.key
            ?: error(if (audio) "请选择 MP3、WAV、M4A、OGG、FLAC 或 AAC 音频" else "请选择 MP4、MOV 或 WebM 视频")
        val directory = File(context.cacheDir, "media-inputs").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            val input = resolver.openInputStream(uri) ?: error("无法读取所选素材")
            input.use { stream -> target.outputStream().use { output ->
                val buffer = ByteArray(65_536); var total = 0L
                while (true) {
                    val n = stream.read(buffer); if (n < 0) break
                    total += n; require(total <= (if (audio) 40L else 100L) * 1024 * 1024) { "素材超过大小上限：视频 100MB，音频 40MB" }
                    output.write(buffer, 0, n)
                }
            } }
            return Imported(target.path, duration(target, audio))
        } catch (e: Exception) { target.delete(); throw e }
    }
    companion object {
        fun duration(file: File, audio: Boolean): Long {
            val formats = if (audio) MediaInputFormats.audio else MediaInputFormats.video
            require(file.extension.lowercase() in formats && file.isFile && file.length() in 1..(if (audio) 40L else 100L) * 1024 * 1024) { "音视频素材格式、大小或路径无效" }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                require(retriever.extractMetadata(if (audio) MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO else MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "所选素材不包含有效${if (audio) "音轨" else "视频轨"}" }
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                require(duration > 0) { "无法读取音视频时长，请更换素材" }
                return duration
            } finally { retriever.release() }
        }
    }
}
