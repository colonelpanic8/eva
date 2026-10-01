package com.colonelpanic.eva.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSnapshotTest {
    @Test fun deviceOwnershipWaitsAndStoppingTakePrecedenceOverInactivity() {
        val owner = taskState(true, false, false, true, true, true, false)
        assertEquals(TaskState.WORKING, owner)
        assertTrue(owner.canStall())
        val needsInput = taskState(true, false, true, false, true, true, false)
        assertEquals(TaskState.NEEDS_INPUT, needsInput)
        assertFalse(needsInput.canStall())
        val queued = taskState(true, false, false, true, false, true, false)
        assertEquals(TaskState.WAITING_FOR_DEVICE, queued)
        assertFalse(queued.canStall())
        val stopping = taskState(false, false, false, false, true, false, false)
        assertEquals(TaskState.STOPPING, stopping)
        assertFalse(stopping.canStall())
        val releasing = taskState(false, true, false, false, true, false, false)
        assertEquals(TaskState.RELEASING_DEVICE, releasing)
        assertFalse(releasing.canStall())
        assertFalse(TaskState.WAITING_FOR_EXTENSION.canStall())
        assertTrue(TaskState.CONNECTING.canStall())
    }
}
