package com.lingan.imagestudio

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingan.imagestudio.data.*
import com.lingan.imagestudio.ui.StudioApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class H3CreationControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modesSwitchInCreationPageWithoutChangingSavedProfile() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secure = SecureSettings(app); val previous = secure.read()
        try {
            val profile = ApiProfile(name = "H3 UI test", baseUrl = "https://h3.coolhs.com/api/v1", apiKey = "RB-local-test", apiMode = "h3-video", model = "reference")
            secure.write(Settings(listOf(profile), profile.id))
            lateinit var vm: StudioViewModel
            compose.runOnUiThread { vm = StudioViewModel(app) }
            compose.setContent { MaterialTheme { StudioApp(vm) } }
            compose.waitUntil(10_000) { !vm.state.value.loading }
            compose.onNodeWithTag("create-scroll").performScrollToNode(hasTestTag("h3-mode"))
            compose.onNodeWithText("出片方式").performClick()
            compose.onNodeWithText("视频变清晰").performScrollTo().performClick()
            compose.onNodeWithTag("create-scroll").performScrollToNode(hasText("选择或替换原视频"))
            compose.onNodeWithText("选择或替换原视频").assertIsDisplayed()
            compose.onNodeWithText("描述你的灵感").assertDoesNotExist()
            compose.onNodeWithTag("create-scroll").performScrollToNode(hasTestTag("h3-mode"))
            compose.onNodeWithText("出片方式").performClick()
            compose.onNodeWithText("按描述生成 · 首尾过渡").performScrollTo().performClick()
            compose.onNodeWithTag("create-scroll").performScrollToNode(hasText("描述你的灵感"))
            compose.onNodeWithText("描述你的灵感").assertIsDisplayed()
            compose.onNodeWithText("添加参考视频（最多 3 条）").assertDoesNotExist()
            compose.onNodeWithTag("create-scroll").performScrollToNode(hasTestTag("h3-mode"))
            compose.onNodeWithText("出片方式").performClick()
            compose.onNodeWithText("按描述生成 · 贴合参考图").performScrollTo().performClick()
            compose.onNodeWithTag("create-scroll").performScrollToNode(hasText("添加参考视频（最多 3 条）"))
            compose.onNodeWithText("添加参考视频（最多 3 条）").assertIsDisplayed()
            compose.onNodeWithTag("create-scroll").performScrollToNode(hasText("添加参考音频（最多 3 段）"))
            compose.onNodeWithText("添加参考音频（最多 3 段）").assertIsDisplayed()
            compose.runOnIdle { assertEquals("reference", vm.state.value.params.h3Preset) }
            assertEquals(profile, secure.read().selected)
        } finally { secure.write(previous) }
    }
}
