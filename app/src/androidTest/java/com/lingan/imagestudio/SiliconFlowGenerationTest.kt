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

@RunWith(AndroidJUnit4::class)
class SiliconFlowGenerationTest {
    @Test fun nativeImagesAreDownloadedAndMultipleImagesSplitIntoTasks() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = LocalStore(app)
        val before = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"images":[{"url":"${server.url("/result.png")}"}]}"""))
                server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            }
            val profile = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "test-key", model = "Qwen/Qwen-Image", apiMode = "siliconflow")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { GenerationService.start(it, profile, "test", GenerationParams(count = 2), emptyList()) }
                val deadline = System.currentTimeMillis() + 15000
                while (GenerationService.status.value.running && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertFalse(GenerationService.status.value.running)
                val tasks = GenerationService.status.value.tasks
                assertEquals(2, tasks.size)
                assertTrue(tasks.all { it.status == TaskStatus.SUCCEEDED && store.file(it, it.outputs.single()).isFile })
                repeat(2) {
                    val generate = server.takeRequest()
                    assertEquals("/v1/images/generations", generate.path)
                    assertEquals("1328x1328", JSONObject(generate.body.readUtf8()).getString("image_size"))
                    assertNull(server.takeRequest().getHeader("Authorization"))
                }
            }
        } finally {
            GenerationService.cancel()
            store.load().first.filter { it.id !in before }.forEach(store::delete)
            server.shutdown()
        }
    }
}
