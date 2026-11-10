package com.lingan.imagestudio.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class XaiVideoApiTest {
    @Test fun `successful video payload retains opaque identifiers and signed download URLs`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("signed-video", ".mp4").apply { delete() }
        try {
            val key = "xai-test-secret"
            val id = "job-$key"
            val link = "/clip.mp4?signature=$key"
            server.enqueue(MockResponse().setBody(JSONObject().put("request_id", id).toString()))
            server.enqueue(MockResponse().setBody("""{"status":"done","video":{"url":"$link"}}"""))
            server.enqueue(MockResponse().setBody("0000ftypmp42content"))
            VideoApi().generate(profile(server), "sea", GenerationParams(), emptyList(), target,
                onSubmitted = { assertEquals(id, it) })
            server.takeRequest()
            assertEquals("/v1/videos/$id", server.takeRequest().path)
            val download = server.takeRequest()
            assertEquals(link, download.path)
            assertNull(download.getHeader("Authorization"))
            assertTrue(target.isFile)
        } finally { target.delete(); server.shutdown() }
    }

    @Test fun `relative content endpoint authenticates only after its own 401 challenge`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("relative-video", ".mp4").apply { delete() }
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("0000ftypmp42content"))
            VideoApi().generate(profile(server), "sea", GenerationParams(), emptyList(), target,
                existingId = "paid-job", onSubmitted = { fail("must not submit") },
                cachedDownloadUrl = "/v1/videos/paid-job/content")
            val anonymous = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val authenticated = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/v1/videos/paid-job/content", anonymous.path)
            assertEquals(anonymous.path, authenticated.path); assertNull(anonymous.getHeader("Authorization"))
            assertEquals("Bearer xai-test-secret", authenticated.getHeader("Authorization"))
            assertEquals(2, server.requestCount); assertTrue(target.isFile)
        } finally { target.delete(); server.shutdown() }
    }

    @Test fun `external content endpoint never receives provider key after 401`() = runBlocking {
        val api = MockWebServer().apply { start() }
        val cdn = MockWebServer().apply { start() }
        val target = File.createTempFile("relative-video", ".mp4").apply { delete() }
        try {
            cdn.enqueue(MockResponse().setResponseCode(401))
            api.enqueue(MockResponse().setBody("""{"status":"expired"}"""))
            val failure = runCatching { VideoApi().generate(profile(api), "sea", GenerationParams(), emptyList(), target,
                existingId = "paid-job", onSubmitted = { fail("must not submit") },
                cachedDownloadUrl = cdn.url("/v1/videos/paid-job/content").toString()) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("过期"))
            assertEquals(1, cdn.requestCount)
            assertNull(requireNotNull(cdn.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"))
            assertEquals("/v1/videos/paid-job", requireNotNull(api.takeRequest(5, TimeUnit.SECONDS)).path)
        } finally { target.delete(); api.shutdown(); cdn.shutdown() }
    }

    @Test fun `generation result resolves relative link under custom API prefix with signed query intact`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("relative-video", ".mp4").apply { delete() }
        try {
            server.enqueue(MockResponse().setBody("""{"request_id":"paid-job"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"done","video":{"url":"videos/paid-job/content?sig=a%2Fb&expires=99"}}"""))
            server.enqueue(MockResponse().setBody("0000ftypmp42content"))
            VideoApi().generate(profile(server).copy(baseUrl = server.url("/gateway/v1").toString()), "sea", GenerationParams(), emptyList(), target,
                onSubmitted = { assertEquals("paid-job", it) })
            assertEquals("/gateway/v1/videos/generations", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            assertEquals("/gateway/v1/videos/paid-job", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            val download = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/gateway/v1/videos/paid-job/content?sig=a%2Fb&expires=99", download.path)
            assertNull(download.getHeader("Authorization")); assertTrue(target.exists())
        } finally { target.delete(); server.shutdown() }
    }
    @Test fun `cached relative video URL resumes download without querying or submitting`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("relative-video", ".mp4").apply { delete() }
        try {
            server.enqueue(MockResponse().setBody("0000ftypmp42content"))
            VideoApi().generate(profile(server), "sea", GenerationParams(), emptyList(), target,
                existingId = "paid-job", onSubmitted = { fail("must not submit") },
                cachedDownloadUrl = "/v1/videos/paid-job/content?token=signed")
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/v1/videos/paid-job/content?token=signed", request.path)
            assertEquals("GET", request.method); assertNull(request.getHeader("Authorization"))
            assertEquals(1, server.requestCount); assertTrue(target.isFile)
        } finally { target.delete(); server.shutdown() }
    }
    @Test fun `HTTP result from custom host explains how to recover without regeneration`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("xai-video", ".mp4").apply { delete() }
        val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        try {
            var cached = ""
            val custom = profile(server).copy(baseUrl = "https://relay.example/v1")
            server.enqueue(MockResponse().setBody("""{"status":"done","video":{"url":"${server.url("/clip.mp4")}"}}"""))
            val failure = runCatching { VideoApi(transport).generate(custom,
                "sea", GenerationParams(), emptyList(), target, existingId = "old-job", onSubmitted = { fail("must not submit") },
                onDownloadReady = { cached = it }) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("允许 HTTP 视频下载"))
            assertEquals(1, server.requestCount); assertFalse(target.exists())
            assertEquals(server.url("/clip.mp4").toString(), cached)
            assertEquals("/v1/videos/old-job", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            server.enqueue(MockResponse().setBody("0000ftypmp42content"))
            VideoApi(transport).generate(custom.copy(allowHttpVideoDownload = true), "sea", GenerationParams(), emptyList(), target,
                existingId = "old-job", onSubmitted = { fail("must not submit") }, cachedDownloadUrl = cached)
            val download = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/clip.mp4", download.path); assertNull(download.getHeader("Authorization"))
            assertEquals(2, server.requestCount); assertEquals("0000ftypmp42content", target.readText())
        } finally { target.delete(); server.shutdown() }
    }

    @Test fun `expired cached download queries original job without resubmitting`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("xai-video", ".mp4").apply { delete() }
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("""{"status":"done","video":{"url":"${server.url("/fresh.mp4")}"}}"""))
            server.enqueue(MockResponse().setBody("0000ftypmp42content"))
            VideoApi().generate(profile(server), "sea", GenerationParams(), emptyList(), target, existingId = "old-job",
                onSubmitted = { fail("must not submit") }, cachedDownloadUrl = server.url("/expired.mp4").toString())
            val expired = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/expired.mp4", expired.path); assertNull(expired.getHeader("Authorization"))
            val query = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/v1/videos/old-job", query.path); assertEquals("GET", query.method)
            assertEquals("/fresh.mp4", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            assertEquals(3, server.requestCount); assertTrue(target.isFile)
        } finally { target.delete(); server.shutdown() }
    }
    private fun profile(server: MockWebServer) = ApiProfile(baseUrl = server.url("/v1").toString(),
        apiKey = "xai-test-secret", apiMode = "xai-video", model = VideoApi.xaiModels.first())

    @Test fun `text video submits settings polls with GET and downloads without key`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("xai-video", ".mp4").apply { delete() }
        try {
            server.enqueue(MockResponse().setBody("""{"request_id":"xai-job"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"done","video":{"url":"${server.url("/clip.mp4")}","respect_moderation":true}}"""))
            server.enqueue(MockResponse().setBody("0000ftypmp42content"))
            val states = mutableListOf<String>()
            var submitted = ""
            val params = GenerationParams(videoDuration = 15, videoResolution = "1080p", videoAspectRatio = "9:16")
            VideoApi(pollMs = 1).generate(profile(server), "sea", params, emptyList(), target,
                onSubmitted = { submitted = it }, onStatus = { states.add(it) })
            assertEquals("xai-job", submitted)
            assertEquals(listOf("Submitting", "Querying", "pending", "done", "Downloading"), states)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", request.method); assertEquals("/v1/videos/generations", request.path)
            assertEquals("Bearer xai-test-secret", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(15, body.getInt("duration")); assertEquals("1080p", body.getString("resolution"))
            assertEquals("9:16", body.getString("aspect_ratio")); assertFalse(body.has("image_size"))
            assertFalse(body.has("n")); assertFalse(body.has("image"))
            repeat(2) {
                val poll = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("GET", poll.method); assertEquals("/v1/videos/xai-job", poll.path)
                assertEquals("Bearer xai-test-secret", poll.getHeader("Authorization"))
            }
            assertNull(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"))
            assertEquals("0000ftypmp42content", target.readText())
        } finally { target.delete(); server.shutdown() }
    }

    @Test fun `image video uses nested data URI and follows source aspect ratio`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val frame = File.createTempFile("xai-frame", ".png").apply { writeText("png-data") }
        val target = File.createTempFile("xai-video", ".mp4")
        try {
            server.enqueue(MockResponse().setBody("""{"request_id":"image-job"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"failed","error":{"message":"xai-test-secret denied"}}"""))
            val failure = runCatching { VideoApi().generate(profile(server), "sea", GenerationParams(), listOf(frame), target, onSubmitted = {}) }.exceptionOrNull()
            assertTrue(failure is IOException); assertFalse(failure?.message.orEmpty().contains("xai-test-secret"))
            val body = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
            assertEquals("data:image/png;base64,cG5nLWRhdGE=", body.getJSONObject("image").getString("url"))
            assertFalse(body.has("aspect_ratio")); assertFalse(body.has("image_size"))
        } finally { frame.delete(); target.delete(); server.shutdown() }
    }

    @Test fun `resume encodes request ID and expired job does not resubmit`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("xai-video", ".mp4")
        try {
            server.enqueue(MockResponse().setBody("""{"status":"expired"}"""))
            val failure = runCatching { VideoApi().generate(profile(server), "sea", GenerationParams(), emptyList(), target,
                existingId = "job/with?query", onSubmitted = { fail("must not submit") }) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("过期"))
            assertEquals(1, server.requestCount)
            assertEquals("/v1/videos/job%2Fwith%3Fquery", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
        } finally { target.delete(); server.shutdown() }
    }

    @Test fun `invalid settings and multiple frames fail before submission`() {
        val profile = ApiProfile(apiKey = "test-key", apiMode = "xai-video", model = VideoApi.xaiModels.first())
        val frame = File.createTempFile("xai-frame", ".png").apply { writeText("png") }
        try {
            listOf(GenerationParams(count = 2), GenerationParams(videoDuration = 0), GenerationParams(videoDuration = 16),
                GenerationParams(videoResolution = "4k"), GenerationParams(videoAspectRatio = "auto")).forEach { params ->
                assertThrows(IllegalArgumentException::class.java) { VideoApi.validate(profile, params, emptyList(), null) }
            }
            assertThrows(IllegalArgumentException::class.java) { VideoApi.validate(profile, GenerationParams(), listOf(frame, frame), null) }
            assertThrows(IllegalArgumentException::class.java) { VideoApi.validate(profile, GenerationParams(), listOf(frame), frame) }
            assertThrows(IllegalArgumentException::class.java) { VideoApi.validate(profile.copy(model = "grok-imagine-video"), GenerationParams(videoResolution = "1080p"), emptyList(), null) }
        } finally { frame.delete() }
    }

    @Test fun `moderated result is rejected without downloading`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("xai-video", ".mp4").apply { delete() }
        try {
            server.enqueue(MockResponse().setBody("""{"status":"done","video":{"respect_moderation":false,"url":"${server.url("/blocked.mp4")}"}}"""))
            val failure = runCatching { VideoApi().generate(profile(server), "sea", GenerationParams(), emptyList(), target,
                existingId = "old-job", onSubmitted = { fail("must not submit") }) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("审核")); assertEquals(1, server.requestCount)
            assertFalse(target.exists())
        } finally { target.delete(); server.shutdown() }
    }
}
