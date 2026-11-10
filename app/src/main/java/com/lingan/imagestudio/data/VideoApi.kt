package com.lingan.imagestudio.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Asynchronous video APIs. Submit is never automatically retried. */
class VideoApi(
    private val transport: OkHttpClient = OkHttpClient(),
    private val pollMs: Long = 10_000,
    private val waitTimeoutMs: Long = TimeUnit.HOURS.toMillis(1),
) {
    companion object {
        val models = listOf("Wan-AI/Wan2.2-T2V-A14B", "Wan-AI/Wan2.2-I2V-A14B")
        val sizes = listOf("1280x720", "720x1280", "960x960")
        val xaiModels = listOf("grok-imagine-video-1.5", "grok-imagine-video-1.5-lite", "grok-imagine-video")
        val xaiAspectRatios = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "3:2", "2:3")
        fun xaiResolutions(model: String): List<String> = if (model == "grok-imagine-video") listOf("480p", "720p") else listOf("480p", "720p", "1080p")
        fun validate(profile: ApiProfile, params: GenerationParams, refs: List<File>, mask: File?, inputs: VideoInputs = VideoInputs()) {
            ApiUrls.validate(profile)
            require(profile.isVideo) { "请选择视频接口" }
            require(params.count == 1) { "视频每次生成一个" }
            require(mask == null) { "视频不支持遮罩，请移除编辑选区" }
            if (profile.apiMode == "h3-video") {
                H3VideoSpec.validate(profile, params, refs, inputs)
            } else if (profile.apiMode == "xai-video") {
                require(inputs.allFiles.isEmpty()) { "当前接口不支持音视频素材，请先移除" }
                require(params.videoDuration in 1..15) { "Grok 视频时长应为 1–15 秒" }
                require(params.videoResolution in xaiResolutions(profile.model)) { "当前 Grok 模型不支持所选清晰度" }
                require(params.videoAspectRatio in xaiAspectRatios) { "不支持的视频画幅" }
                require(refs.size <= 1) { "Grok 图生视频仅支持一张首帧图" }
            } else {
                require(inputs.allFiles.isEmpty()) { "当前接口不支持音视频素材，请先移除" }
                require(params.size in sizes + "auto") { "视频尺寸请选择 1280x720、720x1280 或 960x960" }
                require(refs.size == (if (profile.model.contains("I2V", true)) 1 else 0)) { "图生视频需要一张首帧图；文生视频请移除参考图" }
            }
            require(refs.all { it.isFile && it.length() in 1..16L * 1024 * 1024 }) { "首帧图缺失或超过 16MB" }
        }
    }
    private val client = transport.newBuilder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    private suspend fun <T> fetch(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try { val result = response.use(read); continuation.resume(result) }
                catch (e: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            }
        })
    }
    private suspend fun post(profile: ApiProfile, path: String, body: JSONObject): JSONObject {
        val url = if (profile.apiMode == "h3-video") ApiUrls.h3Endpoint(profile.baseUrl, path) else ApiUrls.endpoint(profile.baseUrl, path)
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${profile.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return json(profile, request)
    }
    private suspend fun json(profile: ApiProfile, request: Request): JSONObject {
        return fetch(request) { response ->
            val bytes = response.body?.byteStream()?.use { stream -> val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val n = stream.read(buffer); if (n < 0) break; require(output.size() + n <= 1024 * 1024) { "视频接口响应过大" }; output.write(buffer, 0, n) }; output.toByteArray() } ?: error("空响应")
            require(bytes.size <= 1024 * 1024) { "视频接口响应过大" }
            val text = bytes.toString(Charsets.UTF_8)
            if (!response.isSuccessful) throw IOException("视频接口 HTTP ${response.code}：${text.replace(profile.apiKey, "[已隐藏]").take(600)}")
            JSONObject(text)
        }
    }
    private class ExpiredDownloadException(code: Int) : IOException("视频下载链接不可用 HTTP $code；可继续查询原任务")
    private class ContentAuthenticationRequired : IOException()
    private class DownloadRedirect(val location: String) : IOException()

    private suspend fun download(profile: ApiProfile, url: String, destination: File, requestId: String) {
        val parsed = ApiUrls.endpoint(profile.baseUrl, "").resolve(url.trim())
            ?: throw IOException("服务商返回的视频下载地址无效；请保留原任务 ID 联系服务商")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "视频下载地址不能包含用户名或密码" }
        require(parsed.isHttps || profile.allowHttpVideoDownload ||
            ApiUrls.base(profile.baseUrl).host in listOf("localhost", "127.0.0.1")) {
            "视频已生成，但服务商返回 HTTP 下载链接。请在原 API 配置中开启“允许 HTTP 视频下载”，保存后继续查询原任务，无需重新生成。"
        }
        val part = File(destination.path + ".part")
        try {
            val base = ApiUrls.base(profile.baseUrl)
            val ownContentPaths = listOf("videos", "videos/generations", "videos/edits", "videos/extensions").map { path ->
                ApiUrls.endpoint(profile.baseUrl, path).newBuilder().addPathSegment(requestId).addPathSegment("content").build().encodedPath
            }
            val ownContent = requestId.isNotBlank() && parsed.scheme == base.scheme && parsed.host == base.host &&
                parsed.port == base.port && parsed.encodedPath in ownContentPaths
            val ownH3Content = profile.apiMode == "h3-video" && requestId.isNotBlank() &&
                parsed.scheme == base.scheme && parsed.host == base.host && parsed.port == base.port &&
                parsed.encodedPath == ApiUrls.h3Endpoint(profile.baseUrl, "jobs").newBuilder()
                    .addPathSegment(requestId).addPathSegment("video").build().encodedPath
            suspend fun transfer(target: HttpUrl, authenticated: Boolean, allowRedirect: Boolean) {
                val request = Request.Builder().url(target).apply {
                    if (authenticated) header("Authorization", "Bearer ${profile.apiKey}")
                }.build()
                fetch(request) { response ->
                    if (allowRedirect && response.code in listOf(301, 302, 303, 307, 308)) {
                        throw DownloadRedirect(response.header("Location") ?: throw IOException("视频重定向缺少地址"))
                    }
                    if (response.code == 401 && ownContent && !authenticated) throw ContentAuthenticationRequired()
                    if (response.code in listOf(401, 403, 404, 410)) throw ExpiredDownloadException(response.code)
                    require(response.isSuccessful) { "视频下载失败 HTTP ${response.code}；可继续查询任务" }
                    val input = response.body?.byteStream() ?: error("视频内容为空")
                    input.use { stream -> part.outputStream().use { output ->
                        val buffer = ByteArray(65536); var total = 0L
                        while (true) { val n = stream.read(buffer); if (n < 0) break; total += n; require(total <= 256L * 1024 * 1024) { "视频超过 256MB" }; output.write(buffer, 0, n) }
                    } }
                }
            }
            // Only the exact H3 task endpoint may initiate redirects. The card is never forwarded.
            var target = parsed
            var authenticated = ownH3Content
            var redirects = 0
            while (true) {
                try {
                    try { transfer(target, authenticated, ownH3Content) }
                    catch (_: ContentAuthenticationRequired) { transfer(target, true, false) }
                    break
                } catch (redirect: DownloadRedirect) {
                    require(++redirects <= 5) { "视频下载重定向次数过多" }
                    val next = target.resolve(redirect.location) ?: throw IOException("视频重定向地址无效")
                    require(next.username.isEmpty() && next.password.isEmpty()) { "视频重定向地址不能包含用户名或密码" }
                    require(next.isHttps || profile.allowHttpVideoDownload ||
                        (target.host in listOf("localhost", "127.0.0.1") && next.host in listOf("localhost", "127.0.0.1"))) {
                        "视频重定向到 HTTP 地址，请检查原 API 配置中的下载设置"
                    }
                    target = next
                    authenticated = false
                }
            }
            require(part.length() >= 12) { "视频内容无效" }
            require(part.inputStream().use { stream -> stream.skip(4); ByteArray(4).also { require(stream.read(it) == 4) }.toString(Charsets.US_ASCII) } == "ftyp") { "服务商未返回 MP4 视频" }
            check(part.renameTo(destination)) { "视频保存失败" }
        } finally { part.delete() }
    }

    suspend fun generate(profile: ApiProfile, prompt: String, params: GenerationParams, refs: List<File>, destination: File,
                         existingId: String = "", onSubmitted: suspend (String) -> Unit, onStatus: suspend (String) -> Unit = {},
                         cachedDownloadUrl: String = "", onDownloadReady: suspend (String) -> Unit = {},
                         inputs: VideoInputs = VideoInputs(), onProgress: suspend (String) -> Unit = {}) {
        require(waitTimeoutMs > 0) { "视频等待时限必须大于零" }
        val completed = withTimeoutOrNull(waitTimeoutMs) {
            generateWithinDeadline(profile, prompt, params, refs, destination, existingId, onSubmitted, onStatus, cachedDownloadUrl, onDownloadReady, inputs, onProgress)
            true
        }
        if (completed == null) throw IOException("视频等待超时，本地已停止等待；服务商可能仍在处理，可在任务详情继续查询原任务，无需重新生成")
    }

    private suspend fun generateWithinDeadline(profile: ApiProfile, prompt: String, params: GenerationParams, refs: List<File>, destination: File,
                         existingId: String, onSubmitted: suspend (String) -> Unit, onStatus: suspend (String) -> Unit,
                         cachedDownloadUrl: String, onDownloadReady: suspend (String) -> Unit, inputs: VideoInputs, onProgress: suspend (String) -> Unit) {
        ApiUrls.validate(profile)
        require(profile.isVideo) { "请选择视频接口" }
        if (existingId.isNotBlank() && cachedDownloadUrl.isNotBlank()) {
            try {
                onStatus("Downloading")
                download(profile, cachedDownloadUrl, destination, existingId)
                return
            } catch (_: ExpiredDownloadException) {
                // Only an expired/unavailable cached link falls back to the original status query.
            }
        }
        if (existingId.isBlank()) validate(profile, params, refs, null, inputs)
        require(prompt.isNotBlank() || H3VideoSpec.isUpscale(profile, params)) { "请输入视频提示词" }
        if (profile.apiMode == "h3-video") {
            generateH3(profile, prompt, params, refs, destination, existingId, onSubmitted, onStatus, onDownloadReady, inputs, onProgress)
            return
        }
        val xai = profile.apiMode == "xai-video"
        val id = existingId.ifBlank {
            onStatus("Submitting")
            val body = JSONObject().put("model", profile.model).put("prompt", prompt)
            if (xai) {
                body.put("duration", params.videoDuration).put("resolution", params.videoResolution)
                if (refs.isEmpty()) body.put("aspect_ratio", params.videoAspectRatio)
            } else body.put("image_size", if (params.size == "auto") "1280x720" else params.size)
            if (refs.isNotEmpty()) {
                val image = "data:image/png;base64," + Base64.getEncoder().encodeToString(refs.first().readBytes())
                body.put("image", if (xai) JSONObject().put("url", image) else image)
            }
            post(profile, if (xai) "videos/generations" else "video/submit", body)
                .getString(if (xai) "request_id" else "requestId").also { require(it.isNotBlank()); onSubmitted(it) }
        }
        onStatus("Querying")
        while (true) {
            val result = if (xai) {
                val url = ApiUrls.endpoint(profile.baseUrl, "videos").newBuilder().addPathSegment(id).build()
                json(profile, Request.Builder().url(url).header("Authorization", "Bearer ${profile.apiKey}").build())
            } else post(profile, "video/status", JSONObject().put("requestId", id))
            val state = result.getString("status")
            onStatus(state)
            val normalized = if (xai) when (state) {
                "done" -> "Succeed"
                "pending" -> "InProgress"
                "failed", "expired" -> "Failed"
                else -> "unknown"
            } else state
            when (normalized) {
                "Succeed" -> {
                    val video = if (xai) result.getJSONObject("video") else result.getJSONObject("results").getJSONArray("videos").getJSONObject(0)
                    require(!xai || video.optBoolean("respect_moderation", true)) { "视频未通过服务商内容审核" }
                    val url = video.getString("url")
                    onDownloadReady(url)
                    onStatus("Downloading")
                    download(profile, url, destination, id)
                    return
                }
                "Failed" -> {
                    val reason = if (xai && state == "expired") "任务已过期，无法继续查询" else
                        result.optString("reason").ifBlank { result.optJSONObject("error")?.optString("message").orEmpty() }
                            .ifBlank { result.optString("error").ifBlank { "服务商未提供失败原因" } }
                    throw IOException("视频生成失败：${reason.replace(profile.apiKey, "[已隐藏]").take(600)}")
                }
                "InQueue", "InProgress" -> delay(pollMs)
                else -> throw IOException("未知视频任务状态：${state.take(80)}；可稍后继续查询")
            }
        }
    }

    private suspend fun generateH3(profile: ApiProfile, prompt: String, params: GenerationParams, refs: List<File>, destination: File,
                                   existingId: String, onSubmitted: suspend (String) -> Unit, onStatus: suspend (String) -> Unit,
                                   onDownloadReady: suspend (String) -> Unit, inputs: VideoInputs, onProgress: suspend (String) -> Unit) {
        val preset = H3VideoSpec.preset(profile, params)
        val upscale = preset == "flashvsr_upscale"
        suspend fun uploadMedia(file: File, kind: String): JSONObject {
            onStatus("Uploading")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(MediaInputFormats.mime(file).toMediaType())).build()
            return json(profile, Request.Builder().url(ApiUrls.h3Endpoint(profile.baseUrl, "uploads/$kind"))
                .header("Authorization", "Bearer ${profile.apiKey}").post(body).build()).getJSONObject("item")
        }
        fun ref(item: JSONObject) = item.getString("ref").also { require(it.isNotBlank() && it != "null") { "素材上传未返回有效 ref" } }
        val id = existingId.ifBlank {
            val images = if (refs.isEmpty()) emptyList() else {
                onStatus("Uploading")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    refs.forEach { addFormDataPart("files", it.name, it.asRequestBody("image/png".toMediaType())) }
                }.build()
                val result = json(profile, Request.Builder().url(ApiUrls.h3Endpoint(profile.baseUrl, "uploads"))
                    .header("Authorization", "Bearer ${profile.apiKey}").post(body).build()).getJSONArray("items")
                require(result.length() == refs.size) { "参考图上传数量不匹配，请检查服务商记录" }
                List(result.length()) { index -> result.getJSONObject(index).getString("ref").also {
                    require(it.isNotBlank() && it != "null") { "参考图上传未返回有效 ref" }
                } }
            }
            val body = JSONObject().put("preset", preset).put("prompt", if (upscale) "" else prompt)
            if (upscale) {
                val uploaded = uploadMedia(requireNotNull(inputs.source), "video")
                val seconds = uploaded.optDouble("seconds", 0.0)
                require(seconds < 12.0 || params.upscaleShortEdge <= 720) { "服务商识别原视频达到 12 秒，最高支持 720p" }
                body.put("video", ref(uploaded)).put("height", params.upscaleShortEdge)
            } else {
                val videos = inputs.videos.map { ref(uploadMedia(it, "video")) }
                val audios = inputs.audios.map { ref(uploadMedia(it, "audio")) }
                val (width, height) = H3VideoSpec.size(preset, params).split("x").map(String::toInt)
                body.put("width", width).put("height", height).put("seconds", H3VideoSpec.seconds(preset, params))
                    .put("images", JSONArray(images)).put("ref_videos", JSONArray(videos)).put("ref_audios", JSONArray(audios))
                if (!H3VideoSpec.isRecipe(preset)) body.put("sampler_steps", params.videoSteps).put("interpolate", params.videoInterpolate)
            }
            onStatus("Submitting")
            post(profile, "jobs", body).getJSONObject("job").get("id").toString().also {
                require(it.isNotBlank() && it != "null") { "服务商未返回有效视频任务 ID" }
                onSubmitted(it)
            }
        }
        val jobUrl = ApiUrls.h3Endpoint(profile.baseUrl, "jobs").newBuilder().addPathSegment(id).build()
        onStatus("Querying")
        while (true) {
            val job = json(profile, Request.Builder().url(jobUrl).header("Authorization", "Bearer ${profile.apiKey}").build()).getJSONObject("job")
            val state = job.getString("status")
            onProgress(job.optString("progress").replace(profile.apiKey, "[已隐藏]").take(600))
            onStatus(when (state) { "pending" -> "InQueue"; "running" -> "InProgress"; else -> state })
            when (state) {
                "done" -> {
                    require(job.optBoolean("has_video")) { "服务商标记完成但成片尚不可用，可稍后继续查询原任务" }
                    val url = jobUrl.newBuilder().addPathSegment("video").addQueryParameter("download", "1").build().toString()
                    onDownloadReady(url)
                    onStatus("Downloading")
                    download(profile, url, destination, id)
                    return
                }
                "failed", "cancelled" -> throw IOException("视频生成${if (state == "cancelled") "已取消" else "失败"}：" +
                    job.optString("error").ifBlank { "服务商未提供原因" }.replace(profile.apiKey, "[已隐藏]").take(600))
                "pending", "running" -> delay(minOf(pollMs, 5_000))
                else -> throw IOException("未知视频任务状态：${state.take(80)}；可稍后继续查询")
            }
        }
    }
}
