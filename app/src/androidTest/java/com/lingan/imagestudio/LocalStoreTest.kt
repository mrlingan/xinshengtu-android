package com.lingan.imagestudio

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingan.imagestudio.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalStoreTest {
    @Test fun importsPersistsRecoversAndDeletesTask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LocalStore(context)
        val input = File(context.cacheDir, "storage-test.png")
        val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val imported = store.importImage(Uri.fromFile(input))
        val initial = ImageTask(prompt = "reference test", profileName = "test", model = "custom", params = GenerationParams())
        try {
            val task = store.prepare(initial, listOf(imported))
            assertEquals(1, task.references.size)
            assertTrue(store.file(task, task.references.first()).isFile)
            assertTrue(runCatching { store.file(task, "../outside.png") }.isFailure)
            val recovered = store.load().first.first { it.id == task.id }
            assertEquals(TaskStatus.INTERRUPTED, recovered.status)
            val result = store.saveResult(task, GenerationResult(listOf(GeneratedImage(input.readBytes(), "png")), "revised", "128x64"), 1000)
            assertEquals(TaskStatus.SUCCEEDED, result.status)
            assertEquals(result, store.load().first.first { it.id == result.id })
            val output = store.file(result, result.outputs.single())
            val shareUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", output)
            assertEquals("content", shareUri.scheme)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val galleryUri = saveToGallery(context, output)
                try {
                    val galleryBytes = context.contentResolver.openInputStream(galleryUri)!!.use { it.readBytes() }
                    assertArrayEquals(output.readBytes(), galleryBytes)
                } finally { context.contentResolver.delete(galleryUri, null, null) }
            }
            val reused = store.copyReferences(result)
            assertTrue(File(reused.single()).isFile)
            reused.forEach { File(it).delete() }
        } finally {
            store.delete(initial)
            input.delete()
            File(imported).delete()
        }
        assertFalse(store.directory(initial.id).exists())
    }
}
