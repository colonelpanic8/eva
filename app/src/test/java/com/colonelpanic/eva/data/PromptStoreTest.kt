package com.colonelpanic.eva.data

import android.app.Application
import android.net.Uri
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptYaml
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PromptStoreTest {
    @Test
    fun `selecting either prompt file publishes its contents to linked configuration`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            val selected = PromptConfig(listOf(PromptComponent("selected", instruction = "Selected instructions.")))
            val external = File(context.cacheDir, "selected-prompt.yaml").apply { writeText(PromptYaml.encode(selected)) }
            val changes = mutableListOf<PromptConfig>()
            lateinit var store: PromptStore
            store = PromptStore(context, onChanged = { changes += (store.state.value as PromptState.Loaded).config })
            val original = store.load()

            store.useDocument(Uri.fromFile(external), create = false)

            assertFalse(store.noticeIsError.value)
            assertTrue(store.location.value.chosen)
            assertEquals(listOf(selected), changes)

            store.useOwnFile()

            assertFalse(store.noticeIsError.value)
            assertFalse(store.location.value.chosen)
            assertEquals(listOf(selected, original), changes)
            assertEquals(PromptYaml.encode(selected), external.readText())
        }

    @Test
    fun `invalid prompt selection leaves the previous source and linked state unchanged`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            val external = File(context.cacheDir, "invalid-prompt.yaml").apply { writeText("components: [") }
            var changes = 0
            val store = PromptStore(context, onChanged = { changes++ })
            val original = store.load()

            store.useDocument(Uri.fromFile(external), create = false)

            assertTrue(store.noticeIsError.value)
            assertFalse(store.location.value.chosen)
            assertEquals(original, store.load())
            assertEquals(0, changes)
        }
}
