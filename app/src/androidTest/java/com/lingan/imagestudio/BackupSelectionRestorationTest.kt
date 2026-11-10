package com.lingan.imagestudio

import android.app.Application
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingan.imagestudio.data.*
import com.lingan.imagestudio.ui.StudioApp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BackupSelectionRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedBackupRetainsTaskIdsWhileDocumentPickerIsPending() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = LocalStore(app)
        val task = ImageTask(prompt = "restore selection ${UUID.randomUUID()}", profileName = "test", model = "test", params = GenerationParams(), status = TaskStatus.FAILED)
        val zip = File(app.cacheDir, "selection-${UUID.randomUUID()}.zip")
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        var requestCode: Int? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { requestCode = code }
        }
        val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val tester = StateRestorationTester(compose)
        try {
            store.save(task)
            lateinit var vm: StudioViewModel
            compose.runOnUiThread { vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[StudioViewModel::class.java] }
            tester.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) { MaterialTheme { StudioApp(vm) } } }
            compose.waitUntil(10_000) { !vm.state.value.loading && vm.state.value.tasks.any { it.id == task.id } }
            compose.onNodeWithText("作品库").performClick()
            compose.onNodeWithText("搜索提示词或模型").performTextInput(task.prompt)
            compose.onNodeWithText("批量管理").performClick()
            compose.onNodeWithText("选择当前结果").performClick()
            compose.onNodeWithText("备份选中").performClick()
            val launchedCode = compose.runOnIdle { requireNotNull(requestCode) }
            requestCode = null
            tester.emulateSavedInstanceStateRestore()
            compose.runOnIdle { assertNull(requestCode); registry.dispatchResult(launchedCode, Uri.fromFile(zip)) }
            compose.waitUntil(10_000) { !vm.state.value.dataBusy && zip.isFile }
            ZipFile(zip).use { backup ->
                val manifest = JSONObject(backup.getInputStream(backup.getEntry("backup.json")).bufferedReader().use { it.readText() })
                val tasks = manifest.getJSONArray("tasks")
                assertEquals("The selected task must survive state restoration", 1, tasks.length())
                assertEquals(task.id, tasks.getJSONObject(0).getString("id"))
            }
        } finally {
            compose.runOnUiThread { owner.viewModelStore.clear() }
            store.delete(task); zip.delete()
        }
    }
}
