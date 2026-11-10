package com.lingan.imagestudio

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingan.imagestudio.data.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class ResponsesGenerationTest {
    @Test fun responsesQuantityBecomesSeparateSavedTasks() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = LocalStore(app)
        val before = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val b64 = Base64.getEncoder().encodeToString(bytes)
            repeat(2) { server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"image_generation_call","result":"$b64"}]}""")) }
            val profile = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "responses-key", model = "gpt-image-test", apiMode = "responses", responsesModel = "primary-model")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { GenerationService.start(it, profile, "generate", GenerationParams(count = 2), emptyList()) }
                val deadline = System.currentTimeMillis() + 15000
                while (GenerationService.status.value.running && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertFalse(GenerationService.status.value.running)
                val tasks = GenerationService.status.value.tasks
                assertEquals(2, tasks.size)
                assertTrue(tasks.all { it.status == TaskStatus.SUCCEEDED && it.params.count == 1 && it.apiMode == "responses" })
                assertTrue(tasks.all { store.file(it, it.outputs.single()).isFile })
                assertEquals(2, server.requestCount)
                repeat(2) {
                    val request = server.takeRequest()
                    assertEquals("/v1/responses", request.path)
                    assertEquals("primary-model", JSONObject(request.body.readUtf8()).getString("model"))
                }
            }
        } finally {
            GenerationService.cancel()
            store.load().first.filter { it.id !in before }.forEach(store::delete)
            server.shutdown()
        }
    }
}
