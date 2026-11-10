package com.lingan.imagestudio.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class H3GenerationFeaturesTest {
    private fun profile(server: MockWebServer) = ApiProfile(baseUrl = server.url("/api/v1").toString(),
        apiKey = "RB-feature-secret", apiMode = "h3-video", model = "reference")
    private fun source(extension: String, text: String) = File.createTempFile("h3-feature", ".$extension").apply { writeText(text) }
    private fun job(status: String) = MockResponse().setBody("""{"job":{"id":72,"status":"$status","error":"test failure"}}""")

    @Test fun `mixed references upload correct file types and preserve each reference order with custom settings`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val image = source("png", "IMAGE"); val video = source("mov", "VIDEO"); val audio = source("wav", "AUDIO"); val output = source("mp4", "")
        try {
            server.enqueue(MockResponse().setBody("""{"items":[{"ref":"image.png"}]}"""))
            server.enqueue(MockResponse().setBody("""{"item":{"ref":"video.mov","seconds":5}}"""))
            repeat(2) { server.enqueue(MockResponse().setBody("""{"item":{"ref":"audio-$it.wav"}}""")) }
            server.enqueue(job("pending")); server.enqueue(job("failed"))
            val failure = runCatching {
                VideoApi().generate(profile(server), "follow the references", GenerationParams(size = "832x480", videoSteps = 8, videoInterpolate = false),
                    listOf(image), output, onSubmitted = {}, inputs = VideoInputs(videos = listOf(video), audios = listOf(audio, audio)))
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("失败"))
            assertEquals("/api/v1/uploads", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            val videoRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/api/v1/uploads/video", videoRequest.path)
            val videoBody = videoRequest.body.readUtf8()
            assertTrue(videoBody.contains("name=\"file\"")); assertTrue(videoBody.contains("video/quicktime")); assertTrue(videoBody.contains("VIDEO"))
            repeat(2) {
                val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("/api/v1/uploads/audio", request.path)
                assertEquals("Bearer RB-feature-secret", request.getHeader("Authorization"))
                assertTrue(request.body.readUtf8().contains("audio/wav"))
            }
            val body = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
            assertEquals(listOf("image.png"), body.getJSONArray("images").strings())
            assertEquals(listOf("video.mov"), body.getJSONArray("ref_videos").strings())
            assertEquals(listOf("audio-0.wav", "audio-1.wav"), body.getJSONArray("ref_audios").strings())
            assertEquals(832, body.getInt("width")); assertEquals(480, body.getInt("height"))
            assertEquals(8, body.getInt("sampler_steps")); assertFalse(body.getBoolean("interpolate"))
            assertEquals(6, server.requestCount)
        } finally { listOf(image, video, audio, output).forEach { it.delete() }; server.shutdown() }
    }

    @Test fun `upscale submits source ref and short edge height without requiring prompt or generation parameters`() = runBlocking {
        val server = MockWebServer().apply { start() }; val video = source("mp4", "SOURCE"); val output = source("mp4", "")
        try {
            server.enqueue(MockResponse().setBody("""{"item":{"ref":"source.mp4","seconds":5}}"""))
            server.enqueue(job("pending")); server.enqueue(job("failed"))
            var submitted = ""
            runCatching { VideoApi().generate(profile(server), "", GenerationParams(h3Preset = "flashvsr_upscale", upscaleShortEdge = 1080),
                emptyList(), output, onSubmitted = { submitted = it }, inputs = VideoInputs(source = video, sourceDurationMs = 5000)) }
            assertEquals("72", submitted)
            assertEquals("/api/v1/uploads/video", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/api/v1/jobs", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("flashvsr_upscale", body.getString("preset")); assertEquals("source.mp4", body.getString("video"))
            assertEquals(1080, body.getInt("height")); assertEquals("", body.getString("prompt"))
            for (key in listOf("width", "seconds", "sampler_steps", "interpolate", "images", "ref_videos", "ref_audios")) assertFalse(key, body.has(key))
            assertEquals(3, server.requestCount)
        } finally { video.delete(); output.delete(); server.shutdown() }
    }

    @Test fun `server duration rejects incompatible 1080p before billable submission`() = runBlocking {
        val server = MockWebServer().apply { start() }; val video = source("mp4", "SOURCE"); val output = source("mp4", "")
        try {
            server.enqueue(MockResponse().setBody("""{"item":{"ref":"source.mp4","seconds":12}}"""))
            val failure = runCatching { VideoApi().generate(profile(server), "", GenerationParams(h3Preset = "flashvsr_upscale", upscaleShortEdge = 1080),
                emptyList(), output, onSubmitted = { fail("must not submit") }, inputs = VideoInputs(source = video, sourceDurationMs = 11990)) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("最高支持 720p")); assertEquals(1, server.requestCount)
        } finally { video.delete(); output.delete(); server.shutdown() }
    }

    @Test fun `failed audio upload is redacted and never retried or followed by submission`() = runBlocking {
        val server = MockWebServer().apply { start() }; val audio = source("mp3", "AUDIO"); val output = source("mp4", "")
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"RB-feature-secret upload failed"}"""))
            val failure = runCatching { VideoApi().generate(profile(server), "test", GenerationParams(), emptyList(), output,
                onSubmitted = { fail("must not submit") }, inputs = VideoInputs(audios = listOf(audio))) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("503")); assertFalse(failure?.message.orEmpty().contains("RB-feature-secret"))
            assertEquals(1, server.requestCount); assertEquals("/api/v1/uploads/audio", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
        } finally { audio.delete(); output.delete(); server.shutdown() }
    }

    @Test fun `service progress is preserved and redacted without synthesized percentage`() = runBlocking {
        val server = MockWebServer().apply { start() }; val output = source("mp4", "")
        try {
            server.enqueue(MockResponse().setBody("""{"job":{"status":"running","progress":"rendering 54%"}}"""))
            server.enqueue(MockResponse().setBody("""{"job":{"status":"failed","progress":"RB-feature-secret failed"}}"""))
            val progress = mutableListOf<String>()
            runCatching { VideoApi(pollMs = 1).generate(profile(server), "test", GenerationParams(), emptyList(), output,
                existingId = "72", onSubmitted = { fail("must not submit") }, onProgress = { progress.add(it) }) }
            assertEquals(listOf("rendering 54%", "[已隐藏] failed"), progress)
            assertEquals(2, server.requestCount)
        } finally { output.delete(); server.shutdown() }
    }

    @Test fun `multimodal parameters and file roles round trip with old history defaults`() {
        val params = GenerationParams(size = "832x480", h3Preset = "reference", videoSteps = 8, videoInterpolate = false, upscaleShortEdge = 960)
        val task = ImageTask(prompt = "test", profileName = "H3", model = "reference", apiMode = "h3-video", params = params,
            videoInputs = VideoInputPaths(listOf("ref-video.webm"), listOf("ref-audio.flac"), "source.mp4", 5000), videoProgress = "rendering 54%")
        assertEquals(task, ImageTask.fromJson(task.toJson()))
        assertEquals(listOf("ref-video.webm", "ref-audio.flac", "source.mp4"), task.assetNames)
        assertEquals(40L * 1024 * 1024, task.assetLimit("ref-audio.flac")); assertEquals(100L * 1024 * 1024, task.assetLimit("source.mp4"))
        val legacy = task.toJson().apply { remove("videoInputs"); remove("videoProgress") }
        val restored = ImageTask.fromJson(legacy)
        assertEquals(VideoInputPaths(), restored.videoInputs); assertEquals("", restored.videoProgress)
        val oldParams = GenerationParams().toJson().apply { remove("h3Preset"); remove("videoSteps"); remove("videoInterpolate"); remove("upscaleShortEdge") }
        assertEquals(GenerationParams(), GenerationParams.fromJson(oldParams))
    }

    @Test fun `mode boundaries reject incompatible files duration dimensions step count and oversized audio`() {
        val profile = ApiProfile(baseUrl = "https://h3.coolhs.com/api/v1", apiKey = "RB-test", model = "reference", apiMode = "h3-video")
        val video = source("mp4", "VIDEO"); val audio = source("wav", "AUDIO")
        try {
            fun rejects(params: GenerationParams = GenerationParams(), inputs: VideoInputs = VideoInputs()) {
                assertThrows(IllegalArgumentException::class.java) { VideoApi.validate(profile, params, emptyList(), null, inputs) }
            }
            VideoApi.validate(profile, GenerationParams(size = "256x256"), emptyList(), null)
            VideoApi.validate(profile, GenerationParams(size = "800x512", videoSteps = 8, videoInterpolate = false), emptyList(), null)
            rejects(params = GenerationParams(size = "1456x512")); rejects(params = GenerationParams(videoSteps = 12))
            rejects(params = GenerationParams(h3Preset = "tail_frame"), inputs = VideoInputs(audios = listOf(audio)))
            rejects(inputs = VideoInputs(source = video, sourceDurationMs = 5000))
            rejects(inputs = VideoInputs(videos = List(4) { video })); rejects(inputs = VideoInputs(audios = List(4) { audio }))
            rejects(params = GenerationParams(h3Preset = "flashvsr_upscale", upscaleShortEdge = 1080), inputs = VideoInputs(source = video, sourceDurationMs = 12000))
            rejects(params = GenerationParams(h3Preset = "flashvsr_upscale", upscaleShortEdge = 481), inputs = VideoInputs(source = video, sourceDurationMs = 5000))
            RandomAccessFile(audio, "rw").use { it.setLength(40L * 1024 * 1024 + 1) }
            rejects(inputs = VideoInputs(audios = listOf(audio)))
        } finally { video.delete(); audio.delete() }
    }
}
