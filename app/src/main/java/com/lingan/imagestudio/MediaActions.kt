package com.lingan.imagestudio

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File

fun File.imageMime(): String = when (extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "mp4" -> "video/mp4"
    "webp" -> "image/webp"
    else -> "image/png"
}

@RequiresApi(Build.VERSION_CODES.Q)
fun saveToGallery(context: Context, file: File): android.net.Uri {
    val video = file.extension.equals("mp4", true)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "新生图_${System.currentTimeMillis()}.${file.extension}")
        put(MediaStore.Images.Media.MIME_TYPE, file.imageMime())
        put(MediaStore.Images.Media.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/新生图")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建相册文件")
    try {
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("无法写入相册")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
}

fun shareImage(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = file.imageMime()
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("生成图片", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享作品"))
}
