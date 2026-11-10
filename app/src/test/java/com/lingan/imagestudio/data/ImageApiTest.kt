package com.lingan.imagestudio.data

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class ImageApiTest {
    private lateinit var server: MockWebServer
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    private val api = ImageApi()
    private fun profile() = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "test-secret", model = "custom-image-model")
    private fun response() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        JSONObject().put("size", "1024x1024").put("data", org.json.JSONArray().put(JSONObject().put("b64_json", Base64.getEncoder().encodeToString(png)))).toString())

    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    private fun chatgpt2api() = ApiPresets.all.first { it.id == "chatgpt2api-images" }.applyTo(ApiProfile())
        .copy(baseUrl = server.url("/gateway/v1").toString(), apiKey = "gateway-secret")

    @Test fun `relative image links are resolved before caching and resume without generation`() = runBlocking {
        val link = "results/image.png?signature=abc%2Fdef&expires=123"
        server.enqueue(MockResponse().setBody("""{"data":[{"url":"$link"}]}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        var cached = ""
        val failure = runCatching { api.generate(profile(), "test", GenerationParams(), emptyList(), onResponse = { cached = it }) }
        assertTrue(failure.isFailure)
        assertEquals(server.url("/v1/$link").toString(), JSONObject(cached).getJSONArray("data").getJSONObject(0).getString("url"))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(png)))
        assertArrayEquals(png, api.downloadResult(JSONObject(cached)).images.single().bytes)
        assertEquals("POST", server.takeRequest().method)
        repeat(2) {
            val download = server.takeRequest()
            assertEquals("/v1/$link", download.path)
            assertNull(download.getHeader("Authorization"))
        }
    }

    @Test fun `base64 result with optional URL does not depend on download cache capacity`() = runBlocking {
        // A valid image response can be larger than the 256 KiB private URL cache.
        val bytes = png + ByteArray(300 * 1024)
        val body = JSONObject().put("data", org.json.JSONArray().put(JSONObject()
            .put("b64_json", Base64.getEncoder().encodeToString(bytes)).put("url", server.url("/optional.png"))))
        server.enqueue(MockResponse().setBody(body.toString()))
        val result = api.generate(profile(), "test", GenerationParams(), emptyList(), onResponse = {
            require(it.toByteArray().size <= 256 * 1024) { "图片下载信息过大" }
        })
        assertArrayEquals(bytes, result.images.single().bytes)
        assertEquals(1, server.requestCount)
    }

    @Test fun `failed image download can resume without another generation request`() = runBlocking {
        val url = server.url("/result.png").toString()
        server.enqueue(MockResponse().setBody(JSONObject().put("data", org.json.JSONArray().put(JSONObject().put("url", url))).toString()))
        server.enqueue(MockResponse().setResponseCode(503))
        var saved = ""
        val failure = runCatching { api.generate(profile(), "test", GenerationParams(), emptyList(), onResponse = { saved = it }) }
        assertTrue(failure.isFailure)
        assertTrue(saved.isNotBlank())
        server.enqueue(MockResponse().setBody(okio.Buffer().write(png)))
        val result = api.downloadResult(JSONObject(saved))
        assertArrayEquals(png, result.images.single().bytes)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        val retried = server.takeRequest()
        assertEquals("GET", retried.method)
        assertNull(retried.getHeader("Authorization"))
        assertEquals(3, server.requestCount)
    }

    @Test fun `ChatGPT2API generation uses synchronous base64 and omits auto size`() = runBlocking {
        val b64 = Base64.getEncoder().encodeToString(png)
        server.enqueue(MockResponse().setBody("""{"created":123,"data":[{"b64_json":"$b64"},{"b64_json":"$b64"}],"usage":{}}"""))
        // Mode guarantees the supported wire format even for a previously edited profile.
        val result = api.generate(chatgpt2api().copy(streamImages = true, forceBase64 = false), "一只猫", GenerationParams(count = 2, quality = "high"), emptyList())
        assertEquals(2, result.images.size)
        assertArrayEquals(png, result.images.first().bytes)
        val request = server.takeRequest()
        assertEquals("/gateway/v1/images/generations", request.path)
        assertEquals("Bearer gateway-secret", request.getHeader("Authorization"))
        val json = JSONObject(request.body.readUtf8())
        assertEquals(setOf("model", "prompt", "n", "quality", "response_format"), json.keys().asSequence().toSet())
        assertEquals("gpt-image-2", json.getString("model"))
        assertEquals("一只猫", json.getString("prompt")); assertEquals(2, json.getInt("n"))
        assertEquals("high", json.getString("quality")); assertEquals("b64_json", json.getString("response_format"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `ChatGPT2API edits send multiple references mask size and codex model`() = runBlocking {
        val file = File.createTempFile("reference", ".png").apply { writeBytes(png) }
        try {
            server.enqueue(response())
            api.generate(chatgpt2api().copy(model = "codex-gpt-image-2"), "修改选区", GenerationParams(size = "1536x1024", quality = "high"), listOf(file, file), file)
            val request = server.takeRequest()
            assertEquals("/gateway/v1/images/edits", request.path)
            assertEquals("Bearer gateway-secret", request.getHeader("Authorization"))
            val body = request.body.readByteArray().toString(Charsets.ISO_8859_1)
            assertEquals(2, Regex(Regex.escape("name=\"image[]\"")).findAll(body).count())
            assertTrue(body.contains("name=\"mask\"; filename=\"mask.png\""))
            assertEquals(2, Regex(Regex.escape("name=\"mask\"")).findAll(body).count())
            val keepMask = body.substringAfter("filename=\"keep-references.png\"").substringAfter("\r\n\r\n").substringBefore("\r\n--")
                .toByteArray(Charsets.ISO_8859_1)
            // Decode the grayscale PNG sample: the upstream uses it as alpha for
            // every subsequent reference, so it must be fully opaque (255).
            val input = java.io.DataInputStream(keepMask.inputStream())
            val signature = ByteArray(8).also { input.readFully(it) }
            assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), signature)
            val compressed = java.io.ByteArrayOutputStream()
            while (input.available() > 0) {
                val length = input.readInt()
                val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                val chunk = ByteArray(length).also { input.readFully(it) }
                input.readInt() // CRC
                if (type == "IHDR") {
                    val header = java.io.DataInputStream(chunk.inputStream())
                    assertEquals(1, header.readInt()); assertEquals(1, header.readInt())
                    assertEquals(8, header.readUnsignedByte()); assertEquals(0, header.readUnsignedByte())
                }
                if (type == "IDAT") compressed.write(chunk)
            }
            val pixels = java.util.zip.InflaterInputStream(compressed.toByteArray().inputStream()).use { it.readBytes() }
            assertArrayEquals(byteArrayOf(0, 255.toByte()), pixels)
            mapOf("model" to "codex-gpt-image-2", "prompt" to "修改选区", "n" to "1", "size" to "1536x1024", "quality" to "high", "response_format" to "b64_json").forEach { (key, value) ->
                val encodedValue = value.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)
                val part = Regex("name=\"$key\"\r\n(?:[^\r\n]+\r\n)*\r\n${Regex.escape(encodedValue)}\r\n")
                assertTrue("Missing form field $key", part.containsMatchIn(body))
            }
            listOf("stream", "partial_images", "output_format", "background").forEach { assertFalse(body.contains("name=\"$it\"")) }
        } finally { file.delete() }
    }

    @Test fun `ChatGPT2API auto size is also omitted in edits`() = runBlocking {
        val file = File.createTempFile("reference", ".png").apply { writeBytes(png) }
        try {
            server.enqueue(response())
            api.generate(chatgpt2api(), "edit", GenerationParams(), listOf(file))
            assertFalse(server.takeRequest().body.readUtf8().contains("name=\"size\""))
        } finally { file.delete() }
    }

    @Test fun `ChatGPT2API rejects unsupported output options without submission`() = runBlocking {
        listOf(GenerationParams(format = "jpeg"), GenerationParams(transparent = true)).forEach { params ->
            val failure = runCatching { api.generate(chatgpt2api(), "test", params, emptyList()) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("重置输出选项"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `ChatGPT2API model listing includes both image aliases`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-image-2"},{"id":"codex-gpt-image-2"},{"id":"auto"},{"id":"gpt-5"}]}"""))
        assertEquals(listOf("codex-gpt-image-2", "gpt-image-2"), api.listModels(chatgpt2api()))
        val request = server.takeRequest()
        assertEquals("GET", request.method); assertEquals("/gateway/v1/models", request.path)
        assertEquals("Bearer gateway-secret", request.getHeader("Authorization"))
    }

    @Test fun `ChatGPT2API empty result explains account pool failure without exposing key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[],"message":"gateway-secret no available account"}"""))
        val failure = runCatching { api.generate(chatgpt2api(), "test", GenerationParams(), emptyList()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("账号池"))
        assertTrue(failure?.message.orEmpty().contains("no available account"))
        assertFalse(failure?.message.orEmpty().contains("gateway-secret"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `Grok video models exclude chat and image models`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"grok-4.5"},{"id":"grok-imagine-image"},{"id":"grok-imagine-video-1.5"},{"id":"grok-imagine-video"}]}"""))
        assertEquals(listOf("grok-imagine-video", "grok-imagine-video-1.5"), api.listModels(profile().copy(apiMode = "xai-video")))
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path); assertEquals("Bearer test-secret", request.getHeader("Authorization"))
    }

    @Test fun `generation sends custom model authorization and advanced fields`() = runBlocking {
        server.enqueue(response())
        val result = api.generate(profile(), "一片森林", GenerationParams(count = 2, transparent = true), emptyList())
        val request = server.takeRequest()
        assertEquals("/v1/images/generations", request.path)
        assertEquals("Bearer test-secret", request.getHeader("Authorization"))
        val json = JSONObject(request.body.readUtf8())
        assertEquals("custom-image-model", json.getString("model"))
        assertEquals("一片森林", json.getString("prompt"))
        assertEquals(2, json.getInt("n"))
        assertEquals("transparent", json.getString("background"))
        assertFalse(json.has("response_format"))
        assertArrayEquals(png, result.images.single().bytes)
        assertEquals("png", result.images.single().extension)
        assertEquals("1024x1024", result.actualSize)
    }

    @Test fun `minimal provider mode omits unsupported optional parameters`() = runBlocking {
        server.enqueue(response())
        api.generate(profile().copy(advancedParameters = false, forceBase64 = true), "test", GenerationParams(), emptyList())
        val json = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(json.has("output_format")); assertFalse(json.has("quality")); assertFalse(json.has("background"))
        assertEquals("b64_json", json.getString("response_format"))
    }

    @Test fun `reference edits upload multipart image array without base64 default`() = runBlocking {
        val file = File.createTempFile("reference", ".png").apply { writeBytes(png) }
        try {
            server.enqueue(response())
            api.generate(profile(), "change sky", GenerationParams(), listOf(file))
            val request = server.takeRequest()
            assertEquals("/v1/images/edits", request.path)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"image[]\"; filename=\"reference-1.png\""))
            assertTrue(body.contains("Content-Type: image/png"))
            assertTrue(body.contains("change sky"))
            assertFalse(body.contains("response_format"))
        } finally { file.delete() }
    }

    @Test fun `sixteen references upload and seventeen are rejected`() = runBlocking {
        val file = File.createTempFile("reference", ".png").apply { writeBytes(png) }
        try {
            server.enqueue(response())
            api.generate(profile(), "edit", GenerationParams(), List(16) { file })
            val body = server.takeRequest().body.readUtf8()
            assertEquals(16, Regex(Regex.escape("name=\"image[]\"")).findAll(body).count())
            assertTrue(runCatching { api.generate(profile(), "edit", GenerationParams(), List(17) { file }) }.isFailure)
            assertEquals(1, server.requestCount)
        } finally { file.delete() }
    }

    @Test fun `mask edits include mask multipart and reject mask without reference`() = runBlocking {
        val image = File.createTempFile("image", ".png").apply { writeBytes(png) }
        val mask = File.createTempFile("mask", ".png").apply { writeBytes(png) }
        try {
            server.enqueue(response())
            api.generate(profile(), "change selected area", GenerationParams(), listOf(image), mask)
            val request = server.takeRequest()
            assertEquals("/v1/images/edits", request.path)
            assertTrue(request.body.readUtf8().contains("name=\"mask\"; filename=\"mask.png\""))
            assertTrue(runCatching { api.generate(profile(), "test", GenerationParams(), emptyList(), mask) }.isFailure)
            assertEquals(1, server.requestCount)
        } finally { image.delete(); mask.delete() }
    }

    @Test fun `siliconflow qwen accepts native images response`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"images":[{"url":"${server.url("/image.png")}"}]}"""))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(png)))
        val provider = profile().copy(baseUrl = server.url("/v1").toString(), model = "Qwen/Qwen-Image", apiMode = "siliconflow")
        val result = api.generate(provider, "test", GenerationParams(), emptyList())
        assertArrayEquals(png, result.images.single().bytes)
        val request = server.takeRequest()
        val json = JSONObject(request.body.readUtf8())
        assertEquals("1328x1328", json.getString("image_size"))
        assertFalse(json.has("size")); assertFalse(json.has("n")); assertFalse(json.has("quality"))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `siliconflow detection is restricted and unsupported settings fail before request`() = runBlocking {
        assertTrue(ApiUrls.isSiliconFlow(profile().copy(baseUrl = "https://api.siliconflow.cn/v1")))
        assertFalse(ApiUrls.isSiliconFlow(profile().copy(baseUrl = "https://api.siliconflow.cn.evil.test/v1")))
        assertFalse(ApiUrls.isSiliconFlow(profile().copy(baseUrl = "https://api.siliconflow.cn/v1", apiMode = "responses")))
        val provider = profile().copy(apiMode = "siliconflow", model = "Qwen/Qwen-Image")
        assertTrue(runCatching { api.generate(provider.copy(streamImages = true), "test", GenerationParams(), emptyList()) }.isFailure)
        assertTrue(runCatching { api.generate(provider, "test", GenerationParams(count = 2), emptyList()) }.isFailure)
        assertTrue(runCatching { api.generate(provider, "test", GenerationParams(transparent = true), emptyList()) }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun `output URL downloads do not contain bearer key`() = runBlocking {
        val otherHost = MockWebServer()
        otherHost.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"url":"${otherHost.url("/output.png")}"}]}"""))
            otherHost.enqueue(MockResponse().setBody(okio.Buffer().write(png)))
            val result = api.generate(profile(), "test", GenerationParams(), emptyList())
            assertEquals("/v1/images/generations", server.takeRequest().path)
            assertNull(otherHost.takeRequest().getHeader("Authorization"))
            assertArrayEquals(png, result.images.first().bytes)
        } finally { otherHost.shutdown() }
    }

    @Test fun `authentication error redacts secret and does not retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid key test-secret"}}"""))
        val error = runCatching { api.generate(profile(), "test", GenerationParams(), emptyList()) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("401"))
        assertFalse(error.message!!.contains("test-secret"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `generation never follows credential redirects`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
        val error = runCatching { api.generate(profile(), "test", GenerationParams(), emptyList()) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("重定向"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `malformed successful response is actionable`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>incorrect API base</html>"))
        val error = runCatching { api.generate(profile(), "test", GenerationParams(), emptyList()) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("有效 JSON"))
    }

    @Test fun `non image response is rejected`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"aGVsbG8="}]}"""))
        val error = runCatching { api.generate(profile(), "test", GenerationParams(), emptyList()) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("不是 PNG"))
    }

    @Test fun `oversized response is rejected without allocation`() = runBlocking {
        server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 101 * 1024 * 1024))
        val error = runCatching { api.generate(profile(), "test", GenerationParams(), emptyList()) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("大小限制"))
    }

    @Test fun `cancellation terminates pending network request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { api.generate(profile(), "test", GenerationParams(), emptyList()) }
        val request = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
        assertNotNull(request)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun `model listing uses supplied credentials without requiring existing model`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"z-model"},{"id":"gpt-image-test"},{"id":"z-model"},{"object":"model"}]}"""))
        assertEquals(listOf("gpt-image-test"), api.listModels(profile().copy(name = "", model = "")))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer test-secret", request.getHeader("Authorization"))
    }

    @Test fun `model listing filters text vision embeddings and honors output capabilities`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-4o"},{"id":"gpt-image-1"},{"id":"text-image-embedding"},{"id":"my-custom","output_modalities":["text","image"]},{"id":"flux-disabled","capabilities":{"image_generation":false}},{"id":"vision-only","input_modalities":["image"],"output_modalities":["text"]}]}"""))
        assertEquals(listOf("gpt-image-1", "my-custom"), api.listModels(profile()))
    }

    @Test fun `generation captures actual quality format and background`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}],"quality":"high","output_format":"png","background":"opaque"}"""))
        val result = api.generate(profile(), "test", GenerationParams(), emptyList())
        assertEquals("high", result.actualQuality)
        assertEquals("png", result.actualFormat)
        assertEquals("opaque", result.actualBackground)
    }

    @Test fun `streaming previews do not replace final output`() = runBlocking {
        val b64 = Base64.getEncoder().encodeToString(png)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(
            "event: image_generation.partial_image\r\ndata: {\"b64_json\":\"$b64\"}\r\n\r\n" +
            "event: image_generation.completed\ndata: {\"b64_json\":\"$b64\",\"quality\":\"high\"}\n\n", 17))
        var previews = 0
        val result = api.generate(profile().copy(streamImages = true), "stream", GenerationParams(), emptyList()) { bytes ->
            assertArrayEquals(png, bytes); previews++
        }
        assertEquals(1, previews)
        assertEquals(1, result.images.size)
        assertEquals("high", result.actualQuality)
        val request = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(request.getBoolean("stream")); assertEquals(2, request.getInt("partial_images"))
    }

    @Test fun `stream without completed event fails instead of saving preview`() = runBlocking {
        val b64 = Base64.getEncoder().encodeToString(png)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"type\":\"image_edit.partial_image\",\"b64_json\":\"$b64\"}\n\n"))
        val error = runCatching { api.generate(profile().copy(streamImages = true), "stream", GenerationParams(), emptyList()) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("没有完成图片"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `stream errors redact secrets and never retry`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: error\ndata: {\"message\":\"test-secret denied\"}\n\n"))
        val error = runCatching { api.generate(profile().copy(streamImages = true), "stream", GenerationParams(), emptyList()) }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("test-secret")); assertEquals(1, server.requestCount)
    }

    @Test fun `stream enabled accepts ordinary JSON without making another request`() = runBlocking {
        server.enqueue(response())
        val result = api.generate(profile().copy(streamImages = true), "stream", GenerationParams(), emptyList())
        assertEquals(1, result.images.size); assertEquals(1, server.requestCount)
    }

    @Test fun `edit stream sends multipart stream flags and accepts unterminated last line`() = runBlocking {
        val b64 = Base64.getEncoder().encodeToString(png)
        val file = File.createTempFile("reference", ".png").apply { writeBytes(png) }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: image_edit.completed\ndata: {\"b64_json\":\"$b64\"}"))
            assertEquals(1, api.generate(profile().copy(streamImages = true), "edit", GenerationParams(), listOf(file)).images.size)
            val request = server.takeRequest()
            assertEquals("/v1/images/edits", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"stream\"")); assertTrue(body.contains("name=\"partial_images\""))
        } finally { file.delete() }
    }

    @Test fun `responses uses separate primary and image models with references mask and no storage`() = runBlocking {
        val b64 = Base64.getEncoder().encodeToString(png)
        val file = File.createTempFile("reference", ".png").apply { writeBytes(png) }
        try {
            server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"message"},{"type":"image_generation_call","status":"completed","result":"$b64","quality":"high"}]}"""))
            val result = api.generate(profile().copy(apiMode = "responses", responsesModel = "primary-model", forceBase64 = true), "edit", GenerationParams(), listOf(file), file)
            assertEquals("high", result.actualQuality)
            assertArrayEquals(png, result.images.single().bytes)
            val request = server.takeRequest()
            assertEquals("/v1/responses", request.path)
            val json = JSONObject(request.body.readUtf8())
            assertEquals("primary-model", json.getString("model"))
            assertFalse(json.getBoolean("store")); assertFalse(json.has("response_format")); assertFalse(json.has("n"))
            val tool = json.getJSONArray("tools").getJSONObject(0)
            assertEquals("custom-image-model", tool.getString("model"))
            assertTrue(tool.getJSONObject("input_image_mask").getString("image_url").startsWith("data:image/png;base64,"))
            assertEquals("input_image", json.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
        } finally { file.delete() }
    }

    @Test fun `responses validates main model and rejects text only or incomplete results`() = runBlocking {
        val profile = profile().copy(apiMode = "responses", responsesModel = "primary")
        assertTrue(runCatching { api.generate(profile.copy(responsesModel = ""), "test", GenerationParams(), emptyList()) }.isFailure)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"message"}]}"""))
        assertTrue(runCatching { api.generate(profile, "test", GenerationParams(), emptyList()) }.exceptionOrNull()!!.message!!.contains("未返回生图"))
        server.enqueue(MockResponse().setBody("""{"status":"failed","error":{"message":"test-secret denied"}}"""))
        val error = runCatching { api.generate(profile, "test", GenerationParams(), emptyList()) }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("test-secret")); assertEquals(2, server.requestCount)
    }

    @Test fun `responses stream supports partial and completed image tool output`() = runBlocking {
        val b64 = Base64.getEncoder().encodeToString(png)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "event: response.image_generation_call.partial_image\ndata: {\"partial_image_b64\":\"$b64\"}\n\n" +
            "event: response.completed\ndata: {\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"image_generation_call\",\"result\":\"$b64\"}]}}\n\n"))
        var previews = 0
        val result = api.generate(profile().copy(apiMode = "responses", responsesModel = "primary", streamImages = true), "stream", GenerationParams(), emptyList()) { previews++ }
        assertEquals(1, previews); assertEquals(1, result.images.size)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.getBoolean("stream")); assertEquals(2, body.getJSONArray("tools").getJSONObject(0).getInt("partial_images"))
    }

    @Test fun `model listing reports malformed or empty response`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertTrue(runCatching { api.listModels(profile()) }.exceptionOrNull()!!.message!!.contains("手动填写"))
        server.enqueue(MockResponse().setBody("not json"))
        assertTrue(runCatching { api.listModels(profile()) }.exceptionOrNull()!!.message!!.contains("格式"))
    }

    @Test fun `connection test uses models endpoint without image generation`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertTrue(api.testConnection(profile()).contains("连接成功"))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
    }

    @Test fun `missing key and blank prompts fail before network`() = runBlocking {
        assertTrue(runCatching { api.generate(profile().copy(apiKey = ""), "test", GenerationParams(), emptyList()) }.isFailure)
        assertTrue(runCatching { api.generate(profile(), " ", GenerationParams(), emptyList()) }.isFailure)
        assertEquals(0, server.requestCount)
    }
}
