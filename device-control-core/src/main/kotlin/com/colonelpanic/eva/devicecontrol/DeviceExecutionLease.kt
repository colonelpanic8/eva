package com.colonelpanic.eva.devicecontrol

import kotlinx.coroutines.sync.Mutex

/**
 * One application-wide lease shared by task execution and ordinary UI-changing operations.
 * Waiters queue in arrival order; a waiter cancelled before its turn never holds the lease.
 */
class DeviceExecutionLease {
    private val mutex = Mutex()

    @Volatile private var holder: String? = null
    val owner: String? get() = holder

    suspend fun acquire(owner: String) {
        mutex.lock()
        holder = owner
    }

    fun release(owner: String) {
        check(holder == owner)
        holder = null
        mutex.unlock()
    }
}
