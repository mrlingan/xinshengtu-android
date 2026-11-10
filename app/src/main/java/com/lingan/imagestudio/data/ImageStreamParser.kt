package com.lingan.imagestudio.data

import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/** SSE frames are bounded independently; partial images are never treated as final outputs. */
internal object ImageStreamParser {
    fun parse(source: BufferedSource, key: String, preview: (ByteArray) -> Unit): ByteArray {
        val images = JSONArray()
        val result = JSONObject()
        val eventData = StringBuilder()
        var eventName = ""
        var totalBytes = 0L
        var previews = 0
        var eventCount = 0
        var done = false
        fun dispatch() {
            if (eventData.isEmpty()) { eventName = ""; return }
            require(++eventCount <= 1000) { "流式事件过多" }
            val text = eventData.toString().trim()
            eventData.clear()
            if (text == "[DONE]") { eventName = ""; done = true; return }
            val json = try { JSONObject(text) } catch (_: Exception) { throw IOException("流式接口返回了无效 JSON") }
            val type = json.optString("type").ifBlank { eventName }
            eventName = ""
            if (type == "error" || (type.endsWith(".failed") || type == "response.incomplete") || (json.has("error") && !json.isNull("error"))) {
                val message = json.optJSONObject("error")?.optString("message") ?: json.optJSONObject("response")?.optJSONObject("error")?.optString("message") ?: json.optString("message", "流式生成失败")
                throw IOException(message.replace(key, "[已隐藏]").take(500))
            }
            when (type) {
                "image_generation.partial_image", "image_edit.partial_image", "response.image_generation_call.partial_image" -> {
                    if (previews < 8) {
                        val base64 = if (type.startsWith("response.")) json.optString("partial_image_b64") else json.optString("b64_json")
                        require(base64.length <= 32 * 1024 * 1024) { "预览图超过大小限制" }
                        val bytes = try { Base64.getMimeDecoder().decode(base64) } catch (_: Exception) { throw IOException("预览图 Base64 无效") }
                        imageExtension(bytes)
                        previews++
                        preview(bytes)
                    }
                }
                "response.completed" -> {
                    val normalized = try { ResponsesImageCodec.normalize(json.getJSONObject("response")) }
                        catch (e: Exception) { throw IOException((e.message ?: "Responses 响应错误").replace(key, "[已隐藏]")) }
                    val data = normalized.getJSONArray("data")
                    for (index in 0 until data.length()) {
                        require(images.length() < 4) { "响应图片过多" }
                        images.put(data.getJSONObject(index))
                    }
                    listOf("size", "quality", "output_format", "background").forEach { field ->
                        if (normalized.has(field)) result.put(field, normalized.get(field))
                    }
                    done = true
                }
                "image_generation.completed", "image_edit.completed" -> {
                    val data = json.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            require(images.length() < 4) { "流式接口返回图片过多" }
                            images.put(data.getJSONObject(i))
                        }
                    } else {
                        require(images.length() < 4 && json.optString("b64_json").isNotBlank()) { "流式完成事件没有图片或返回过多" }
                        images.put(JSONObject().put("b64_json", json.getString("b64_json")).put("revised_prompt", json.optString("revised_prompt")))
                    }
                    listOf("size", "quality", "output_format", "background").forEach { field ->
                        if (json.has(field) && !json.isNull(field)) result.put(field, json.get(field))
                    }
                }
            }
        }
        while (!done && !source.exhausted()) {
            val line = try { source.readUtf8LineStrict(limit = 36L * 1024 * 1024) }
                catch (e: java.io.EOFException) {
                    if (source.buffer.size > 36L * 1024 * 1024) throw IOException("单个流式事件过大")
                    source.readUtf8()
                }
            totalBytes += line.toByteArray(Charsets.UTF_8).size + 1L
            require(totalBytes <= 192L * 1024 * 1024) { "流式响应超过大小限制" }
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                line.startsWith("data:") -> {
                    if (eventData.isNotEmpty()) eventData.append('\n')
                    eventData.append(line.substringAfter(':').removePrefix(" "))
                    require(eventData.length <= 36 * 1024 * 1024) { "单个流式事件过大" }
                }
            }
        }
        dispatch()
        if (images.length() == 0) throw IOException("流式连接结束但没有完成图片，任务可能仍在服务端处理，请检查记录后再重试")
        return result.put("data", images).toString().toByteArray(Charsets.UTF_8)
    }
}
