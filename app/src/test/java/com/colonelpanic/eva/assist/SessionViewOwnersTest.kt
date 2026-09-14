package com.colonelpanic.eva.assist

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SessionViewOwnersTest {
    @Test
    fun `an observer added after creation receives creation and subsequent visibility events`() {
        val owners = SessionViewOwners()
        owners.create()
        val events = mutableListOf<Lifecycle.Event>()
        owners.lifecycle.addObserver(LifecycleEventObserver { _, event -> events += event })
        owners.show()
        owners.hide()
        owners.show()
        owners.destroy()
        assertEquals(
            listOf(
                Lifecycle.Event.ON_CREATE,
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
            ),
            events,
        )
    }

    @Test
    fun `destroy clears view models even when the session was never shown`() {
        val owners = SessionViewOwners()
        owners.create()
        var cleared = false
        owners.viewModelStore.put(
            "session",
            object : ViewModel() {
                override fun onCleared() {
                    cleared = true
                }
            },
        )
        owners.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owners.lifecycle.currentState)
        assertTrue(cleared)
    }
}
