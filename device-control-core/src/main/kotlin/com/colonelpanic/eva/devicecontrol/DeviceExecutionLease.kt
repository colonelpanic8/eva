package com.colonelpanic.eva.devicecontrol

import java.util.concurrent.atomic.AtomicReference

/** One application-wide lease shared by task execution and ordinary UI-changing operations. */
class DeviceExecutionLease {
    private val holder = AtomicReference<String?>(null)
    val owner: String? get() = holder.get()

    fun acquire(owner: String): Boolean = holder.compareAndSet(null, owner)

    fun release(owner: String) {
        check(holder.compareAndSet(owner, null))
    }
}
