package com.lingan.imagestudio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingan.imagestudio.data.*
import com.lingan.imagestudio.ui.AnnotationEditor
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AnnotationEditorTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun temp(extension: String = "png") = File(app.cacheDir, "annotation-test-${UUID.randomUUID()}.$extension")
    private fun mark(tool: AnnotationTool, points: List<MaskPoint>, color: Int = Color.BLUE, width: Float = .05f, text: String = "") = AnnotationMark(tool, points, color, width, text)

    @Test fun rendererErasesAnnotationsWithoutDamagingBaseAndRendersShapesAndText() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val output = temp()
        try {
            val line = mark(AnnotationTool.BRUSH, listOf(MaskPoint(.1f, .5f), MaskPoint(.9f, .5f)), width = .1f)
            val erase = mark(AnnotationTool.ERASER, listOf(MaskPoint(.5f, .5f)), width = .15f)
            AnnotationRenderer.save(256, 256, source, listOf(line, erase), output)
            BitmapFactory.decodeFile(output.path).let { bitmap ->
                assertEquals(Color.GREEN, bitmap.getPixel(128, 128)); assertEquals(Color.BLUE, bitmap.getPixel(60, 128))
                assertEquals(Color.GREEN, source.getPixel(60, 128)); bitmap.recycle()
            }
            AnnotationRenderer.save(256, 256, null, listOf(
                mark(AnnotationTool.RECTANGLE, listOf(MaskPoint(.8f, .8f), MaskPoint(.2f, .2f)), Color.RED),
                mark(AnnotationTool.ELLIPSE, listOf(MaskPoint(.3f, .3f), MaskPoint(.7f, .7f))),
                mark(AnnotationTool.TEXT, listOf(MaskPoint(.05f, .02f)), Color.BLACK, text = "TEST")), output)
            BitmapFactory.decodeFile(output.path).let { bitmap ->
                assertEquals(Color.RED, bitmap.getPixel(51, 128)); assertEquals(Color.BLUE, bitmap.getPixel(128, 77))
                var dark = 0
                for (x in 12..100) for (y in 5..45) if (Color.red(bitmap.getPixel(x, y)) < 100) dark++
                assertTrue(dark > 10); assertEquals(Color.WHITE, bitmap.getPixel(250, 250)); bitmap.recycle()
            }
        } finally { source.recycle(); output.delete() }
    }

    @Test fun blankCanvasSupportsUndoRedoClearTextAndExport() {
        var exported: String? = null
        compose.setContent { MaterialTheme { AnnotationEditor(null, {}, { exported = it }) } }
        try {
            compose.onNodeWithText("用作参考图").assertIsNotEnabled()
            compose.onNodeWithText("矩形").performClick()
            compose.onNodeWithTag("annotation-canvas").performTouchInput { swipe(Offset(width * .2f, height * .2f), Offset(width * .8f, height * .8f)) }
            compose.onNodeWithText("撤销").performClick()
            compose.onNodeWithText("用作参考图").assertIsNotEnabled()
            compose.onNodeWithText("重做").performClick()
            compose.onNodeWithText("用作参考图").assertIsEnabled()
            compose.onNodeWithText("清空").performClick(); compose.onNodeWithText("清空标注").performClick()
            compose.onNodeWithText("用作参考图").assertIsNotEnabled()
            compose.onNodeWithText("文字").performClick()
            compose.onNodeWithText("标注文字").performTextInput("TEST")
            val canvasBounds = compose.onNodeWithTag("annotation-canvas").fetchSemanticsNode().boundsInRoot
            assertTrue("Canvas has no drawing space after text input: $canvasBounds", canvasBounds.height > 1)
            compose.onNodeWithTag("annotation-canvas").performTouchInput { click(center) }
            compose.onNodeWithText("用作参考图").assertIsEnabled().performClick()
            compose.waitUntil(10000) { exported != null }
            BitmapFactory.decodeFile(exported!!).let { bitmap ->
                assertEquals(1024, bitmap.width); assertEquals(1024, bitmap.height)
                var red = 0
                for (x in 510..680) for (y in 510..610) if (Color.green(bitmap.getPixel(x, y)) < 100) red++
                assertTrue(red > 20); bitmap.recycle()
            }
        } finally { exported?.let { File(it).delete() } }
    }

    @Test fun annotatedReferenceReplacesOriginalClearsMaskAndSurvivesGenerationBackup() = runBlocking {
        val original = temp(); val annotated = temp(); val extra = temp(); val backupFile = temp("zip")
        val store = LocalStore(app); val before = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        try {
            AnnotationRenderer.save(256, 256, null, emptyList(), original)
            AnnotationRenderer.save(256, 256, null, listOf(mark(AnnotationTool.BRUSH, listOf(MaskPoint(.5f, .5f)))), annotated)
            original.copyTo(extra)
            lateinit var vm: StudioViewModel
            compose.runOnUiThread { vm = StudioViewModel(app) }
            compose.waitUntil(10000) { !vm.state.value.loading }
            compose.runOnUiThread {
                assertTrue(vm.applyAnnotation(original.path, null)); assertTrue(vm.applyAnnotation(extra.path, null)); vm.setMask("old-mask")
                assertTrue(vm.applyAnnotation(annotated.path, original.path))
            }
            assertEquals(listOf(annotated.path, extra.path), vm.state.value.references); assertNull(vm.state.value.mask)
            val profile = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "test-key", model = "test-image")
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${java.util.Base64.getEncoder().encodeToString(annotated.readBytes())}"}]}"""))
            val task = store.prepare(ImageTask(prompt = "use annotation", profileName = "test", model = profile.model, params = GenerationParams()), vm.state.value.references)
            val result = ImageApi().generate(profile, task.prompt, task.params, task.references.map { store.file(task, it) })
            val request = server.takeRequest(); assertEquals("/v1/images/edits", request.path); assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/"))
            val saved = store.saveResult(task, result, 100)
            BackupStore(app, store).export(Uri.fromFile(backupFile), listOf(saved))
            assertEquals(1, BackupStore(app, store).restore(Uri.fromFile(backupFile)))
            val restored = store.load().first.single { it.id !in before && it.id != task.id }
            assertArrayEquals(annotated.readBytes(), store.file(restored, restored.references.first()).readBytes())
            // Adding a new board at capacity fails without disturbing the sixteen existing references.
            compose.runOnUiThread { repeat(14) { vm.applyAnnotation(extra.path, null) } }
            val overflow = temp().apply { writeBytes(annotated.readBytes()) }
            compose.runOnUiThread { assertFalse(vm.applyAnnotation(overflow.path, null)) }
            assertFalse(overflow.exists()); assertEquals(16, vm.state.value.references.size)
        } finally {
            store.load().first.filter { it.id !in before }.forEach(store::delete)
            original.delete(); annotated.delete(); extra.delete(); backupFile.delete(); server.shutdown()
        }
    }
}
