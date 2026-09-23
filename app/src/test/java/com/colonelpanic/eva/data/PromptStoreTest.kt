package com.colonelpanic.eva.data

import android.app.Application
import android.net.Uri
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
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

    @Test
    fun `following the source takes new wording at most every so often and keeps the user's edit`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            var identity = "First upstream wording."
            val repository =
                PromptRepository { _, _ ->
                    val remote =
                        PromptDefaults.config.copy(
                            components =
                                PromptDefaults.config.components.map {
                                    if (it.id ==
                                        "identity"
                                    ) {
                                        it.copy(instruction = identity)
                                    } else {
                                        it
                                    }
                                },
                        )
                    PromptYaml.encode(remote).toByteArray()
                }
            var clock = 1_000_000L
            val store = PromptStore(context, repository = repository, now = { clock })
            store.load()
            store.update { config ->
                config.upsert(config.components.first { it.id == "honesty" }.copy(instruction = "My own honesty rule."))
            }

            fun wording(id: String) =
                (store.state.value as PromptState.Loaded)
                    .config.components
                    .first { it.id == id }
                    .instruction

            store.follow()
            assertEquals("First upstream wording.", wording("identity"))
            assertEquals("My own honesty rule.", wording("honesty"))

            identity = "Second upstream wording."
            store.follow()
            assertEquals("First upstream wording.", wording("identity"))

            clock += 16 * 60 * 1000L
            store.follow()
            assertEquals("Second upstream wording.", wording("identity"))
            assertEquals("My own honesty rule.", wording("honesty"))
        }
}
