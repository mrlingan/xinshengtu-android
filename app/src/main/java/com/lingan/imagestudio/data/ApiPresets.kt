package com.lingan.imagestudio.data

/** Built-in connection templates contain no credentials and remain editable after applying. */
data class ApiPreset(
    val id: String, val title: String, val baseUrl: String, val apiMode: String,
    val model: String, val description: String, val documentation: String,
    val responsesModel: String = "",
    val forceBase64: Boolean = false,
) {
    fun applyTo(current: ApiProfile): ApiProfile = current.copy(
        name = title, baseUrl = baseUrl, apiMode = apiMode, model = model,
        apiKey = if (ApiPresets.sameOrigin(current.baseUrl, baseUrl)) current.apiKey else "",
        responsesModel = responsesModel, advancedParameters = true,
        streamImages = false, forceBase64 = forceBase64,
        allowHttpVideoDownload = current.allowHttpVideoDownload && ApiPresets.sameOrigin(current.baseUrl, baseUrl),
    )
}

object ApiPresets {
    val all = listOf(
        ApiPreset("chatgpt2api-images", "ChatGPT2API · 自建生图", "http://10.0.2.2:3000/v1", "chatgpt2api", "gpt-image-2",
            "连接自己部署的 ChatGPT2API。默认地址仅用于安卓模拟器访问电脑；真机请改为服务器域名或电脑局域网 IP。API Key 填服务端 auth-key。", "https://github.com/basketikun/chatgpt2api", forceBase64 = true),
        ApiPreset("openai-images", "OpenAI · 图片", "https://api.openai.com/v1", "images", "gpt-image-2.5-sunburst",
            "官方 Images API，支持文字、参考图与遮罩编辑。模型可手动修改。", "https://developers.openai.com/api/docs/guides/image-generation"),
        ApiPreset("openai-responses", "OpenAI · Responses 生图", "https://api.openai.com/v1", "responses", "gpt-image-2.5-sunburst",
            "通过主模型调用生图工具，主模型与生图模型分别计费，可修改为账号支持的主模型 ID。", "https://developers.openai.com/api/docs/guides/tools-image-generation", responsesModel = "gpt-6-astra"),
        ApiPreset("siliconflow-images", "硅基流动 · 图片", "https://api.siliconflow.cn/v1", "siliconflow", "Qwen/Qwen-Image",
            "原生文生图协议。画面质量和格式选择自动，不支持本应用的参考图与遮罩编辑。", "https://api-docs.siliconflow.cn/docs/api/images-generations-post"),
        ApiPreset("siliconflow-t2v", "硅基流动 · 文生视频", "https://api.siliconflow.cn/v1", "video", "Wan-AI/Wan2.2-T2V-A14B",
            "输入视频提示词，无需参考图。提交后自动查询并下载 MP4。", "https://api-docs.siliconflow.cn/docs/api/video-submit-post"),
        ApiPreset("siliconflow-i2v", "硅基流动 · 图生视频", "https://api.siliconflow.cn/v1", "video", "Wan-AI/Wan2.2-I2V-A14B",
            "需要从相册添加一张首帧图，生成完成后自动下载 MP4。", "https://api-docs.siliconflow.cn/docs/api/video-submit-post"),
        ApiPreset("xai-video", "xAI · Grok 视频（API Key）", "https://api.x.ai/v1", "xai-video", "grok-imagine-video-1.5",
            "使用 xAI API Key，支持文生视频或添加一张首帧图。视频使用 Grok Imagine 模型；如服务商提供其他模型 ID，可手动填写。", "https://docs.x.ai/developers/model-capabilities/video/generation"),
        ApiPreset("h3-video", "瞬映 · MiniMax H3 视频", "https://h3.coolhs.com/api/v1", "h3-video", "reference",
            "使用瞬映卡密，支持文字、多参考图、首尾帧及 15/20/30 秒横竖屏模板。完成后下载 MP4，可继续查询原任务。", "https://h3.coolhs.com/docs#start"),
        ApiPreset("302-images", "302.AI · 图片", "https://api.302.ai/v1", "images", "gpt-image-2",
            "OpenAI 兼容图片接口。请使用 302.AI 的 Key，模型权限以该服务商为准。", "https://doc.302.ai/api-288853804"),
    )
    fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = ApiUrls.base(first); val b = ApiUrls.base(second)
        a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }.getOrDefault(false)

    fun matching(profile: ApiProfile): ApiPreset? = all.firstOrNull {
        (profile.apiMode == "chatgpt2api" || profile.baseUrl.trim().trimEnd('/') == it.baseUrl) && profile.apiMode == it.apiMode &&
            (it.apiMode != "video" || profile.model == it.model)
    }
}
