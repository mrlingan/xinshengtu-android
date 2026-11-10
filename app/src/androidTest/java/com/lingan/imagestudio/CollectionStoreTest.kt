package com.lingan.imagestudio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingan.imagestudio.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CollectionStoreTest {
    @Test fun multipleMembershipRenameDeleteAndBackupRemap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folders = CollectionStore(context)
        val previous = folders.read()
        val store = LocalStore(context)
        val existing = store.load().first.map { it.id }.toSet()
        val zip = File(context.cacheDir, "collections-${UUID.randomUUID()}.zip")
        try {
            folders.update { emptyList() }
            val task = ImageTask(prompt = "collection test", profileName = "test", model = "test", params = GenerationParams(), status = TaskStatus.FAILED)
            store.save(task)
            val a = folders.create("素材").single()
            val b = folders.create("风格").last()
            folders.membership(a.id, setOf(task.id), true)
            folders.membership(b.id, setOf(task.id), true)
            assertEquals(2, CollectionStore(context).read().count { task.id in it.taskIds })
            folders.assign(task.id, setOf(a.id))
            assertEquals(1, folders.read().count { task.id in it.taskIds })
            folders.rename(a.id, "新素材")
            assertTrue(runCatching { folders.create("新素材") }.isFailure)
            folders.delete(b.id)
            assertTrue(store.directory(task.id).isDirectory)
            val backup = BackupStore(context, store)
            backup.export(Uri.fromFile(zip), listOf(task))
            assertEquals(1, backup.restore(Uri.fromFile(zip)))
            val restored = store.load().first.single { it.id !in existing && it.id != task.id }
            val imported = folders.read().single { it.id != a.id }
            assertEquals(setOf(restored.id), imported.taskIds)
            assertNotEquals(a.id, imported.id)
            assertNotEquals("新素材", imported.name)
            assertEquals(setOf(task.id), folders.read().single { it.id == a.id }.taskIds)
            folders.delete(imported.id)
            assertTrue(store.directory(restored.id).exists())
        } finally {
            store.load().first.filter { it.id !in existing }.forEach(store::delete)
            folders.update { previous }
            zip.delete()
        }
    }
}
