package com.lingan.imagestudio.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ApiProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "我的 API",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-image-2.5-sunburst",
    val timeoutSeconds: Int = 300,
    val advancedParameters: Boolean = true,
    val forceBase64: Boolean = false,
    val streamImages: Boolean = false,
    val apiMode: String = "images",
    val responsesModel: String = "",
    val allowHttpVideoDownload: Boolean = false,
) {
    val isVideo: Boolean get() = apiMode in listOf("video", "xai-video", "h3-video")

    fun toJson() = JSONObject().put("id", id).put("name", name).put("baseUrl", baseUrl)
        .put("apiKey", apiKey).put("model", model).put("timeout", timeoutSeconds)
        .put("advanced", advancedParameters).put("forceBase64", forceBase64).put("streamImages", streamImages).put("apiMode", apiMode).put("responsesModel", responsesModel)
        .put("allowHttpVideoDownload", allowHttpVideoDownload)

    companion object {
        fun fromJson(j: JSONObject) = ApiProfile(
            id = j.getString("id"), name = j.getString("name"), baseUrl = j.getString("baseUrl"),
            apiKey = j.optString("apiKey"), model = j.getString("model"),
            timeoutSeconds = j.optInt("timeout", 300),
            allowHttpVideoDownload = j.optBoolean("allowHttpVideoDownload", false),
            advancedParameters = j.optBoolean("advanced", true), forceBase64 = j.optBoolean("forceBase64"), streamImages = j.optBoolean("streamImages"), apiMode = j.optString("apiMode", "images"), responsesModel = j.optString("responsesModel"),
        )
    }
}

data class GenerationParams(
    val size: String = "auto",
    val quality: String = "auto",
    val format: String = "auto",
    val count: Int = 1,
    val transparent: Boolean = false,
    val videoDuration: Int = 8,
    val videoResolution: String = "720p",
    val videoAspectRatio: String = "16:9",
    val h3Preset: String = "",
    val videoSteps: Int = 20,
    val videoInterpolate: Boolean = true,
    val upscaleShortEdge: Int = 720,
) {
    fun validate() {
        require(count in 1..4) { "每次可以生成 1–4 张图片" }
        if (size != "auto") {
            require(size.matches(Regex("[0-9]+x[0-9]+"))) { "尺寸格式应为 宽x高" }
            val dimensions = size.split("x").mapNotNull { it.toIntOrNull() }
            require(dimensions.size == 2) { "尺寸格式应为 宽x高" }
            val (width, height) = dimensions
            require(width in 256..3840 && height in 256..3840 && width % 16 == 0 && height % 16 == 0) { "宽高需为 16 的倍数，范围 256–3840" }
            require(width.toLong() * height in 655360L..8294400L && width <= height * 3 && height <= width * 3) { "总像素需在 655360–8294400，宽高比在 1:3–3:1" }
        }
        require(quality in listOf("auto", "low", "medium", "high", "xhigh", "max")) { "不支持的质量" }
        require(format in listOf("auto", "png", "jpeg", "webp")) { "不支持的图片格式" }
        require(!transparent || format != "jpeg") { "透明背景需要 PNG 或 WebP 格式" }
    }

    fun toJson() = JSONObject().put("size", size).put("quality", quality).put("format", format)
        .put("count", count).put("transparent", transparent)
        .put("videoDuration", videoDuration).put("videoResolution", videoResolution).put("videoAspectRatio", videoAspectRatio)
        .put("h3Preset", h3Preset).put("videoSteps", videoSteps).put("videoInterpolate", videoInterpolate).put("upscaleShortEdge", upscaleShortEdge)

    companion object {
        fun fromJson(j: JSONObject) = GenerationParams(j.getString("size"), j.getString("quality"),
            j.getString("format"), j.getInt("count"), j.optBoolean("transparent"),
            j.optInt("videoDuration", 8), j.optString("videoResolution", "720p"), j.optString("videoAspectRatio", "16:9"),
            j.optString("h3Preset"), j.optInt("videoSteps", 20), j.optBoolean("videoInterpolate", true), j.optInt("upscaleShortEdge", 720))
    }
}

/** Paths are cache paths in a draft, and task-relative names once prepared. */
data class VideoInputPaths(
    val videos: List<String> = emptyList(), val audios: List<String> = emptyList(),
    val sourceVideo: String = "", val sourceDurationMs: Long = 0,
) {
    val names: List<String> get() = videos + audios + listOfNotNull(sourceVideo.takeIf(String::isNotBlank))
    fun files(resolve: (String) -> java.io.File = { java.io.File(it) }) = VideoInputs(
        videos.map(resolve), audios.map(resolve), sourceVideo.takeIf(String::isNotBlank)?.let(resolve), sourceDurationMs)
    fun toJson() = JSONObject().put("videos", JSONArray(videos)).put("audios", JSONArray(audios))
        .put("sourceVideo", sourceVideo).put("sourceDurationMs", sourceDurationMs)
    companion object {
        fun fromJson(json: JSONObject?) = if (json == null) VideoInputPaths() else VideoInputPaths(
            json.optJSONArray("videos").strings(), json.optJSONArray("audios").strings(), json.optString("sourceVideo"), json.optLong("sourceDurationMs"))
    }
}

enum class TaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }

data class ImageTask(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val profileName: String,
    val model: String,
    val params: GenerationParams,
    val advancedParameters: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val status: TaskStatus = TaskStatus.RUNNING,
    val outputs: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val mask: String = "",
    val error: String = "",
    val revisedPrompt: String = "",
    val actualSize: String = "",
    val actualQuality: String = "",
    val actualFormat: String = "",
    val actualBackground: String = "",
    val favorite: Boolean = false,
    val apiMode: String = "images",
    val responsesModel: String = "",
    val videoRequestId: String = "",
    val videoStatus: String = "",
    val videoStatusUpdatedAt: Long = 0,
    val videoInputs: VideoInputPaths = VideoInputPaths(),
    val videoProgress: String = "",
) {
    val isVideo: Boolean get() = apiMode in listOf("video", "xai-video", "h3-video")

    val assetNames: List<String> get() = (outputs + references + videoInputs.names + listOfNotNull(mask.takeIf(String::isNotBlank))).distinct()
    fun assetLimit(name: String): Long = when {
        isVideo && name in outputs -> 256L * 1024 * 1024
        name in videoInputs.audios -> 40L * 1024 * 1024
        name in videoInputs.names -> 100L * 1024 * 1024
        else -> 24L * 1024 * 1024
    }

    // Deliberately contains neither the API Key nor the provider URL.
    fun toJson() = JSONObject().put("id", id).put("prompt", prompt).put("profileName", profileName)
        .put("model", model).put("videoRequestId", videoRequestId).put("videoStatus", videoStatus).put("videoStatusUpdatedAt", videoStatusUpdatedAt).put("params", params.toJson()).put("createdAt", createdAt)
        .put("videoInputs", videoInputs.toJson()).put("videoProgress", videoProgress)
        .put("durationMs", durationMs).put("status", status.name).put("outputs", JSONArray(outputs))
        .put("references", JSONArray(references)).put("error", error).put("revisedPrompt", revisedPrompt)
        .put("actualQuality", actualQuality).put("actualFormat", actualFormat).put("actualBackground", actualBackground).put("apiMode", apiMode).put("responsesModel", responsesModel).put("mask", mask).put("actualSize", actualSize).put("favorite", favorite).put("advancedParameters", advancedParameters)

    companion object {
        fun fromJson(j: JSONObject) = ImageTask(
            videoInputs = VideoInputPaths.fromJson(j.optJSONObject("videoInputs")), videoProgress = j.optString("videoProgress"),
            videoRequestId = j.optString("videoRequestId"), videoStatus = j.optString("videoStatus"), videoStatusUpdatedAt = j.optLong("videoStatusUpdatedAt"),
            id = j.getString("id"), prompt = j.getString("prompt"), profileName = j.getString("profileName"),
            model = j.getString("model"), params = GenerationParams.fromJson(j.getJSONObject("params")),
            advancedParameters = j.optBoolean("advancedParameters", true),
            createdAt = j.getLong("createdAt"), durationMs = j.optLong("durationMs"),
            status = TaskStatus.valueOf(j.getString("status")), outputs = j.optJSONArray("outputs").strings(),
            references = j.optJSONArray("references").strings(), mask = j.optString("mask"), error = j.optString("error"),
            revisedPrompt = j.optString("revisedPrompt"), actualSize = j.optString("actualSize"),
            favorite = j.optBoolean("favorite"), apiMode = j.optString("apiMode", "images"), responsesModel = j.optString("responsesModel"), actualQuality = j.optString("actualQuality"), actualFormat = j.optString("actualFormat"), actualBackground = j.optString("actualBackground"),
        )
    }
}

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    List(length()) { getString(it) }

data class Settings(val profiles: List<ApiProfile> = listOf(ApiProfile()), val selectedId: String = "") {
    val selected: ApiProfile get() = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
    fun toJson() = JSONObject().put("profiles", JSONArray(profiles.map { it.toJson() })).put("selectedId", selectedId)
    companion object {
        fun fromJson(j: JSONObject): Settings {
            val list = j.getJSONArray("profiles")
            val profiles = List(list.length()) { ApiProfile.fromJson(list.getJSONObject(it)) }
            require(profiles.isNotEmpty()) { "至少需要一个 API 配置" }
            return Settings(profiles, j.optString("selectedId"))
        }
    }
}

fun splitBatchPrompts(text: String): List<String> = text.replace("\r\n", "\n").trim().split(Regex("\\n[ \t]*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
