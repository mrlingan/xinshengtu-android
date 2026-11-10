package com.lingan.imagestudio.data

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VideoApiTest {
    private fun profile(server: MockWebServer) = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "test-key", apiMode = "video", model = VideoApi.models.first())
    @Test fun `deadline cancels stalled submission status and download without resubmitting`() = runBlocking {
        for (stalledStage in listOf("submit", "status", "download")) {
            val server = MockWebServer().apply { start() }
            val target = File.createTempFile("video", ".mp4").apply { delete() }
            try {
                if (stalledStage != "submit") server.enqueue(MockResponse().setBody("""{"requestId":"job-1"}"""))
                if (stalledStage == "download") server.enqueue(MockResponse().setBody("""{"status":"Succeed","results":{"videos":[{"url":"${server.url("/clip.mp4")}"}]}}"""))
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                var submitted = ""
                val outcome = withTimeoutOrNull(3000) {
                    runCatching {
                        VideoApi(waitTimeoutMs = 500).generate(profile(server), "sea", GenerationParams(), emptyList(), target,
                            onSubmitted = { submitted = it })
                    }
                }
                assertNotNull("Still waiting during $stalledStage", outcome)
                assertTrue("Expected resumable timeout during $stalledStage", requireNotNull(outcome).exceptionOrNull() is java.io.IOException)
                assertEquals(if (stalledStage == "submit") "" else "job-1", submitted)
                assertFalse(target.exists())
                assertFalse(File(target.path + ".part").exists())
                val paths = List(server.requestCount) { server.takeRequest().path }
                assertEquals(1, paths.count { it == "/v1/video/submit" })
                assertEquals(when (stalledStage) { "submit" -> 1; "status" -> 2; else -> 3 }, paths.size)
            } finally { target.delete(); server.shutdown() }
        }
    }
    @Test fun `waiting is bounded by elapsed time rather than number of status requests`() = runBlocking {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"status":"InProgress"}""")
            }
            start()
        }
        val target = File.createTempFile("video", ".mp4").apply { delete() }
        try {
            val outcome = withTimeoutOrNull(1500) {
                runCatching {
                    VideoApi(pollMs = 1, waitTimeoutMs = 100).generate(profile(server), "sea", GenerationParams(), emptyList(), target,
                        existingId = "old-job", onSubmitted = { fail("must not resubmit") }, onStatus = { if (it == "InProgress") delay(250) })
                }
            }
            assertNotNull("Video generation kept waiting beyond its total deadline", outcome)
            val failure = requireNotNull(outcome).exceptionOrNull()
            assertTrue("Deadline must end waiting with a resumable error: $failure", failure is java.io.IOException)
            assertTrue(failure?.message.orEmpty().contains("继续查询原任务"))
            assertFalse(target.exists())
            assertTrue(server.requestCount > 0)
            repeat(server.requestCount) { assertEquals("/v1/video/status", server.takeRequest().path) }
        } finally { target.delete(); server.shutdown() }
    }
    @Test fun `submit poll download uses video protocol and no download credential`() = runBlocking {
        val server = MockWebServer(); server.start()
        val target = File.createTempFile("video", ".mp4").apply { delete() }
        try {
            server.enqueue(MockResponse().setBody("""{"requestId":"job-1"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"InQueue"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"InProgress"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"Succeed","results":{"videos":[{"url":"${server.url("/video.mp4")}"}]}}"""))
            server.enqueue(MockResponse().setBody("0000ftypmp42content"))
            var id = ""; val states = mutableListOf<String>()
            VideoApi(pollMs = 1).generate(profile(server), "sea", GenerationParams(), emptyList(), target, onSubmitted = { id = it }, onStatus = { states.add(it) })
            assertEquals("job-1", id)
            assertEquals(listOf("Submitting", "Querying", "InQueue", "InProgress", "Succeed", "Downloading"), states)
            assertTrue(target.isFile)
            val submit = server.takeRequest(); assertEquals("/v1/video/submit", submit.path)
            val body = JSONObject(submit.body.readUtf8()); assertEquals("1280x720", body.getString("image_size")); assertFalse(body.has("n")); assertFalse(body.has("size"))
            repeat(3) { val status = server.takeRequest(); assertEquals("/v1/video/status", status.path); assertEquals("job-1", JSONObject(status.body.readUtf8()).getString("requestId")) }
            assertNull(server.takeRequest().getHeader("Authorization"))
        } finally { target.delete(); server.shutdown() }
    }
    @Test fun `resume only queries existing job and failed result is redacted`() = runBlocking {
        val server = MockWebServer(); server.start(); val target = File.createTempFile("video", ".mp4")
        try {
            server.enqueue(MockResponse().setBody("""{"status":"Failed","reason":"test-key denied"}"""))
            try {
                VideoApi().generate(profile(server), "sea", GenerationParams(), emptyList(), target, existingId = "old-job", onSubmitted = { fail("must not submit") })
                fail("must fail")
            } catch (e: java.io.IOException) { assertFalse(e.message!!.contains("test-key")) }
            assertEquals(1, server.requestCount); assertEquals("/v1/video/status", server.takeRequest().path)
        } finally { target.delete(); server.shutdown() }
    }
    @Test fun `I2V validates exactly one frame and includes image data`() = runBlocking {
        val server = MockWebServer(); server.start(); val ref = File.createTempFile("frame", ".png").apply { writeText("png-data") }; val target = File.createTempFile("video", ".mp4")
        try {
            val p = profile(server).copy(model = VideoApi.models.last())
            assertThrows(IllegalArgumentException::class.java) { VideoApi.validate(p, GenerationParams(), emptyList(), null) }
            assertThrows(IllegalArgumentException::class.java) { VideoApi.validate(p, GenerationParams(count = 2), listOf(ref), null) }
            server.enqueue(MockResponse().setBody("""{"requestId":"image-job"}""")); server.enqueue(MockResponse().setBody("""{"status":"Failed","reason":"test failure"}"""))
            runCatching { VideoApi().generate(p, "sea", GenerationParams(size = "720x1280"), listOf(ref), target, onSubmitted = {}) }
            val body = JSONObject(server.takeRequest().body.readUtf8()); assertTrue(body.getString("image").startsWith("data:image/png;base64,")); assertEquals("720x1280", body.getString("image_size"))
        } finally { ref.delete(); target.delete(); server.shutdown() }
    }
    @Test fun `cancel waiting does not submit again`() = runBlocking {
        val server = MockWebServer(); server.start(); val target = File.createTempFile("video", ".mp4")
        try {
            server.enqueue(MockResponse().setBody("""{"status":"InProgress"}"""))
            val job = launch { VideoApi(pollMs = 10000).generate(profile(server), "sea", GenerationParams(), emptyList(), target, existingId = "old-job", onSubmitted = {}) }
            withContext(Dispatchers.IO) { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) }
            job.cancelAndJoin(); assertEquals(1, server.requestCount)
        } finally { target.delete(); server.shutdown() }
    }
}
