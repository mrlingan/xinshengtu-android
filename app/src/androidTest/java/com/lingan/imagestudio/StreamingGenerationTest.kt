package com.lingan.imagestudio

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingan.imagestudio.data.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StreamingGenerationTest {
    @Test fun previewIsVisibleBeforeFinalArtworkAndCleanedAfterCompletion() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = LocalStore(app)
        val existing = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val b64 = Base64.getEncoder().encodeToString(bytes)
            val partial = "event: image_generation.partial_image\ndata: {\"b64_json\":\"$b64\"}\n\n"
            val final = "event: image_generation.completed\ndata: {\"b64_json\":\"$b64\",\"quality\":\"high\"}\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(partial + final)
                .throttleBody(partial.toByteArray().size.toLong(), 2, TimeUnit.SECONDS))
            val profile = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "test-key", model = "gpt-image-test", streamImages = true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { GenerationService.start(it, profile, "stream", GenerationParams(), emptyList()) }
                val previewDeadline = System.currentTimeMillis() + 10000
                while (GenerationService.status.value.previewPath == null && GenerationService.status.value.running && System.currentTimeMillis() < previewDeadline) Thread.sleep(20)
                val preview = requireNotNull(GenerationService.status.value.previewPath)
                assertTrue(File(preview).isFile)
                assertEquals(TaskStatus.RUNNING, GenerationService.status.value.task?.status)
                assertTrue(GenerationService.status.value.task!!.outputs.isEmpty())
                val deadline = System.currentTimeMillis() + 15000
                while (GenerationService.status.value.running && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertFalse(GenerationService.status.value.running)
                val task = requireNotNull(GenerationService.status.value.task)
                assertEquals(TaskStatus.SUCCEEDED, task.status)
                assertEquals("high", task.actualQuality)
                assertEquals(1, task.outputs.size)
                assertFalse(File(preview).exists())
                assertNull(GenerationService.status.value.previewPath)
                assertEquals(1, server.requestCount)
            }
        } finally {
            GenerationService.cancel()
            store.load().first.filter { it.id !in existing }.forEach(store::delete)
            server.shutdown()
        }
    }
}
