package com.lingan.imagestudio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettings(context: Context) {
    private val preferences = context.getSharedPreferences("secure_settings", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("image_studio_settings_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("image_studio_settings_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): Settings {
        val value = preferences.getString("payload", null) ?: return Settings()
        val parts = value.split(":")
        require(parts.size == 2) { "配置格式损坏，请重新保存 API 设置" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val plaintext = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        return Settings.fromJson(JSONObject(plaintext))
    }

    fun write(settings: Settings) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(settings.toJson().toString().toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        check(preferences.edit().putString("payload", payload).commit()) { "配置保存失败，请检查设备存储空间" }
    }
}

class LocalStore(private val context: Context) {
    private val root = File(context.filesDir, "tasks").apply { mkdirs() }
    private val imports = File(context.cacheDir, "references").apply { mkdirs() }

    // Signed media links stay private to this task and are excluded from history JSON and backups.
    fun saveVideoDownload(task: ImageTask, url: String) {
        val bytes = JSONObject().put("requestId", task.videoRequestId).put("url", url).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64 * 1024) { "视频下载地址过长" }
        val atomic = AtomicFile(file(task, "video-download.json"))
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    fun videoDownloadUrl(task: ImageTask): String = runCatching {
        val cache = file(task, "video-download.json")
        if (!cache.isFile || cache.length() > 64 * 1024 || task.videoRequestId.isBlank()) return@runCatching ""
        val json = JSONObject(AtomicFile(cache).readFully().toString(Charsets.UTF_8))
        if (json.optString("requestId") == task.videoRequestId) json.optString("url") else ""
    }.getOrDefault("")

    fun saveImageResponse(task: ImageTask, response: String) {
        val bytes = response.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024) { "图片下载信息过大" }
        val atomic = AtomicFile(file(task, "image-download.json"))
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    fun imageResponse(task: ImageTask): String? = runCatching {
        val cache = file(task, "image-download.json")
        if (!cache.isFile || cache.length() > 256 * 1024) null else AtomicFile(cache).readFully().toString(Charsets.UTF_8)
    }.getOrNull()

    fun directory(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "无效任务 ID" }
        return File(root, id)
    }

    fun file(task: ImageTask, relative: String): File {
        val dir = directory(task.id).canonicalFile
        return File(dir, relative).canonicalFile.also {
            require(it.path.startsWith(dir.path + File.separator)) { "无效图片路径" }
        }
    }

    fun load(preserveRunning: Boolean = false): Pair<List<ImageTask>, Int> {
        var damaged = 0
        val tasks = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            runCatching {
                val task = ImageTask.fromJson(JSONObject(AtomicFile(File(dir, "task.json")).readFully().toString(Charsets.UTF_8)))
                require(task.id == dir.name)
                task.assetNames.forEach { file(task, it) }
                if (task.status in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED) && !preserveRunning) {
                    task.copy(status = TaskStatus.INTERRUPTED, error = "应用在任务完成前被关闭，请检查服务商记录后再决定是否重试。").also(::save)
                } else task
            }.getOrElse { damaged++; null }
        }.sortedByDescending { it.createdAt }
        return tasks to damaged
    }

    fun save(task: ImageTask) {
        val dir = directory(task.id).apply { mkdirs() }
        val atomic = AtomicFile(File(dir, "task.json"))
        val output = atomic.startWrite()
        try {
            output.write(task.toJson().toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    fun prepare(task: ImageTask, references: List<String>, mask: String? = null, videoInputs: VideoInputPaths = VideoInputPaths()): ImageTask {
        val dir = directory(task.id).apply { mkdirs() }
        try {
            val names = references.mapIndexed { index, path ->
                val source = File(path)
                require(source.isFile) { "参考图已失效，请重新选择" }
                val name = "input-${index + 1}.png"
                source.copyTo(File(dir, name), overwrite = true)
                name
            }
            if (mask != null) {
                require(names.isNotEmpty()) { "遮罩需要参考图" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(File(dir, names.first()).path, bounds)
                val maskBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(mask, maskBounds)
                require(bounds.outWidth == maskBounds.outWidth && bounds.outHeight == maskBounds.outHeight && bounds.outWidth > 0) { "遮罩尺寸与第一张参考图不匹配" }
                File(mask).copyTo(File(dir, "mask.png"), overwrite = true)
            }
            fun copyMedia(path: String, prefix: String, audio: Boolean): String {
                val source = File(path)
                MediaInputStore.duration(source, audio)
                val name = "$prefix.${source.extension.lowercase()}"
                source.copyTo(File(dir, name), overwrite = true)
                return name
            }
            val storedInputs = VideoInputPaths(
                videos = videoInputs.videos.mapIndexed { i, path -> copyMedia(path, "ref-video-${i + 1}", false) },
                audios = videoInputs.audios.mapIndexed { i, path -> copyMedia(path, "ref-audio-${i + 1}", true) },
                sourceVideo = videoInputs.sourceVideo.takeIf(String::isNotBlank)?.let { copyMedia(it, "source-video", false) }.orEmpty(),
                sourceDurationMs = videoInputs.sourceVideo.takeIf(String::isNotBlank)?.let { MediaInputStore.duration(File(it), false) } ?: 0)
            return task.copy(references = names, mask = if (mask != null) "mask.png" else "", videoInputs = storedInputs).also(::save)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }

    fun saveResult(task: ImageTask, result: GenerationResult, elapsed: Long): ImageTask {
        val names = result.images.mapIndexed { index, image ->
            val name = "output-${index + 1}.${image.extension}"
            val output = file(task, name)
            output.writeBytes(image.bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "服务商返回了损坏的图片" }
            name
        }
        return task.copy(status = TaskStatus.SUCCEEDED, outputs = names, durationMs = elapsed,
            revisedPrompt = result.revisedPrompt, actualSize = result.actualSize, actualQuality = result.actualQuality, actualFormat = result.actualFormat, actualBackground = result.actualBackground).also(::save)
    }

    fun delete(task: ImageTask) {
        check(directory(task.id).deleteRecursively()) { "删除失败，请重试" }
    }

    fun copyReferences(task: ImageTask): List<String> = task.references.map { name ->
        file(task, name).copyTo(File(imports, "${UUID.randomUUID()}.png")).path
    }

    fun copyVideoInputs(task: ImageTask): VideoInputPaths {
        fun copy(name: String): String = file(task, name).let { source ->
            source.copyTo(File(imports, "${UUID.randomUUID()}.${source.extension}")).path
        }
        return task.videoInputs.copy(videos = task.videoInputs.videos.map(::copy), audios = task.videoInputs.audios.map(::copy),
            sourceVideo = task.videoInputs.sourceVideo.takeIf(String::isNotBlank)?.let(::copy).orEmpty())
    }

    fun importImage(uri: Uri): String {
        // Bound input bytes and decoded dimensions before allocating a bitmap.
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                require(buffer.size() + n <= 32 * 1024 * 1024) { "参考图超过 32 MB，请先压缩图片" }
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        } ?: throw IOException("无法读取所选图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别该图片，请选择 PNG、JPEG 或 WebP" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("无法解码参考图")
        try {
            // Preserve EXIF rotation for JPEGs before uploading.
            val orientation = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
            val matrix = android.graphics.Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            val corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val output = File(imports, "${UUID.randomUUID()}.png")
            try {
                output.outputStream().use { check(corrected.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                require(output.length() <= 16L * 1024 * 1024) { "图片转换后超过 16 MB，请选择更小的图片" }
                return output.path
            } catch (e: Exception) { output.delete(); throw e }
            finally { if (corrected !== bitmap) corrected.recycle() }
        } finally { bitmap.recycle() }
    }
}
