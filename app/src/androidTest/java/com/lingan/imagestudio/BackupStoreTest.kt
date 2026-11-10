package com.lingan.imagestudio

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingan.imagestudio.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupStoreTest {
    @Test fun roundTripPreservesArtworkAndRejectsTraversal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LocalStore(context)
        val backup = BackupStore(context, store)
        val existing = store.load().first.map { it.id }.toSet()
        val zip = File(context.cacheDir, "backup-test-${UUID.randomUUID()}.zip")
        val bad = File(context.cacheDir, "invalid-${UUID.randomUUID()}.zip")
        try {
            val initial = ImageTask(prompt = "backup prompt", profileName = "test", model = "test-image", params = GenerationParams(size = "2048x2048"), favorite = true)
            store.prepare(initial, emptyList())
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val task = store.saveResult(initial, GenerationResult(listOf(GeneratedImage(bytes, "png")), "", "16x16"), 100)
            backup.export(Uri.fromFile(zip), listOf(task))
            assertEquals(1, backup.restore(Uri.fromFile(zip)))
            val restored = store.load().first.single { it.id !in existing && it.id != task.id }
            assertTrue(store.directory(task.id).isDirectory)
            assertEquals(task.prompt, restored.prompt)
            assertEquals(task.params, restored.params)
            assertTrue(restored.favorite)
            assertArrayEquals(bytes, store.file(restored, restored.outputs.single()).readBytes())
            val before = store.load().first.map { it.id }.toSet()
            ZipOutputStream(bad.outputStream()).use {
                it.putNextEntry(ZipEntry("../outside.json")); it.write("bad".toByteArray()); it.closeEntry()
            }
            assertTrue(runCatching { backup.restore(Uri.fromFile(bad)) }.isFailure)
            assertEquals(before, store.load().first.map { it.id }.toSet())
        } finally {
            store.load().first.filter { it.id !in existing }.forEach(store::delete)
            zip.delete(); bad.delete()
        }
    }
}
