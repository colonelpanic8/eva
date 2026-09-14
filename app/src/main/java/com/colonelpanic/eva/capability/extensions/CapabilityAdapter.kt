package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.flow.StateFlow

/** Discovery and execution mapping only. Authorization and catalog admission belong to EVA. */
interface CapabilityAdapter {
    val installed: StateFlow<List<InstalledExtension>>
    val ready: StateFlow<Boolean>

    fun refresh()

    fun invalidate(
        packageName: String,
        removed: Boolean,
    )

    fun available(
        identity: ExtensionIdentity,
        digest: String,
    ): Boolean

    fun bindings(extension: InstalledExtension): List<CapabilityBinding>
}

data class CapabilityBinding(
    val capability: Capability,
    val definition: CapabilityDefinition,
    val backend: ExecutionBackend,
    val revision: String,
)

class GrantedExecutionBackend(
    private val backend: ExecutionBackend,
    private val authorization: () -> String?,
) : ExecutionBackend {
    override fun dispatchRejection(): String? = authorization() ?: backend.dispatchRejection()

    override suspend fun unavailableReason(): String? = authorization() ?: backend.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        authorization()?.let { ExecutionOutcome(InvocationStatus.NOT_EXECUTED, it) } ?: backend.execute(arguments)

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome =
        authorization()?.let { ExecutionOutcome(InvocationStatus.NOT_EXECUTED, it) } ?: backend.execute(proposal)
}
