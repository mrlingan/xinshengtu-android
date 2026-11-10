package com.lingan.imagestudio

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingan.imagestudio.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueGenerationTest {
    @Test fun sequentialQueueContinuesAfterFailureAndBulkManagementWorks() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = LocalStore(app)
        val previous = SecureSettings(app).read()
        val existing = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            fun image() = MockResponse().setBody("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}],"quality":"high"}""").setBodyDelay(300, TimeUnit.MILLISECONDS)
            server.enqueue(image())
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"test rejected"}}"""))
            server.enqueue(image())
            val profile = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "queue-test", model = "gpt-image-test")
            SecureSettings(app).write(Settings(listOf(profile), profile.id))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { GenerationService.startBatch(it, profile, listOf("first", "second", "third"), GenerationParams(), emptyList()) }
                val deadline = System.currentTimeMillis() + 15000
                while (GenerationService.status.value.running && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertFalse(GenerationService.status.value.running)
                val tasks = GenerationService.status.value.tasks
                assertEquals(listOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.SUCCEEDED), tasks.map { it.status })
                assertEquals(3, server.requestCount)
                assertEquals(listOf("first", "second", "third"), List(3) { JSONObject(server.takeRequest().body.readUtf8()).getString("prompt") })
                assertEquals("high", tasks.first().actualQuality)
                lateinit var vm: StudioViewModel
                scenario.onActivity { vm = StudioViewModel(app) }
                val ready = System.currentTimeMillis() + 5000
                while (vm.state.value.loading && System.currentTimeMillis() < ready) Thread.sleep(50)
                val ids = tasks.map { it.id }.toSet()
                scenario.onActivity { vm.bulkTasks(ids, "favorite") }
                while (vm.state.value.dataBusy) Thread.sleep(50)
                assertTrue(store.load().first.filter { it.id in ids }.all { it.favorite })
                scenario.onActivity { vm.bulkTasks(ids, "delete") }
                while (vm.state.value.dataBusy) Thread.sleep(50)
                assertTrue(store.load().first.none { it.id in ids })
            }
        } finally {
            GenerationService.cancel()
            store.load().first.filter { it.id !in existing }.forEach(store::delete)
            SecureSettings(app).write(previous)
            server.shutdown()
        }
    }

    @Test fun stoppingQueueDoesNotSubmitPendingRequests() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = LocalStore(app)
        val existing = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val profile = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "test-key", model = "gpt-image-test")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { GenerationService.startBatch(it, profile, listOf("first", "second"), GenerationParams(), emptyList()) }
                assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                scenario.onActivity { GenerationService.cancel() }
                val deadline = System.currentTimeMillis() + 10000
                while (GenerationService.status.value.running && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertFalse(GenerationService.status.value.running)
                assertEquals(1, server.requestCount)
                assertTrue(GenerationService.status.value.tasks.all { it.status == TaskStatus.CANCELLED })
            }
        } finally {
            GenerationService.cancel()
            store.load().first.filter { it.id !in existing }.forEach(store::delete)
            server.shutdown()
        }
    }
}
