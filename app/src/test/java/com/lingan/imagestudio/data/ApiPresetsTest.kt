package com.lingan.imagestudio.data

import org.junit.Assert.*
import org.junit.Test

class ApiPresetsTest {
    @Test fun `H3 preset persists video identity in settings and task history without leaking credentials`() {
        val preset = ApiPresets.all.first { it.id == "h3-video" }
        val profile = preset.applyTo(ApiProfile(apiKey = "another-provider-secret"))
        assertEquals("https://h3.coolhs.com/api/v1", profile.baseUrl)
        assertEquals("reference", profile.model); assertTrue(profile.isVideo); assertEquals("", profile.apiKey)
        assertEquals(preset, ApiPresets.matching(profile))
        assertEquals(profile, ApiProfile.fromJson(profile.toJson()))
        val task = ImageTask(prompt = "海景", profileName = profile.name, model = "dmad_30_portrait", params = GenerationParams(),
            apiMode = profile.apiMode, videoRequestId = "42")
        val restored = ImageTask.fromJson(task.toJson())
        assertTrue(restored.isVideo); assertEquals(task, restored)
        assertFalse(task.toJson().has("apiKey")); assertFalse(task.toJson().has("baseUrl"))
        assertEquals("RB-same-origin", preset.applyTo(profile.copy(apiKey = "RB-same-origin")).apiKey)
    }

    @Test fun `ChatGPT2API preset uses gateway key and survives custom deployment address`() {
        val preset = ApiPresets.all.first { it.id == "chatgpt2api-images" }
        val profile = preset.applyTo(ApiProfile(apiKey = "another-provider-secret", streamImages = true))
        assertEquals("chatgpt2api", profile.apiMode)
        assertEquals("gpt-image-2", profile.model)
        assertEquals("", profile.apiKey)
        assertTrue(profile.forceBase64); assertFalse(profile.streamImages)
        val deployed = profile.copy(baseUrl = "https://images.example.com/gateway/v1", apiKey = "gateway-key", model = "codex-gpt-image-2")
        ApiUrls.validate(deployed)
        assertEquals(deployed, ApiProfile.fromJson(deployed.toJson()))
        assertEquals(preset, ApiPresets.matching(deployed))
        assertNull(ApiPresets.matching(deployed.copy(apiMode = "images")))
    }
    @Test fun `Grok preset selects video protocol and clears another providers key`() {
        val preset = ApiPresets.all.first { it.id == "xai-video" }
        val profile = preset.applyTo(ApiProfile(apiKey = "other-provider-secret"))
        assertEquals("https://api.x.ai/v1", profile.baseUrl)
        assertEquals("xai-video", profile.apiMode); assertTrue(profile.isVideo)
        assertEquals("grok-imagine-video-1.5", profile.model); assertEquals("", profile.apiKey)
        assertEquals(preset, ApiPresets.matching(profile))
        assertEquals("xai-secret", preset.applyTo(profile.copy(apiKey = "xai-secret")).apiKey)
    }
    @Test fun `preset switches clear cross origin credentials and keep profile identity`() {
        val original = ApiProfile(apiKey = "openai-test-secret", timeoutSeconds = 600, streamImages = true, forceBase64 = true)
        val silicon = ApiPresets.all.first { it.id == "siliconflow-images" }.applyTo(original)
        assertEquals(original.id, silicon.id); assertEquals("", silicon.apiKey)
        assertEquals("Qwen/Qwen-Image", silicon.model); assertEquals("siliconflow", silicon.apiMode)
        assertEquals(600, silicon.timeoutSeconds); assertFalse(silicon.streamImages); assertFalse(silicon.forceBase64)
        val video = ApiPresets.all.first { it.id == "siliconflow-i2v" }.applyTo(silicon.copy(apiKey = "silicon-test-secret"))
        assertEquals("silicon-test-secret", video.apiKey); assertEquals("video", video.apiMode)
        assertTrue(video.model.contains("I2V")); assertEquals("", video.responsesModel)
        assertFalse(ApiPresets.sameOrigin("https://api.siliconflow.cn", "https://api.siliconflow.cn.attacker.example"))
        assertFalse(ApiPresets.sameOrigin("https://api.siliconflow.cn", "http://api.siliconflow.cn"))
    }
    @Test fun `built in presets produce valid editable profiles without credentials`() {
        assertEquals(ApiPresets.all.size, ApiPresets.all.map { it.id }.distinct().size)
        ApiPresets.all.forEach { preset ->
            val profile = preset.applyTo(ApiProfile(baseUrl = "https://custom.example/v1"))
            assertEquals("", profile.apiKey)
            ApiUrls.validate(profile.copy(apiKey = "test-key"))
            assertEquals(profile, ApiProfile.fromJson(profile.toJson()))
            assertEquals("custom-model", profile.copy(model = "custom-model").model)
        }
        assertNull(ApiPresets.matching(ApiProfile(baseUrl = "https://custom.example/v1")))
        assertEquals("openai-responses", ApiPresets.matching(ApiPresets.all.first { it.id == "openai-responses" }.applyTo(ApiProfile()))!!.id)
    }
}
