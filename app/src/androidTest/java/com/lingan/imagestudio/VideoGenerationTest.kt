package com.lingan.imagestudio

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingan.imagestudio.data.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VideoGenerationTest {
    @Test fun videoCompletesInBackgroundAndResumeDoesNotSubmitAgain() {
        checkBackgroundVideo("video")
    }
    @Test fun grokVideoCompletesBacksUpAndResumesWithoutResubmitting() {
        checkBackgroundVideo("xai-video")
    }
    private fun checkBackgroundVideo(mode: String) {
        val xai = mode == "xai-video"
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = LocalStore(app); val before = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("video-test.mp4").use { it.readBytes() }
        val profile = ApiProfile(name = "test video", baseUrl = server.url("/v1").toString(), apiKey = "test-key", model = if (xai) VideoApi.xaiModels.first() else VideoApi.models.first(), apiMode = mode)
        val submitPath = if (xai) "/v1/videos/generations" else "/v1/video/submit"
        val statusPath = if (xai) "/v1/videos/video-job" else "/v1/video/status"
        val done = if (xai) """{"status":"done","video":{"url":"/clip.mp4","respect_moderation":true}}""" else """{"status":"Succeed","results":{"videos":[{"url":"${server.url("/clip.mp4")}"}]}}"""
        val params = GenerationParams(videoDuration = 12, videoResolution = "1080p", videoAspectRatio = "9:16")
        val backup = File(app.cacheDir, "video-backup.zip")
        try {
            server.enqueue(MockResponse().setBody(if (xai) """{"request_id":"video-job"}""" else """{"requestId":"video-job"}"""))
            server.enqueue(MockResponse().setBody(done).setBodyDelay(1, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { GenerationService.start(it, profile, "blue sea", params, emptyList()) }
                assertEquals(submitPath, server.takeRequest(5, TimeUnit.SECONDS)!!.path)
                scenario.onActivity { it.moveTaskToBack(true) }
                waitForCompletion()
                val task = GenerationService.status.value.tasks.single()
                assertEquals(TaskStatus.SUCCEEDED, task.status); assertEquals("video-job", task.videoRequestId)
                assertEquals(mode, task.apiMode); assertEquals(params, task.params)
                val output = store.file(task, task.outputs.single()); assertArrayEquals(bytes, output.readBytes()); assertEquals("video/mp4", output.imageMime())
                val poll = server.takeRequest(); assertEquals(statusPath, poll.path); assertEquals(if (xai) "GET" else "POST", poll.method)
                assertNull(server.takeRequest().getHeader("Authorization"))
                BackupStore(app, store).export(Uri.fromFile(backup), listOf(task))
                java.util.zip.ZipFile(backup).use { zip -> assertFalse(zip.entries().asSequence().any { it.name.endsWith("video-download.json") }) }
                assertEquals(1, BackupStore(app, store).restore(Uri.fromFile(backup)))
                assertTrue(store.load().first.any { it.id != task.id && it.apiMode == mode && it.params == params })
                assertEquals(if (xai) "/clip.mp4" else server.url("/clip.mp4").toString(), LocalStore(app).videoDownloadUrl(task))
                assertEquals("", store.videoDownloadUrl(task.copy(videoRequestId = "different-job")))
                val interrupted = task.copy(status = TaskStatus.INTERRUPTED, outputs = emptyList()).also { store.save(it) }
                server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
                scenario.onActivity { GenerationService.resumeVideo(it, profile, interrupted) }
                waitForCompletion()
                assertEquals(TaskStatus.SUCCEEDED, GenerationService.status.value.tasks.single().status)
                val download = server.takeRequest()
                assertEquals("/clip.mp4", download.path); assertNull(download.getHeader("Authorization")); assertEquals(4, server.requestCount)
            }
        } finally {
            GenerationService.cancel(); backup.delete()
            store.load().first.filter { it.id !in before }.forEach(store::delete); server.shutdown()
        }
    }
    private fun waitForCompletion() {
        val end = System.currentTimeMillis() + 20000
        while (GenerationService.status.value.running && System.currentTimeMillis() < end) Thread.sleep(50)
        assertFalse(GenerationService.status.value.running)
    }
}
