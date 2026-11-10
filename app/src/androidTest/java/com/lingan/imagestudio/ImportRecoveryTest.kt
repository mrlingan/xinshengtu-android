package com.lingan.imagestudio

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ImportRecoveryTest {
    @Test fun failedBatchImportKeepsDraftAndRemovesPartialCacheFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "references").apply { mkdirs() }
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val good = File(context.cacheDir, "import-${UUID.randomUUID()}.png")
        val bad = File(context.cacheDir, "import-${UUID.randomUUID()}.txt").apply { writeText("not an image") }
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
            try { good.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: StudioViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[StudioViewModel::class.java] }
                awaitCondition { !vm.state.value.loading }
                scenario.onActivity { vm.importImages(listOf(Uri.fromFile(good), Uri.fromFile(bad))) }
                awaitCondition { !vm.state.value.importing }
                assertTrue(vm.state.value.references.isEmpty())
                assertEquals("Failed import must not leave invisible reference files", before, directory.listFiles().orEmpty().map { it.name }.toSet())
            }
        } finally {
            good.delete(); bad.delete()
            directory.listFiles().orEmpty().filter { it.name !in before }.forEach { it.delete() }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) CountDownLatch(1).await(20, TimeUnit.MILLISECONDS)
        assertTrue("Operation timed out", condition())
    }
}
