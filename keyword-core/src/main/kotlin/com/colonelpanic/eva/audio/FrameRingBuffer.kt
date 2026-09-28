package com.colonelpanic.eva.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded frame queue between the capture thread and the uplink. [offer] never blocks: when full
 * it evicts the oldest frame and counts it in [dropped], so a stalled link costs old audio rather
 * than stalling the microphone.
 */
class FrameRingBuffer<T : Any>(
    val capacity: Int,
) {
    private val slots = arrayOfNulls<Any>(capacity)
    private var head = 0
    private var size = 0
    private var droppedCount = 0L
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    init {
        require(capacity > 0)
    }

    val dropped: Long get() = lock.withLock { droppedCount }

    val count: Int get() = lock.withLock { size }

    fun offer(item: T) {
        lock.withLock {
            if (size == capacity) {
                head = (head + 1) % capacity
                size--
                droppedCount++
            }
            slots[(head + size) % capacity] = item
            size++
            notEmpty.signal()
        }
    }

    fun poll(): T? = lock.withLock { removeFirst() }

    /** Waits up to [timeoutMs] for a frame; only the consumer ever waits. */
    fun take(timeoutMs: Long): T? =
        lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (size == 0 && remaining > 0) remaining = notEmpty.awaitNanos(remaining)
            removeFirst()
        }

    fun clear() {
        lock.withLock {
            slots.fill(null)
            head = 0
            size = 0
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeFirst(): T? {
        if (size == 0) return null
        val item = slots[head] as T
        slots[head] = null
        head = (head + 1) % capacity
        size--
        return item
    }
}
