package com.lingan.imagestudio

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingan.imagestudio.data.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundGenerationTest {
    @Test fun requestCompletesAfterGoingHome() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val server = MockWebServer().apply { start() }
        val store = LocalStore(app)
        var id: String? = null
        try {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}]}""").setBodyDelay(4, TimeUnit.SECONDS))
            val profile = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "test-key", model = "test-image")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { GenerationService.start(it, profile, "background test", GenerationParams(), emptyList()) }
                assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                scenario.onActivity { it.finish() }
                val deadline = System.currentTimeMillis() + 15000
                while (GenerationService.status.value.running && System.currentTimeMillis() < deadline) Thread.sleep(100)
                val status = GenerationService.status.value
                assertFalse("后台任务应已完成", status.running)
                val task = requireNotNull(status.task)
                id = task.id
                assertEquals(TaskStatus.SUCCEEDED, task.status)
                assertTrue(store.file(task, task.outputs.single()).isFile)
                assertEquals(1, server.requestCount)
            }
        } finally {
            id?.let { taskId -> store.load().first.firstOrNull { it.id == taskId }?.let { store.delete(it) } }
            server.shutdown()
        }
    }
}
