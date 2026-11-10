package com.lingan.imagestudio.data

/** Native 瞬映 /api/v1 presets; SDK aliases such as sora-2 are not model identities. */
object H3VideoSpec {
    val presets = listOf("reference", "tail_frame", "easy_15", "dmad_20", "dmad_30",
        "easy_15_portrait", "dmad_20_portrait", "dmad_30_portrait", "flashvsr_upscale")
    val sizes = listOf("832x480", "1024x576", "1280x736", "1344x768",
        "480x832", "576x1024", "736x1280", "768x1344", "512x512", "736x736", "960x960", "1440x1440")
    fun isRecipe(preset: String) = preset in presets && preset !in listOf("reference", "tail_frame", "flashvsr_upscale")
    fun preset(profile: ApiProfile, params: GenerationParams) = params.h3Preset.ifBlank { profile.model }
    fun isUpscale(profile: ApiProfile, params: GenerationParams) = profile.apiMode == "h3-video" && preset(profile, params) == "flashvsr_upscale"
    fun maxImages(preset: String) = when { preset == "flashvsr_upscale" -> 0; preset == "tail_frame" -> 2; isRecipe(preset) -> 9; else -> 8 }
    fun durations(preset: String): List<Int> = when (preset.removeSuffix("_portrait")) {
        "easy_15" -> listOf(15)
        "dmad_20" -> listOf(20)
        "dmad_30" -> listOf(30)
        else -> listOf(4, 6, 8)
    }
    fun size(preset: String, params: GenerationParams): String {
        if (!isRecipe(preset)) return if (params.size == "auto") "1344x768" else params.size
        val landscape = if (preset.startsWith("easy_15")) "1344x768" else "1184x672"
        return if (preset.endsWith("_portrait")) landscape.split("x").reversed().joinToString("x") else landscape
    }
    fun seconds(preset: String, params: GenerationParams) = if (isRecipe(preset)) durations(preset).first() else params.videoDuration
    fun validateParams(preset: String, params: GenerationParams) {
        require(preset in presets) { "请选择瞬映支持的出片方式" }
        require(params.count == 1) { "视频每次生成一个" }
        if (preset == "flashvsr_upscale") {
            require(params.upscaleShortEdge in 480..1080 && (params.upscaleShortEdge == 1080 || params.upscaleShortEdge % 16 == 0)) { "超分短边清晰度应为 480–1080p" }
        } else if (!isRecipe(preset)) {
            require(params.videoDuration in durations(preset)) { "瞬映常规视频时长请选择 4、6 或 8 秒" }
            val requestedSize = size(preset, params)
            require(requestedSize.matches(Regex("[0-9]+x[0-9]+"))) { "尺寸格式应为 宽x高" }
            val dimensions = requestedSize.split("x").mapNotNull(String::toIntOrNull)
            require(dimensions.size == 2 && dimensions.all { it in 256..1440 && it % 32 == 0 }) { "瞬映宽高需为 32 的倍数，范围 256–1440" }
            require(params.videoSteps in listOf(8, 20)) { "采样步数请选择 8 或 20" }
        }
    }
    fun validate(profile: ApiProfile, params: GenerationParams, refs: List<java.io.File>, inputs: VideoInputs = VideoInputs()) {
        val preset = preset(profile, params)
        validateParams(preset, params)
        require(refs.size <= maxImages(preset)) { "参考图超出当前出片方式的上限" }
        if (preset == "tail_frame") require(refs.size == 2) { "首尾帧模式需要按顺序添加两张图片" }
        require(inputs.videos.size <= 3 && inputs.audios.size <= 3) { "参考视频与参考音频各最多 3 个" }
        if (preset in listOf("tail_frame", "flashvsr_upscale")) {
            require(inputs.videos.isEmpty() && inputs.audios.isEmpty()) { "当前模式不支持参考视频和音频，请先移除素材" }
        }
        if (preset == "flashvsr_upscale") {
            require(inputs.source != null) { "请添加需要变清晰的原视频" }
            require(inputs.sourceDurationMs in 1..Long.MAX_VALUE) { "无法读取原视频时长，请重新选择" }
            require(inputs.sourceDurationMs < 12_000 || params.upscaleShortEdge <= 720) { "12 秒及以上的原视频最高支持 720p" }
        } else require(inputs.source == null) { "按描述生成不接收超分原视频，请先移除原视频" }
        require((inputs.videos + listOfNotNull(inputs.source)).all {
            it.extension.lowercase() in MediaInputFormats.video && it.isFile && it.length() in 1..100L * 1024 * 1024
        }) { "视频素材缺失、格式不支持或超过 100MB" }
        require(inputs.audios.all {
            it.extension.lowercase() in MediaInputFormats.audio && it.isFile && it.length() in 1..40L * 1024 * 1024
        }) { "音频素材缺失、格式不支持或超过 40MB" }
    }
}

data class VideoInputs(val videos: List<java.io.File> = emptyList(), val audios: List<java.io.File> = emptyList(),
                       val source: java.io.File? = null, val sourceDurationMs: Long = 0) {
    val allFiles get() = videos + audios + listOfNotNull(source)
}
