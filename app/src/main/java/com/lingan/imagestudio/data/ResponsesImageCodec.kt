package com.lingan.imagestudio.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64

internal object ResponsesImageCodec {
    fun request(profile: ApiProfile, prompt: String, params: GenerationParams, references: List<File>, mask: File?): JSONObject {
        require(params.count == 1) { "Responses 每个请求仅生成一张，请通过任务队列逐张生成" }
        require(references.sumOf { it.length() } + (mask?.length() ?: 0) <= 40L * 1024 * 1024) { "Responses 参考图与遮罩合计不能超过 40MB，请减少图片" }
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt))
        references.forEach { file -> content.put(JSONObject().put("type", "input_image").put("image_url", dataUrl(file))) }
        val tool = JSONObject().put("type", "image_generation").put("model", profile.model).put("size", params.size)
        if (profile.advancedParameters) {
            tool.put("quality", params.quality)
            if (params.format != "auto") tool.put("output_format", params.format)
            else if (params.transparent) tool.put("output_format", "png")
            if (params.transparent) tool.put("background", "transparent")
        }
        if (mask != null) tool.put("input_image_mask", JSONObject().put("image_url", dataUrl(mask)))
        if (profile.streamImages) tool.put("partial_images", 2)
        return JSONObject().put("model", profile.responsesModel.trim())
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("tools", JSONArray().put(tool)).put("tool_choice", JSONObject().put("type", "image_generation"))
            .put("store", false).put("stream", profile.streamImages)
    }

    private fun dataUrl(file: File) = "data:image/png;base64," + Base64.getEncoder().encodeToString(file.readBytes())

    fun normalize(response: JSONObject): JSONObject {
        val status = response.optString("status", "completed")
        if (status != "completed") throw IOException("Responses 未完成：$status。" + (response.optJSONObject("error")?.optString("message") ?: "请检查服务商记录"))
        val output = response.optJSONArray("output") ?: throw IOException("Responses 响应缺少 output")
        val images = JSONArray()
        val result = JSONObject()
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("type") != "image_generation_call") continue
            require(item.optString("status", "completed") == "completed") { "图片工具调用未完成" }
            val base64 = item.optString("result")
            require(base64.isNotBlank() && base64 != "null" && base64.length <= 32 * 1024 * 1024 && images.length() < 4) { "图片工具返回空结果或图片过大" }
            images.put(JSONObject().put("b64_json", base64).put("revised_prompt", item.optString("revised_prompt")))
            listOf("size", "quality", "output_format", "background").forEach { field ->
                if (item.has(field) && !item.isNull(field)) result.put(field, item.get(field))
            }
        }
        if (images.length() == 0) throw IOException("Responses 未返回生图结果，请检查主模型是否支持 image_generation 工具")
        return result.put("data", images)
    }
}
