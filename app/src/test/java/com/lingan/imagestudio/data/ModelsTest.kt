package com.lingan.imagestudio.data

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun `HTTP video option defaults off and persists with encrypted profile payload`() {
        val profile = ApiProfile(allowHttpVideoDownload = true)
        assertEquals(profile, ApiProfile.fromJson(profile.toJson()))
        assertFalse(ApiProfile.fromJson(profile.toJson().apply { remove("allowHttpVideoDownload") }).allowHttpVideoDownload)
        val preset = ApiPresets.all.first { it.id == "xai-video" }
        assertFalse(preset.applyTo(profile).allowHttpVideoDownload)
        assertTrue(preset.applyTo(profile.copy(baseUrl = "https://api.x.ai/v1")).allowHttpVideoDownload)
    }
    @Test fun `Grok history retains video parameters and old parameters use defaults`() {
        val params = GenerationParams(videoDuration = 12, videoResolution = "1080p", videoAspectRatio = "9:16")
        val task = ImageTask(prompt = "sea", model = VideoApi.xaiModels.first(), profileName = "xAI", params = params,
            apiMode = "xai-video", videoRequestId = "job", videoStatus = "pending", videoStatusUpdatedAt = 123456789)
        assertEquals(task, ImageTask.fromJson(task.toJson()))
        assertEquals(0L, ImageTask.fromJson(task.toJson().apply { remove("videoStatusUpdatedAt") }).videoStatusUpdatedAt)
        assertTrue(task.isVideo)
        val old = GenerationParams().toJson().apply { remove("videoDuration"); remove("videoResolution"); remove("videoAspectRatio") }
        assertEquals(GenerationParams(), GenerationParams.fromJson(old))
    }
    @Test fun `collection JSON retains multi task membership`() {
        val folder = ArtworkCollection(name = "产品素材", taskIds = setOf("task-b", "task-a"))
        assertEquals(folder, ArtworkCollection.fromJson(folder.toJson()))
    }

    @Test fun `batch prompts preserve internal line breaks and split blank lines`() {
        assertEquals(listOf("first\nline", "second"), splitBatchPrompts(" first\nline\n \nsecond "))
        assertEquals(listOf("a", "b"), splitBatchPrompts("a\r\n\r\nb"))
        assertTrue(splitBatchPrompts(" ").isEmpty())
    }

    @Test fun `malformed dimensions cannot bypass image or H3 validation`() {
        listOf("1024xjunkx1024", "1024xx1024", "x1024x1024", "1024x1024x", "+1024x1024").forEach { size ->
            assertTrue("Expected rejection for $size", runCatching { GenerationParams(size = size).validate() }.isFailure)
            assertTrue("Expected H3 rejection for $size", runCatching { H3VideoSpec.validateParams("reference", GenerationParams(size = size)) }.isFailure)
        }
    }

    @Test fun `custom sizes accept valid dimensions and reject unsafe aspect ratio`() {
        GenerationParams(size = "3840x2160").validate()
        GenerationParams(size = "2048x2048").validate()
        assertTrue(runCatching { GenerationParams(size = "1025x1024").validate() }.isFailure)
        assertTrue(runCatching { GenerationParams(size = "3840x256").validate() }.isFailure)
        assertTrue(runCatching { GenerationParams(size = "3840x3840").validate() }.isFailure)
    }

    @Test fun `base URL root v1 and arbitrary prefixes are normalized`() {
        assertEquals("https://api.example.com/v1/images/generations", ApiUrls.endpoint(" https://api.example.com/ ", "images/generations").toString())
        assertEquals("https://api.example.com/v1/images/edits", ApiUrls.endpoint("https://api.example.com/v1/", "images/edits").toString())
        assertEquals("https://api.example.com/gateway/custom/images/edits", ApiUrls.endpoint("https://api.example.com/gateway/custom", "images/edits").toString())
        assertEquals("http://10.0.2.2:8080/v1/models", ApiUrls.endpoint("http://10.0.2.2:8080", "models").toString())
    }

    @Test fun `credentialed query fragmented and full endpoint URLs are rejected`() {
        listOf("https://user:pass@example.com", "https://example.com?key=secret", "https://example.com#fragment", "https://example.com/v1/images/generations", "ftp://example.com").forEach { value ->
            assertTrue("Expected rejection for $value", runCatching { ApiUrls.base(value) }.isFailure)
        }
    }

    @Test fun `invalid output parameters are rejected`() {
        assertTrue(runCatching { GenerationParams(format = "jpeg", transparent = true).validate() }.isFailure)
        assertTrue(runCatching { GenerationParams(count = 5).validate() }.isFailure)
        assertTrue(runCatching { GenerationParams(size = "1x1").validate() }.isFailure)
    }

    @Test fun `history roundtrip preserves images references and requested parameters without secrets`() {
        val task = ImageTask(prompt = "test", model = "custom", profileName = "provider",
            params = GenerationParams(count = 3), outputs = listOf("output-1.png"), references = listOf("input-1.png"),
            status = TaskStatus.SUCCEEDED, favorite = true, actualSize = "1536x1024")
        val json = task.toJson()
        assertFalse(json.has("apiKey")); assertFalse(json.has("baseUrl"))
        assertEquals(task, ImageTask.fromJson(json))
    }

    @Test fun `multiple profile configuration roundtrip retains active choice`() {
        val first = ApiProfile(apiKey = "one")
        val second = ApiProfile(apiKey = "two", model = "other", advancedParameters = false)
        val settings = Settings(listOf(first, second), second.id)
        val restored = Settings.fromJson(settings.toJson())
        assertEquals(settings, restored)
        assertEquals("two", restored.selected.apiKey)
        assertEquals(first, Settings(listOf(first), "invalid-id").selected)
    }
}
