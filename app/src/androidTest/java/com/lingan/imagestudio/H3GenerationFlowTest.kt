package com.lingan.imagestudio

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingan.imagestudio.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class H3GenerationFlowTest {
    @Test fun multimediaInputsSurviveGenerationBackupAndReuse() = checkFlow(false)
    @Test fun upscalePresetSurvivesBackupAndResumesWithOriginalConfiguration() = checkFlow(true)

    private fun checkFlow(upscale: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = LocalStore(app); val before = store.load().first.map { it.id }.toSet()
        val server = MockWebServer().apply { start() }
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("video-test.mp4").use { it.readBytes() }
        val video = File.createTempFile("h3-source", ".mp4", app.cacheDir).apply { writeBytes(bytes) }
        val audio = File.createTempFile("h3-audio", ".wav", app.cacheDir).apply {
            val pcm = ByteArray(16_000)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1.toShort()).putShort(1.toShort()).putInt(8000).putInt(16000).putShort(2.toShort()).putShort(16.toShort())
                .put("data".toByteArray()).putInt(pcm.size).array()
            outputStream().use { it.write(header); it.write(pcm) }
        }
        val imports = mutableListOf<String>(); val backup = File.createTempFile("h3-backup", ".zip", app.cacheDir)
        try {
            val importedVideo = MediaInputStore(app).import(Uri.fromFile(video), false).also { imports.add(it.path) }
            val importedAudio = MediaInputStore(app).import(Uri.fromFile(audio), true).also { imports.add(it.path) }
            val inputs = if (upscale) VideoInputPaths(sourceVideo = importedVideo.path, sourceDurationMs = importedVideo.durationMs)
                else VideoInputPaths(videos = listOf(importedVideo.path), audios = listOf(importedAudio.path))
            val params = GenerationParams(size = "832x480", h3Preset = if (upscale) "flashvsr_upscale" else "reference", videoSteps = 8, videoInterpolate = false)
            val profile = ApiProfile(name = "H3 flow", baseUrl = server.url("/api/v1").toString(), apiKey = "RB-local-test", model = "reference", apiMode = "h3-video")
            server.enqueue(MockResponse().setBody("""{"item":{"ref":"video.mp4","seconds":5}}"""))
            if (!upscale) server.enqueue(MockResponse().setBody("""{"item":{"ref":"audio.wav","seconds":1}}"""))
            server.enqueue(MockResponse().setBody("""{"job":{"id":72,"status":"pending"}}"""))
            server.enqueue(MockResponse().setBody("""{"job":{"id":72,"status":"done","has_video":true,"progress":"已完成"}}"""))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { GenerationService.start(it, profile, if (upscale) "视频变清晰" else "海边", params, emptyList(), videoInputs = inputs) }
                runBlocking { withTimeout(20_000) { GenerationService.status.first { !it.running } } }
                val task = GenerationService.status.value.tasks.single()
                assertEquals(task.error, TaskStatus.SUCCEEDED, task.status)
                assertEquals(params.h3Preset, task.model); assertEquals("72", task.videoRequestId)
                assertEquals("已完成", task.videoProgress); assertTrue(task.actualSize.contains("x"))
                assertEquals("/api/v1/uploads/video", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
                if (!upscale) assertEquals("/api/v1/uploads/audio", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
                val submit = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("/api/v1/jobs", submit.path); assertEquals(params.h3Preset, JSONObject(submit.body.readUtf8()).getString("preset"))
                assertEquals("/api/v1/jobs/72", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
                val download = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("/api/v1/jobs/72/video?download=1", download.path); assertEquals("Bearer RB-local-test", download.getHeader("Authorization"))
                BackupStore(app, store).export(Uri.fromFile(backup), listOf(task))
                java.util.zip.ZipFile(backup).use { zip ->
                    task.videoInputs.names.forEach { assertNotNull(zip.getEntry("${task.id}/$it")) }
                    assertNull(zip.getEntry("${task.id}/video-download.json"))
                }
                assertEquals(1, BackupStore(app, store).restore(Uri.fromFile(backup)))
                val restored = store.load().first.first { it.id != task.id && it.id !in before }
                assertEquals(task.videoInputs, restored.videoInputs); assertEquals(task.params, restored.params)
                restored.videoInputs.names.forEach { assertArrayEquals(store.file(task, it).readBytes(), store.file(restored, it).readBytes()) }
                val copied = store.copyVideoInputs(restored); imports.addAll(copied.names)
                assertEquals(task.videoInputs.names.size, copied.names.size)
                assertTrue(copied.files().allFiles.all { it.isFile })
                // Losing the original input copies must not make an already-submitted job unresumable.
                task.videoInputs.names.forEach { store.file(task, it).delete() }
                val interrupted = task.copy(status = TaskStatus.INTERRUPTED, outputs = emptyList()).also(store::save)
                val count = server.requestCount
                server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
                scenario.onActivity { GenerationService.resumeVideo(it, profile, interrupted) }
                runBlocking { withTimeout(20_000) { GenerationService.status.first { !it.running } } }
                assertEquals(TaskStatus.SUCCEEDED, GenerationService.status.value.tasks.single().status)
                assertEquals(count + 1, server.requestCount)
                assertEquals("/api/v1/jobs/72/video?download=1", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            }
        } finally {
            GenerationService.cancel(); imports.forEach { File(it).delete() }; video.delete(); audio.delete(); backup.delete()
            store.load().first.filter { it.id !in before }.forEach(store::delete); server.shutdown()
        }
    }
}
