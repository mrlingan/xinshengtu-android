package com.lingan.imagestudio

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingan.imagestudio.data.*
import com.lingan.imagestudio.ui.StudioApp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class StudioFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fetchSearchAndSelectModelInSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secure = SecureSettings(app)
        val previous = secure.read()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-image-test"},{"id":"text-model"}]}"""))
            val profile = ApiProfile(name = "模型测试接口", baseUrl = server.url("/v1").toString(), apiKey = "test-model-key", model = "")
            secure.write(Settings(listOf(profile), profile.id))
            lateinit var vm: StudioViewModel
            compose.runOnUiThread { vm = StudioViewModel(app) }
            compose.setContent { MaterialTheme { StudioApp(vm) } }
            compose.waitUntil(10000) { !vm.state.value.loading }
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("模型测试接口").performClick()
            compose.onNodeWithText("获取模型列表").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("选择模型").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("text-model").assertDoesNotExist()
            compose.onNodeWithText("gpt-image-test").assertExists()
            compose.onNodeWithText("搜索模型").performTextInput("image")
            compose.onNodeWithText("gpt-image-test").performClick()
            compose.onNodeWithText("保存并使用").performScrollTo().performClick()
            compose.waitUntil(10000) { vm.state.value.settings.selected.model == "gpt-image-test" }
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer test-model-key", request.getHeader("Authorization"))
        } finally { secure.write(previous); server.shutdown() }
    }

    @Test fun retryCreatesNewTaskUsingOriginalPrompt() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secure = SecureSettings(app)
        val previous = secure.read()
        val store = LocalStore(app)
        val existing = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}]}"""))
            val profile = ApiProfile(baseUrl = server.url("/v1").toString(), apiKey = "test-retry", model = "test-image")
            secure.write(Settings(listOf(profile), profile.id))
            val failed = ImageTask(prompt = "retry this prompt", model = "old-model", profileName = "old", params = GenerationParams(size = "2048x2048"), status = TaskStatus.FAILED)
            store.save(failed)
            lateinit var vm: StudioViewModel
            compose.runOnUiThread { vm = StudioViewModel(app) }
            compose.setContent { MaterialTheme { StudioApp(vm) } }
            compose.waitUntil(10000) { !vm.state.value.loading }
            compose.runOnUiThread { vm.retry(failed) {} }
            compose.waitUntil(15000) { vm.state.value.tasks.any { it.id != failed.id && it.id !in existing && it.status == TaskStatus.SUCCEEDED } }
            val success = vm.state.value.tasks.first { it.status == TaskStatus.SUCCEEDED && it.id !in existing }
            assertEquals(failed.prompt, success.prompt)
            assertEquals(failed.params, success.params)
            assertEquals(profile.model, success.model)
            assertEquals(TaskStatus.FAILED, store.load().first.first { it.id == failed.id }.status)
            assertEquals(1, server.requestCount)
        } finally {
            store.load().first.filter { it.id !in existing }.forEach(store::delete)
            secure.write(previous); server.shutdown()
        }
    }

    @Test fun selectProviderPresetAndCustomizeSavedConnection() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secure = SecureSettings(app); val previous = secure.read()
        try {
            val profile = ApiProfile(name = "预设切换测试", apiKey = "old-provider-key")
            secure.write(Settings(listOf(profile), profile.id))
            lateinit var vm: StudioViewModel
            compose.runOnUiThread { vm = StudioViewModel(app) }
            compose.setContent { MaterialTheme { StudioApp(vm) } }
            compose.waitUntil(10000) { !vm.state.value.loading }
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("预设切换测试").performClick()
            compose.onNodeWithText("服务商预设").performClick()
            compose.onNodeWithText("硅基流动 · 图片").performScrollTo().performClick()
            val key = compose.onNode(hasSetTextAction() and hasText("API Key"))
            key.performScrollTo().performTextInput("new-silicon-key")
            compose.onNodeWithText("保存并使用").performScrollTo().performClick()
            compose.waitUntil(10000) { vm.state.value.settings.selected.apiMode == "siliconflow" }
            val saved = secure.read().selected
            assertEquals(profile.id, saved.id); assertEquals("new-silicon-key", saved.apiKey)
            assertEquals("https://api.siliconflow.cn/v1", saved.baseUrl); assertEquals("Qwen/Qwen-Image", saved.model)
            compose.onNodeWithText("硅基流动 · 图片").performClick()
            compose.onNodeWithText("服务商预设").performClick()
            compose.onNodeWithText("自定义接口").performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and hasText("API 基础地址")).performScrollTo().performTextClearance()
            compose.onNode(hasSetTextAction() and hasText("API 基础地址")).performTextInput("https://custom.example/v1")
            compose.onNodeWithText("保存并使用").performScrollTo().performClick()
            compose.waitUntil(10000) { vm.state.value.settings.selected.baseUrl == "https://custom.example/v1" }
            assertEquals("https://custom.example/v1", secure.read().selected.baseUrl)
        } finally { secure.write(previous) }
    }

    @Test fun configureVideoModeAndShowVideoParameters() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secure = SecureSettings(app); val previous = secure.read()
        try {
            val profile = ApiProfile(name = "视频接口切换测试", apiKey = "test-key", model = "gpt-image-test")
            secure.write(Settings(listOf(profile), profile.id))
            lateinit var vm: StudioViewModel
            compose.runOnUiThread { vm = StudioViewModel(app) }
            compose.setContent { MaterialTheme { StudioApp(vm) } }
            compose.waitUntil(10000) { !vm.state.value.loading }
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("视频接口切换测试").performClick()
            compose.onNodeWithText("Images API").performScrollTo().performClick()
            compose.onNodeWithText("硅基流动视频").performClick()
            compose.onNodeWithText("保存并使用").performScrollTo().performClick()
            compose.waitUntil(10000) { vm.state.value.settings.selected.apiMode == "video" }
            assertEquals(VideoApi.models.first(), vm.state.value.settings.selected.model)
            compose.onNodeWithText("创作").performClick()
            compose.onNodeWithTag("create-scroll").performScrollToNode(hasText("视频分辨率"))
            compose.onNodeWithText("视频分辨率").assertExists()
            compose.onNodeWithText("生成视频").assertExists()
        } finally { secure.write(previous) }
    }

    @Test fun configureResponsesModeAndPrimaryModel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secure = SecureSettings(app)
        val previous = secure.read()
        try {
            val profile = ApiProfile(name = "接口切换测试", apiKey = "test-key", model = "gpt-image-test")
            secure.write(Settings(listOf(profile), profile.id))
            lateinit var vm: StudioViewModel
            compose.runOnUiThread { vm = StudioViewModel(app) }
            compose.setContent { MaterialTheme { StudioApp(vm) } }
            compose.waitUntil(10000) { !vm.state.value.loading }
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("接口切换测试").performClick()
            compose.onNodeWithText("Images API").performScrollTo().performClick()
            compose.onNodeWithText("Responses API").performClick()
            compose.onNodeWithText("Responses 主模型 ID").performTextInput("primary-model")
            compose.onNodeWithText("保存并使用").performScrollTo().performClick()
            compose.waitUntil(10000) { vm.state.value.settings.selected.apiMode == "responses" }
            assertEquals("primary-model", secure.read().selected.responsesModel)
            assertEquals("gpt-image-test", secure.read().selected.model)
        } finally { secure.write(previous) }
    }

    @Test fun createCollectionAssignArtworkAndFilterLibrary() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val folders = CollectionStore(app)
        val previous = folders.read()
        val store = LocalStore(app)
        val existing = store.load().first.map { it.id }.toSet()
        try {
            folders.update { emptyList() }
            val task = ImageTask(prompt = "归档作品", profileName = "test", model = "test", params = GenerationParams(), status = TaskStatus.FAILED)
            store.save(task)
            store.save(task.copy(id = java.util.UUID.randomUUID().toString(), prompt = "未归档作品"))
            lateinit var vm: StudioViewModel
            compose.runOnUiThread { vm = StudioViewModel(app) }
            compose.setContent { MaterialTheme { StudioApp(vm) } }
            compose.waitUntil(10000) { !vm.state.value.loading }
            compose.onNodeWithText("作品库").performClick()
            compose.onNodeWithText("管理").performClick()
            compose.onNodeWithText("新收藏夹名称").performTextInput("测试素材")
            compose.onNodeWithText("新建").performClick()
            compose.waitUntil(10000) { vm.state.value.collections.any { it.name == "测试素材" } }
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithText("归档作品").performClick()
            compose.onNodeWithTag("detail-scroll").performScrollToNode(hasText("管理作品收藏夹"))
            compose.onNodeWithText("管理作品收藏夹").performClick()
            compose.onNode(isToggleable() and hasAnySibling(hasText("测试素材"))).performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10000) { vm.state.value.collections.any { task.id in it.taskIds } }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("全部作品").performClick()
            compose.onNodeWithText("测试素材 (1)").performClick()
            compose.onNodeWithText("归档作品").assertExists()
            compose.onNodeWithText("未归档作品").assertDoesNotExist()
        } finally {
            store.load().first.filter { it.id !in existing }.forEach(store::delete)
            folders.update { previous }
        }
    }

    @Test fun generateBrowseAndReuseWithEncryptedCustomConfiguration() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secure = SecureSettings(app)
        val previous = secure.read()
        val store = LocalStore(app)
        val existingIds = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        var vm: StudioViewModel? = null
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(100, 140, 120)) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}],"size":"1024x1024"}"""))
            val profile = ApiProfile(name = "模拟接口", baseUrl = server.url("/v1").toString(), apiKey = "instrumented-test-key", model = "test-image-model")
            secure.write(Settings(listOf(profile), profile.id))
            val encrypted = app.getSharedPreferences("secure_settings", Context.MODE_PRIVATE).getString("payload", "")!!
            assertFalse(encrypted.contains("instrumented-test-key"))
            assertEquals(profile, secure.read().selected)
            compose.runOnUiThread { vm = StudioViewModel(app) }
            compose.setContent { MaterialTheme { StudioApp(requireNotNull(vm)) } }
            compose.waitUntil(10_000) { vm?.state?.value?.loading == false }
            compose.onNodeWithText("让想象，有画面。").assertIsDisplayed()
            compose.onNodeWithText("灵感模板").performClick()
            compose.onNodeWithText("电影感").performClick()
            compose.onNodeWithTag("create-scroll").performScrollToNode(hasText("生成图片"))
            compose.onNodeWithText("生成图片").performClick()
            compose.waitUntil(15_000) { vm?.state?.value?.tasks?.any { it.id !in existingIds && it.status == TaskStatus.SUCCEEDED } == true }
            val task = vm!!.state.value.tasks.first { it.id !in existingIds }
            assertEquals("test-image-model", task.model)
            assertTrue(store.file(task, task.outputs.single()).isFile)
            val request = server.takeRequest()
            assertEquals("Bearer instrumented-test-key", request.getHeader("Authorization"))
            assertEquals("/v1/images/generations", request.path)
            compose.onNodeWithText("作品库").performClick()
            compose.onNodeWithText("灵感的留存").assertIsDisplayed()
            compose.onNodeWithText(task.prompt).performClick()
            compose.onNodeWithText("保存图片").assertIsDisplayed()
            compose.onNodeWithText("分享", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("detail-scroll").performScrollToNode(hasText("复用提示词与参数"))
            compose.onNodeWithText("复用提示词与参数").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("让想象，有画面。").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(task.prompt, vm!!.state.value.prompt)
            assertEquals(task.params, vm!!.state.value.params)
            assertEquals(1, server.requestCount)
        } finally {
            store.load().first.filter { it.id !in existingIds }.forEach { store.delete(it) }
            secure.write(previous)
            server.shutdown()
        }
    }
}
