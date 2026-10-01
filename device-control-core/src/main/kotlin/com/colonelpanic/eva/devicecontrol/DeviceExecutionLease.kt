package com.colonelpanic.eva.devicecontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * One application-wide lease shared by task execution and ordinary UI-changing operations.
 * Waiters queue in arrival order; a waiter cancelled before its turn never holds the lease.
 */
class DeviceExecutionLease {
    private val mutex = Mutex()

    private val mutableOwner = MutableStateFlow<String?>(null)
    val ownerFlow = mutableOwner.asStateFlow()
    val owner: String? get() = mutableOwner.value

    suspend fun acquire(owner: String) {
        mutex.lock()
        mutableOwner.value = owner
    }

    @Synchronized
    fun releaseIfOwned(owner: String) {
        if (mutableOwner.value == owner) release(owner)
    }

    @Synchronized
    fun release(owner: String) {
        check(mutableOwner.value == owner)
        mutableOwner.value = null
        mutex.unlock()
    }
}
