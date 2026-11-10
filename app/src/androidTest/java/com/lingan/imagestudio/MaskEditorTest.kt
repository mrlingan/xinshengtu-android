package com.lingan.imagestudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingan.imagestudio.data.*
import com.lingan.imagestudio.ui.MaskEditor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MaskEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun paintClearAndApplyThenBackupMask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "mask-test-${UUID.randomUUID()}.png")
        val zip = File(context.cacheDir, "mask-backup-${UUID.randomUUID()}.zip")
        val store = LocalStore(context)
        val before = store.load().first.map { it.id }.toSet()
        var mask: String? = null
        try {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            compose.setContent { MaterialTheme { MaskEditor(input.path, {}, { mask = it }) } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mask-canvas").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mask-canvas").performTouchInput { swipe(center, center + Offset(width * .1f, 0f)) }
            compose.onNodeWithText("撤销").assertIsEnabled()
            compose.onNodeWithText("清空").performClick()
            compose.onNodeWithText("应用选区").assertIsNotEnabled()
            compose.onNodeWithTag("mask-canvas").performTouchInput { swipe(center, center + Offset(width * .1f, 0f)) }
            compose.onNodeWithText("应用选区").performClick()
            compose.waitUntil(10000) { mask != null }
            val decoded = BitmapFactory.decodeFile(mask!!)
            assertEquals(256, decoded.width)
            assertEquals(0, Color.alpha(decoded.getPixel(128, 128)))
            assertEquals(255, Color.alpha(decoded.getPixel(0, 0)))
            decoded.recycle()
            val task = store.prepare(ImageTask(prompt = "edit", profileName = "test", model = "test-image", params = GenerationParams()), listOf(input.path), mask)
            assertEquals("mask.png", task.mask)
            val saved = task.copy(status = TaskStatus.FAILED)
            store.save(saved)
            val backup = BackupStore(context, store)
            backup.export(Uri.fromFile(zip), listOf(saved))
            assertEquals(1, backup.restore(Uri.fromFile(zip)))
            val restored = store.load().first.single { it.id !in before && it.id != task.id }
            assertEquals("mask.png", restored.mask)
            assertArrayEquals(File(mask!!).readBytes(), store.file(restored, restored.mask).readBytes())
        } finally {
            store.load().first.filter { it.id !in before }.forEach(store::delete)
            input.delete(); zip.delete(); mask?.let { File(it).delete() }
        }
    }
}
