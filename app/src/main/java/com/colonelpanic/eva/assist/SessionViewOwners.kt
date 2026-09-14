package com.colonelpanic.eva.assist

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A voice interaction session is not an Activity, so nothing supplies the owners a
 * `ComposeView` looks for in its view tree, and composition never starts without them.
 * The session drives these from its own callbacks instead.
 */
internal class SessionViewOwners :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** A session is never restored: it is created fresh for each invocation. */
    fun create() {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun show() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun hide() {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    fun own(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
