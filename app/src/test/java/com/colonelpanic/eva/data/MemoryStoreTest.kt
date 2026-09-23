package com.colonelpanic.eva.data

import android.app.Application
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryCapabilities
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class MemoryStoreTest {
    @Test fun notesSurviveReopeningAndCanBeReplacedAndForgotten() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            File(context.filesDir, "memories.json").delete()
            val store = MemoryStore(context) { 123L }
            store.save("Kyoto hotel", "Hotel A, September 23–27")
            assertEquals(listOf(MemoryNote("Kyoto hotel", "Hotel A, September 23–27", 123L)), MemoryStore(context).all())
            store.save("Kyoto hotel", "Hotel B, September 23–27")
            assertEquals(1, store.all().size)
            assertEquals("Hotel B, September 23–27", store.all().single().text)
            assertFalse(store.forget("hotel"))
            assertTrue(store.forget("Kyoto hotel"))
            assertTrue(MemoryStore(context).all().isEmpty())
        }

    @Test fun toolsSearchPageAndDeclareMutations() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            File(context.filesDir, "memories.json").delete()
            val store = MemoryStore(context)
            val backends = MemoryCapabilities.backends(store)
            val registry = CapabilityRegistry(backends)
            assertTrue(
                registry.snapshot.definitions
                    .getValue(MemoryCapabilities.SEARCH)
                    .readOnly,
            )
            assertFalse(
                registry.snapshot.definitions
                    .getValue(MemoryCapabilities.SAVE)
                    .readOnly,
            )
            assertFalse(
                registry.snapshot.definitions
                    .getValue(MemoryCapabilities.FORGET)
                    .readOnly,
            )
            for (i in 1..12) {
                assertEquals(
                    InvocationStatus.COMPLETED,
                    backends
                        .getValue(MemoryCapabilities.SAVE)
                        .execute(
                            mapOf(
                                "name" to "Trip $i",
                                "text" to "Kyoto hotel $i",
                            ),
                        ).status,
                )
            }
            val search = backends.getValue(MemoryCapabilities.SEARCH)
            val first = search.execute(mapOf("query" to "KYOTO")).data!!
            assertEquals(MemoryCapabilities.PAGE_SIZE, first.getValue("notes").jsonArray.size)
            assertEquals("12", first.getValue("total").jsonPrimitive.content)
            val last = search.execute(mapOf("query" to "KYOTO", "offset" to first.getValue("nextOffset").jsonPrimitive.content)).data!!
            assertEquals(2, last.getValue("notes").jsonArray.size)
            assertFalse(last.containsKey("nextOffset"))
            assertEquals(
                0,
                search
                    .execute(mapOf("query" to "missing"))
                    .data!!
                    .getValue("notes")
                    .jsonArray.size,
            )
            assertEquals(
                InvocationStatus.NOT_EXECUTED,
                backends.getValue(MemoryCapabilities.SAVE).execute(mapOf("name" to " ", "text" to "fact")).status,
            )
            backends.getValue(MemoryCapabilities.FORGET).execute(mapOf("name" to "Trip 1"))
            assertEquals(11, MemoryStore(context).all().size)
        }

    @Test fun learnedNotesWaitInTheInboxYetAreSearchable() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            File(context.filesDir, "memories.json").delete()
            File(context.filesDir, "memory-inbox.json").delete()
            var now = 0L
            val store = MemoryStore(context) { now++ }
            val backends = MemoryCapabilities.backends(store)
            assertTrue(
                CapabilityRegistry(backends)
                    .snapshot.definitions
                    .getValue(MemoryCapabilities.LEARN)
                    .bookkeeping,
            )
            store.save("Coffee", "Oat flat white")
            val learn = backends.getValue(MemoryCapabilities.LEARN)
            assertEquals(InvocationStatus.NOT_EXECUTED, learn.execute(mapOf("name" to "Coffee", "text" to "Espresso")).status)
            assertEquals("Oat flat white", store.all().single().text)

            store.learn("Sister", "Maya, lives in Denver", "thread-1")
            val found = backends.getValue(MemoryCapabilities.SEARCH).execute(mapOf("query" to "denver")).data!!
            val note =
                found
                    .getValue("notes")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("false", note.getValue("reviewed").jsonPrimitive.content)
            assertEquals(
                "thread-1",
                MemoryStore(context)
                    .load()
                    .inbox
                    .single()
                    .threadId,
            )

            assertTrue(store.keep("Sister"))
            assertFalse(store.keep("Sister"))
            val kept = MemoryStore(context).load()
            assertEquals(listOf("Coffee", "Sister"), kept.kept.map { it.name })
            assertTrue(kept.inbox.isEmpty())

            store.learn("Gym", "Mondays", null)
            store.save("Gym", "Mondays and Thursdays")
            assertTrue(store.load().inbox.isEmpty())
            store.learn("Dentist", "Dr. Lee", null)
            assertTrue(store.forget("Dentist"))
            assertTrue(store.load().inbox.isEmpty())
        }

    @Test fun aFullInboxDropsItsOldestNote() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            File(context.filesDir, "memory-inbox.json").delete()
            var now = 0L
            val store = MemoryStore(context) { now++ }
            for (i in 0..MemoryStore.MAX_INBOX) store.learn("Fact $i", "text", null)
            val inbox = MemoryStore(context).load().inbox
            assertEquals(MemoryStore.MAX_INBOX, inbox.size)
            assertFalse(inbox.any { it.name == "Fact 0" })
            assertTrue(inbox.any { it.name == "Fact ${MemoryStore.MAX_INBOX}" })
        }

    @Test fun corruptStorageIsNotSilentlyOverwritten() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val file = File(context.filesDir, "memories.json")
            file.writeText("broken")
            try {
                MemoryStore(context).save("hotel", "somewhere")
                fail("Corruption must fail closed")
            } catch (_: kotlinx.serialization.SerializationException) {
                assertEquals("broken", file.readText())
            } finally {
                file.delete()
            }
        }
}
