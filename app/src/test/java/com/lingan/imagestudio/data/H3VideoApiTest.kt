package com.lingan.imagestudio.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class H3VideoApiTest {
    private fun profile(server: MockWebServer) = ApiProfile(baseUrl = server.url("/api/v1").toString(),
        apiKey = "RB-test-secret", apiMode = "h3-video", model = "reference")
    private fun job(state: String, video: Boolean = false) = MockResponse().setBody(
        """{"job":{"id":42,"status":"$state","has_video":$video,"error":"RB-test-secret failed"}}""")
    private fun target() = File.createTempFile("h3-video", ".mp4").apply { delete() }

    @Test fun `native text video preserves numeric id polls then authenticates attachment download`() = runBlocking {
        val server = MockWebServer().apply { start() }; val output = target()
        try {
            server.enqueue(job("pending")); server.enqueue(job("pending")); server.enqueue(job("running"))
            server.enqueue(job("done", true)); server.enqueue(MockResponse().setBody("0000ftypmp42video"))
            val states = mutableListOf<String>(); var id = ""; var cached = ""
            VideoApi(pollMs = 1).generate(profile(server), "海上日出", GenerationParams(size = "768x1344", videoDuration = 6),
                emptyList(), output, onSubmitted = { id = it }, onStatus = { states.add(it) }, onDownloadReady = { cached = it })
            assertEquals("42", id)
            assertEquals(listOf("Submitting", "Querying", "InQueue", "InProgress", "done", "Downloading"), states)
            val submit = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/api/v1/jobs", submit.path); assertEquals("POST", submit.method)
            val body = JSONObject(submit.body.readUtf8())
            assertEquals("reference", body.getString("preset")); assertFalse(body.has("model"))
            assertEquals(768, body.getInt("width")); assertEquals(1344, body.getInt("height")); assertEquals(6, body.getInt("seconds"))
            assertEquals(20, body.getInt("sampler_steps")); assertTrue(body.getBoolean("interpolate"))
            repeat(3) {
                val poll = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("GET", poll.method); assertEquals("/api/v1/jobs/42", poll.path)
                assertEquals("Bearer RB-test-secret", poll.getHeader("Authorization"))
            }
            val download = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/api/v1/jobs/42/video?download=1", download.path)
            assertEquals("Bearer RB-test-secret", download.getHeader("Authorization"))
            assertEquals(server.url("/api/v1/jobs/42/video?download=1").toString(), cached)
            assertEquals("0000ftypmp42video", output.readText())
        } finally { output.delete(); server.shutdown() }
    }

    @Test fun `tail frame uploads both images in order and submits returned refs under gateway prefix`() = runBlocking {
        val server = MockWebServer().apply { start() }; val output = target()
        val first = File.createTempFile("first-frame", ".png").apply { writeText("FIRST-FRAME") }
        val last = File.createTempFile("last-frame", ".png").apply { writeText("LAST-FRAME") }
        try {
            server.enqueue(MockResponse().setBody("""{"items":[{"ref":"first.png"},{"ref":"last.png"}]}"""))
            server.enqueue(job("pending")); server.enqueue(job("failed"))
            val failure = runCatching {
                VideoApi().generate(profile(server).copy(baseUrl = server.url("/gateway/api/v1").toString(), model = "tail_frame"),
                    "转场", GenerationParams(), listOf(first, last), output, onSubmitted = {})
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("失败")); assertFalse(failure?.message.orEmpty().contains("RB-test-secret"))
            val upload = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/gateway/api/v1/uploads", upload.path)
            assertEquals("Bearer RB-test-secret", upload.getHeader("Authorization"))
            val multipart = upload.body.readUtf8()
            assertEquals(2, Regex("name=\"files\"").findAll(multipart).count())
            assertTrue(multipart.indexOf("FIRST-FRAME") < multipart.indexOf("LAST-FRAME"))
            val submit = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/gateway/api/v1/jobs", submit.path)
            val body = JSONObject(submit.body.readUtf8())
            assertEquals("tail_frame", body.getString("preset")); assertEquals(listOf("first.png", "last.png"), body.getJSONArray("images").strings())
            assertEquals("/gateway/api/v1/jobs/42", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            assertEquals(3, server.requestCount)
        } finally { first.delete(); last.delete(); output.delete(); server.shutdown() }
    }

    @Test fun `recipe submits locked duration and portrait dimensions despite previous interface settings`() = runBlocking {
        val server = MockWebServer().apply { start() }; val output = target()
        try {
            server.enqueue(job("pending")); server.enqueue(job("cancelled"))
            val failure = runCatching {
                VideoApi().generate(profile(server).copy(model = "dmad_30_portrait"), "海景",
                    GenerationParams(size = "1536x1024", videoDuration = 8), emptyList(), output, onSubmitted = {})
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("已取消"))
            val body = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
            assertEquals(30, body.getInt("seconds")); assertEquals(672, body.getInt("width")); assertEquals(1184, body.getInt("height"))
            assertFalse(body.has("sampler_steps")); assertFalse(body.has("interpolate"))
            assertEquals(2, server.requestCount)
        } finally { output.delete(); server.shutdown() }
    }

    @Test fun `resume skips upload validation and submission with cached authenticated attachment`() = runBlocking {
        val server = MockWebServer().apply { start() }; val output = target()
        try {
            server.enqueue(MockResponse().setBody("0000ftypmp42video"))
            VideoApi().generate(profile(server).copy(model = "tail_frame"), "", GenerationParams(), emptyList(), output,
                existingId = "42", onSubmitted = { fail("must not resubmit") }, cachedDownloadUrl = "/api/v1/jobs/42/video?download=1")
            assertEquals(1, server.requestCount)
            val download = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/api/v1/jobs/42/video?download=1", download.path)
            assertEquals("Bearer RB-test-secret", download.getHeader("Authorization")); assertTrue(output.exists())
        } finally { output.delete(); server.shutdown() }
    }

    @Test fun `missing cached video resumes original task with no surviving reference files`() = runBlocking {
        val server = MockWebServer().apply { start() }; val output = target()
        try {
            server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(job("done", true))
            server.enqueue(MockResponse().setBody("0000ftypmp42video"))
            VideoApi().generate(profile(server).copy(model = "tail_frame"), "旧任务", GenerationParams(), emptyList(), output,
                existingId = "42", onSubmitted = { fail("must not resubmit") }, cachedDownloadUrl = "/api/v1/jobs/42/video?download=1")
            assertEquals(3, server.requestCount)
            assertEquals("/api/v1/jobs/42/video?download=1", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            assertEquals("/api/v1/jobs/42", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            assertEquals("/api/v1/jobs/42/video?download=1", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
        } finally { output.delete(); server.shutdown() }
    }

    @Test fun `H3 attachment redirects to CDN without forwarding card`() = runBlocking {
        val api = MockWebServer().apply { start() }; val cdn = MockWebServer().apply { start() }; val output = target()
        try {
            api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/signed.mp4")))
            cdn.enqueue(MockResponse().setBody("0000ftypmp42video"))
            VideoApi().generate(profile(api), "sea", GenerationParams(), emptyList(), output, existingId = "42",
                onSubmitted = { fail("must not submit") }, cachedDownloadUrl = "/api/v1/jobs/42/video?download=1")
            assertEquals("Bearer RB-test-secret", api.takeRequest().getHeader("Authorization"))
            assertNull(cdn.takeRequest().getHeader("Authorization")); assertTrue(output.exists())
        } finally { output.delete(); api.shutdown(); cdn.shutdown() }
    }

    @Test fun `cached other task and external origin never receive card key and redirects stay disabled`() = runBlocking {
        val server = MockWebServer().apply { start() }; val cdn = MockWebServer().apply { start() }; val output = target()
        try {
            for (url in listOf(server.url("/api/v1/jobs/99/video?download=1"), cdn.url("/api/v1/jobs/42/video?download=1"))) {
                val downloadServer = if (url.port == server.port) server else cdn
                downloadServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/redirect")))
                val failure = runCatching {
                    VideoApi().generate(profile(server), "海景", GenerationParams(), emptyList(), output, existingId = "42",
                        onSubmitted = { fail("must not resubmit") }, cachedDownloadUrl = url.toString())
                }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("302"))
                assertNull(requireNotNull(downloadServer.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"))
            }
            assertEquals(1, server.requestCount); assertEquals(1, cdn.requestCount); assertFalse(output.exists())
        } finally { output.delete(); server.shutdown(); cdn.shutdown() }
    }

    @Test fun `completed job without video does not download or resubmit`() = runBlocking {
        val server = MockWebServer().apply { start() }; val output = target()
        try {
            server.enqueue(job("done"))
            val failure = runCatching {
                VideoApi().generate(profile(server), "海景", GenerationParams(), emptyList(), output,
                    existingId = "42", onSubmitted = { fail("must not resubmit") })
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("成片尚不可用")); assertEquals(1, server.requestCount); assertFalse(output.exists())
        } finally { output.delete(); server.shutdown() }
    }

    @Test fun `stalled upload is bounded without submitting a billable task`() = runBlocking {
        val server = MockWebServer().apply { start() }; val output = target()
        val ref = File.createTempFile("h3-frame", ".png").apply { writeText("PNG") }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failure = runCatching {
                VideoApi(waitTimeoutMs = 500).generate(profile(server), "海景", GenerationParams(), listOf(ref), output,
                    onSubmitted = { fail("must not submit") })
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("超时")); assertEquals(1, server.requestCount)
            assertEquals("/api/v1/uploads", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            assertFalse(output.exists()); assertFalse(File(output.path + ".part").exists())
        } finally { ref.delete(); output.delete(); server.shutdown() }
    }

    @Test fun `connection and model lookup use non billable presets endpoint and supported available modes`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val response = """{"presets":[{"name":"reference","available":true},{"name":"tail_frame","available":false},{"name":"flashvsr_upscale"},{"name":"easy_15_portrait"}]}"""
            server.enqueue(MockResponse().setBody(response)); server.enqueue(MockResponse().setBody(response))
            val root = profile(server).copy(baseUrl = server.url("/").toString())
            assertTrue(ImageApi().testConnection(root).contains("连接成功"))
            assertEquals(listOf("reference", "flashvsr_upscale", "easy_15_portrait"), ImageApi().listModels(root))
            repeat(2) {
                val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("GET", request.method); assertEquals("/api/v1/presets", request.path)
                assertEquals("Bearer RB-test-secret", request.getHeader("Authorization"))
            }
        } finally { server.shutdown() }
    }

    @Test fun `preset validation rejects incompatible duration size mask and reference count before network`() {
        val profile = ApiProfile(baseUrl = "https://h3.coolhs.com/api/v1", apiKey = "RB-test-secret", apiMode = "h3-video", model = "reference")
        val ref = File.createTempFile("h3-frame", ".png").apply { writeText("PNG") }
        try {
            VideoApi.validate(profile, GenerationParams(), List(8) { ref }, null)
            VideoApi.validate(profile.copy(model = "easy_15"), GenerationParams(), List(9) { ref }, null)
            VideoApi.validate(profile.copy(model = "tail_frame"), GenerationParams(), List(2) { ref }, null)
            fun rejects(p: ApiProfile = profile, params: GenerationParams = GenerationParams(), refs: List<File> = emptyList(), mask: File? = null) {
                assertThrows(IllegalArgumentException::class.java) { VideoApi.validate(p, params, refs, mask) }
            }
            rejects(params = GenerationParams(videoDuration = 15))
            rejects(params = GenerationParams(size = "1280x720"))
            rejects(refs = List(9) { ref })
            rejects(p = profile.copy(model = "tail_frame"), refs = listOf(ref))
            rejects(p = profile.copy(model = "flashvsr_upscale"))
            rejects(params = GenerationParams(count = 2))
            rejects(refs = listOf(ref), mask = ref)
        } finally { ref.delete() }
    }
}
