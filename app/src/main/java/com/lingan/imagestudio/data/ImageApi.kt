package com.lingan.imagestudio.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GeneratedImage(val bytes: ByteArray, val extension: String)
data class GenerationResult(val images: List<GeneratedImage>, val revisedPrompt: String, val actualSize: String, val actualQuality: String = "", val actualFormat: String = "", val actualBackground: String = "")

object ApiUrls {
    fun base(value: String): HttpUrl {
        val url = value.trim().trimEnd('/').toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "API 地址不能包含用户名或密码" }
        require(url.query == null && url.fragment == null) { "API 地址不能包含查询参数或 #" }
        require(!url.encodedPath.endsWith("/images/generations") && !url.encodedPath.endsWith("/images/edits") && !url.encodedPath.endsWith("/responses")) {
            "请填写基础地址，例如 https://api.example.com/v1，无需填写 images/generations"
        }
        return url
    }

    fun endpoint(value: String, path: String): HttpUrl {
        val url = base(value)
        val prefix = url.encodedPath.trimEnd('/').ifEmpty { "/v1" }
        return url.newBuilder().encodedPath("$prefix/$path").build()
    }

    fun h3Endpoint(value: String, path: String): HttpUrl {
        val url = base(value)
        val prefix = url.encodedPath.trimEnd('/').ifEmpty { "/api/v1" }
        return url.newBuilder().encodedPath("$prefix/$path").build()
    }

    fun validateConnection(profile: ApiProfile) {
        base(profile.baseUrl)
        require(profile.apiKey.isNotBlank()) { "请先在设置中填写 API Key" }
        require(profile.apiKey.none { it.isWhitespace() || it.code !in 33..126 }) { "API Key 包含空格或无效字符" }
        require(profile.timeoutSeconds in 30..1200) { "超时时间应为 30–1200 秒" }
    }
    fun isSiliconFlow(profile: ApiProfile): Boolean = profile.apiMode == "siliconflow" ||
        (profile.apiMode == "images" && base(profile.baseUrl).host.equals("api.siliconflow.cn", true))
    fun validate(profile: ApiProfile) {
        validateConnection(profile)
        require(profile.name.isNotBlank()) { "请输入配置名称" }
        require(profile.model.isNotBlank()) { "请输入生图模型 ID" }
        require(profile.apiMode in listOf("images", "chatgpt2api", "responses", "siliconflow", "video", "xai-video", "h3-video")) { "不支持的接口模式" }
        if (profile.apiMode == "responses") require(profile.responsesModel.isNotBlank()) { "请输入 Responses 主模型 ID" }
    }
}

class ImageApi(private val transport: OkHttpClient = OkHttpClient()) {
    // ChatGPT2API repeats the final mask for remaining references. A white grayscale
    // PNG supplies full opacity at every size, preserving those other references.
    private val keepReferenceMask by lazy {
        Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGP4DwABAQEAsTj2FAAAAABJRU5ErkJggg==")
    }
    private fun client(profile: ApiProfile) = transport.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS).callTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun testConnection(profile: ApiProfile): String {
        ApiUrls.validate(profile)
        val request = Request.Builder().url(if (profile.apiMode == "h3-video") ApiUrls.h3Endpoint(profile.baseUrl, "presets") else ApiUrls.endpoint(profile.baseUrl, "models"))
            .header("Authorization", "Bearer ${profile.apiKey}").build()
        val payload = client(profile).fetch(request, 2 * 1024 * 1024)
        checkResponse(payload, profile)
        return if (profile.isVideo) "连接成功，认证已通过。模型的视频生成权限需在生成时验证。" else "连接成功，认证已通过。模型的生图权限需在生成时验证。"
    }

    suspend fun listModels(profile: ApiProfile): List<String> {
        ApiUrls.validateConnection(profile)
        val request = Request.Builder().url(if (profile.apiMode == "h3-video") ApiUrls.h3Endpoint(profile.baseUrl, "presets") else ApiUrls.endpoint(profile.baseUrl, "models"))
            .header("Authorization", "Bearer ${profile.apiKey}").build()
        val payload = client(profile).fetch(request, 2 * 1024 * 1024)
        checkResponse(payload, profile)
        if (profile.apiMode == "h3-video") {
            val presets = JSONObject(payload.bytes.toString(Charsets.UTF_8)).getJSONArray("presets")
            val available = (0 until minOf(presets.length(), 5000)).mapNotNull { index ->
                val entry = presets.optJSONObject(index) ?: return@mapNotNull null
                entry.optString("name").takeIf { it in H3VideoSpec.presets && entry.optBoolean("available", true) }
            }.distinct()
            if (available.isEmpty()) throw IOException("未找到本应用支持的可用出片方式")
            return available
        }
        val data = try { JSONObject(payload.bytes.toString(Charsets.UTF_8)).getJSONArray("data") }
            catch (_: Exception) { throw IOException("模型列表格式不符合 OpenAI 兼容接口，请检查服务商配置") }
        val models = (0 until minOf(data.length(), 5000)).mapNotNull { index ->
            val entry = data.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
            id.takeIf { when (profile.apiMode) {
                "xai-video" -> id.contains("video", true)
                "video" -> id.contains("T2V", true) || id.contains("I2V", true)
                else -> isImageGenerationModel(entry, id)
            } }
        }.distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        if (models.isEmpty()) throw IOException(if (profile.isVideo) "未识别到视频模型，可手动填写模型 ID" else "未识别到生图模型，请检查服务商或手动填写模型 ID")
        return models
    }

    suspend fun generate(profile: ApiProfile, prompt: String, params: GenerationParams, references: List<File>, mask: File? = null, onResponse: suspend (String) -> Unit = {}, onPreview: (ByteArray) -> Unit = {}): GenerationResult {
        ApiUrls.validate(profile)
        params.validate()
        require(prompt.isNotBlank()) { "请先描述你想生成的画面" }
        require(references.size <= 16) { "最多使用 16 张参考图" }
        require(references.all { it.isFile && it.length() in 1..16L * 1024 * 1024 }) { "参考图不存在或超过 16 MB，请重新选择" }
        if (mask != null) {
            require(references.isNotEmpty()) { "遮罩需要至少一张参考图" }
            require(mask.isFile && mask.length() in 1..4L * 1024 * 1024) { "遮罩不存在或超过 4MB" }
        }
        val siliconflow = ApiUrls.isSiliconFlow(profile)
        val chatgpt2api = profile.apiMode == "chatgpt2api"
        if (chatgpt2api) {
            require(params.format == "auto" && !params.transparent) { "ChatGPT2API 不支持指定输出格式或透明背景，请重置输出选项" }
        }
        if (siliconflow) {
            require(references.isEmpty() && mask == null) { "硅基流动参考图编辑暂未接入，请移除参考图或选择支持的接口" }
            require(!profile.streamImages && !profile.forceBase64) { "硅基流动生图请关闭流式预览和显式 Base64" }
            require(!params.transparent && params.format == "auto" && params.quality == "auto") { "硅基流动请将质量、格式设为自动并关闭透明背景" }
            require(params.count == 1) { "硅基流动每次请求生成一张，请逐张排队" }
        }
        val body = if (siliconflow) {
            JSONObject().put("model", profile.model).put("prompt", prompt)
                .put("image_size", if (params.size == "auto") {
                    if (profile.model.equals("Qwen/Qwen-Image", true)) "1328x1328" else "1024x1024"
                } else params.size).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        } else if (profile.apiMode == "responses") {
            withContext(Dispatchers.IO) { ResponsesImageCodec.request(profile, prompt, params, references, mask)
                .toString().toRequestBody("application/json; charset=utf-8".toMediaType()) }
        } else if (references.isEmpty()) {
            val json = JSONObject().put("model", profile.model).put("prompt", prompt)
                .put("n", params.count)
            if (!chatgpt2api || params.size != "auto") json.put("size", params.size)
            if (chatgpt2api) json.put("quality", params.quality)
            if (profile.advancedParameters && !chatgpt2api) {
                json.put("quality", params.quality)
                if (params.format != "auto") json.put("output_format", params.format)
                else if (params.transparent) json.put("output_format", "png")
                if (params.transparent) json.put("background", "transparent")
            }
            if (profile.streamImages && !chatgpt2api) json.put("stream", true).put("partial_images", 2)
            if (profile.forceBase64 || chatgpt2api) json.put("response_format", "b64_json")
            json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        } else {
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", profile.model).addFormDataPart("prompt", prompt)
                .addFormDataPart("n", params.count.toString())
                .apply {
                    if (!chatgpt2api || params.size != "auto") addFormDataPart("size", params.size)
                    if (chatgpt2api) addFormDataPart("quality", params.quality)
                    if (profile.advancedParameters && !chatgpt2api) {
                        addFormDataPart("quality", params.quality)
                        if (params.format != "auto") addFormDataPart("output_format", params.format)
                        else if (params.transparent) addFormDataPart("output_format", "png")
                        if (params.transparent) addFormDataPart("background", "transparent")
                    }
                    if (profile.streamImages && !chatgpt2api) { addFormDataPart("stream", "true"); addFormDataPart("partial_images", "2") }
                    if (profile.forceBase64 || chatgpt2api) addFormDataPart("response_format", "b64_json")
                    if (mask != null) addFormDataPart("mask", "mask.png", mask.asRequestBody("image/png".toMediaType()))
                    if (chatgpt2api && mask != null && references.size > 1) {
                        addFormDataPart("mask", "keep-references.png", keepReferenceMask.toRequestBody("image/png".toMediaType()))
                    }
                    references.forEachIndexed { index, file ->
                        addFormDataPart("image[]", "reference-${index + 1}.png", file.asRequestBody("image/png".toMediaType()))
                    }
                }.build()
        }
        val path = if (profile.apiMode == "responses") "responses" else if (references.isEmpty()) "images/generations" else "images/edits"
        val request = Request.Builder().url(ApiUrls.endpoint(profile.baseUrl, path))
            .header("Authorization", "Bearer ${profile.apiKey}").post(body).build()
        val response = client(profile).fetch(request, 96 * 1024 * 1024, if (profile.streamImages && !chatgpt2api) ({ source -> ImageStreamParser.parse(source, profile.apiKey, onPreview) }) else null)
        checkResponse(response, profile)
        val raw = try { JSONObject(response.bytes.toString(Charsets.UTF_8)) } catch (_: Exception) {
            throw IOException("接口没有返回有效 JSON，请检查基础地址以及所选接口兼容性")
        }
        if (siliconflow && raw.has("images") && !raw.has("data")) raw.put("data", raw.getJSONArray("images"))
        val json = if (profile.apiMode == "responses" && !raw.has("data")) {
            try { ResponsesImageCodec.normalize(raw) } catch (e: Exception) { throw IOException((e.message ?: "Responses 响应错误").replace(profile.apiKey, "[已隐藏]")) }
        } else raw
        val data = json.optJSONArray("data") ?: throw IOException("响应中没有 data 图片列表。当前仅支持同步 Images API。")
        if (chatgpt2api && data.length() == 0) {
            val message = json.optString("message").replace(profile.apiKey, "[已隐藏]").take(500)
            throw IOException("ChatGPT2API 未返回图片，请检查服务端账号池、额度与日志" + if (message.isBlank()) "" else "\n$message")
        }
        require(data.length() in 1..4) { "接口返回的图片数量不在 1–4 张范围内" }
        // Normalize links before persisting them so downloads can resume without the profile.
        // Base64 results already contain the image; an optional URL must not make success
        // depend on the much smaller URL-only recovery cache.
        for (index in 0 until data.length()) {
            val item = data.getJSONObject(index)
            if (item.optString("b64_json").let { it.isBlank() || it == "null" }) {
                val link = item.optString("url").trim()
                require(link.isNotEmpty() && link != "null") { "服务商未返回有效图片或下载地址" }
                val url = ApiUrls.endpoint(profile.baseUrl, "").resolve(link)
                    ?: throw IOException("服务商返回的图片下载地址无效")
                require(url.username.isEmpty() && url.password.isEmpty()) { "图片下载地址不能包含用户名或密码" }
                item.put("url", url.toString())
            }
        }
        if ((0 until data.length()).all { index -> data.getJSONObject(index).let { item ->
                item.optString("b64_json").let { it.isBlank() || it == "null" } &&
                    item.optString("url").let { it.isNotBlank() && it != "null" }
            } }) {
            onResponse(json.toString())
        }
        return downloadResult(json)
    }

    suspend fun downloadResult(json: JSONObject): GenerationResult {
        val data = json.getJSONArray("data")
        require(data.length() in 1..4) { "图片数量无效" }
        val images = List(data.length()) { index ->
            val item = data.getJSONObject(index)
            val base64 = item.optString("b64_json").takeUnless { it.isBlank() || it == "null" }
            val bytes = if (base64 != null) {
                require(base64.length <= 32 * 1024 * 1024) { "图片数据超过大小限制" }
                try { Base64.getMimeDecoder().decode(base64.substringAfter("base64,", base64)) }
                catch (_: IllegalArgumentException) { throw IOException("接口返回的 Base64 图片无法解码") }
            } else {
                val url = item.optString("url").toHttpUrl()
                // Output URLs can be on another host. Never attach the API credential to them.
                val download = transport.newBuilder().retryOnConnectionFailure(false)
                    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
                    .callTimeout(120, TimeUnit.SECONDS).build()
                    .fetch(Request.Builder().url(url).build(), 24 * 1024 * 1024)
                if (download.code !in 200..299) throw IOException("图片下载失败（HTTP ${download.code}）")
                download.bytes
            }
            require(bytes.size <= 24 * 1024 * 1024) { "图片超过 24 MB，请降低尺寸或质量" }
            GeneratedImage(bytes, imageExtension(bytes))
        }
        return GenerationResult(images, data.optJSONObject(0)?.optString("revised_prompt").orEmpty(), json.optString("size"), json.optString("quality"), json.optString("output_format"), json.optString("background"))
    }

    private fun checkResponse(payload: HttpPayload, profile: ApiProfile) {
        if (payload.code in 200..299) return
        val detail = runCatching {
            val json = JSONObject(payload.bytes.toString(Charsets.UTF_8))
            json.optJSONObject("error")?.optString("message") ?: json.optString("message")
        }.getOrDefault("").replace(profile.apiKey, "[已隐藏]").take(500)
        val hint = when (payload.code) {
            401, 403 -> "认证失败，请检查 API Key、余额和模型权限"
            404 -> "接口或模型不存在，请检查基础地址、模型 ID 和 Images API 支持情况"
            429 -> "请求过于频繁或额度不足，请稍后再试"
            in 300..399 -> "接口发生重定向，请直接填写最终 API 基础地址"
            in 500..599 -> "服务商暂时不可用，请稍后再试"
            else -> "请求失败，请检查参数与服务商兼容性"
        }
        throw IOException("$hint（HTTP ${payload.code}）" + if (detail.isBlank()) "" else "\n$detail")
    }
}

/** Model list responses usually omit capability data, so name matching is only a heuristic. */
internal fun isImageGenerationModel(entry: JSONObject, id: String): Boolean {
    val capabilities = entry.optJSONObject("capabilities")
    val explicit = capabilities?.opt("image_generation") ?: entry.opt("image_generation")
    if (explicit is Boolean) return explicit
    val output = entry.optJSONArray("output_modalities") ?: capabilities?.optJSONArray("output_modalities")
    if (output != null) return (0 until output.length()).any { output.optString(it).equals("image", ignoreCase = true) }
    val name = id.lowercase(java.util.Locale.ROOT)
    if (listOf("embedding", "moderation", "vision-only", "image-encoder", "image-classifier").any { it in name }) return false
    return listOf("gpt-image", "chatgpt-image", "dall-e", "dalle", "stable-diffusion", "stable_diffusion",
        "flux", "imagen", "seedream", "recraft", "ideogram", "kolors", "qwen-image", "playground-v2").any { it in name }
}

internal fun imageExtension(bytes: ByteArray): String = when {
    bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() } -> "png"
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
    else -> throw IOException("接口返回的内容不是 PNG、JPEG 或 WebP 图片")
}

private data class HttpPayload(val code: Int, val bytes: ByteArray)

private suspend fun OkHttpClient.fetch(request: Request, maxBytes: Int, stream: ((okio.BufferedSource) -> ByteArray)? = null): HttpPayload = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    val body = it.body ?: throw IOException("接口返回了空内容")
                    if (it.isSuccessful && stream != null && body.contentType()?.subtype?.equals("event-stream", true) == true) {
                        val bytes = stream(body.source())
                        if (continuation.isActive) continuation.resume(HttpPayload(it.code, bytes))
                        return
                    }
                    require(body.contentLength() <= maxBytes) { "响应超过大小限制，请减少生成数量" }
                    val buffer = ByteArray(8192)
                    val output = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= maxBytes) { "响应超过大小限制，请减少生成数量" }
                            output.write(buffer, 0, count)
                        }
                    }
                    if (continuation.isActive) continuation.resume(HttpPayload(it.code, output.toByteArray()))
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
