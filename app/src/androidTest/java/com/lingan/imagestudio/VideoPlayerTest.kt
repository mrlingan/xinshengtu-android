package com.lingan.imagestudio

import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingan.imagestudio.ui.VideoPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoPlayerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun replacingSourceLoadsTheNewVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun copyAsset(name: String) = File(instrumentation.targetContext.cacheDir, "player-${UUID.randomUUID()}.mp4").also { file ->
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val first = copyAsset("video-test.mp4")
        val second = copyAsset("video-test-long.mp4")
        val source = mutableStateOf(first)
        var root: View? = null
        fun video(view: View?): VideoView? = when (view) {
            is VideoView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { video(view.getChildAt(it)) }
            else -> null
        }
        try {
            compose.setContent { root = LocalView.current.rootView; MaterialTheme { VideoPlayer(source.value) } }
            compose.waitUntil(10_000) { compose.runOnUiThread { video(root)?.duration ?: 0 } in 900..1100 }
            compose.runOnIdle { source.value = second }
            compose.waitUntil(10_000) { compose.runOnUiThread { video(root)?.duration ?: 0 } in 1900..2100 }
            compose.runOnIdle { assertEquals(2000, requireNotNull(video(root)).duration) }
        } finally { first.delete(); second.delete() }
    }
}
