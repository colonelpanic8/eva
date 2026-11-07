package com.colonelpanic.eva.capability.extensions

/**
 * Installed providers EVA enables, with every action, without a settings visit. Each is pinned to
 * its package and production signing certificate, so a same-named app from another signer, or a
 * debug build, is treated like any other provider.
 */
object DefaultProviders {
    private val pinned =
        mapOf(
            "com.colonelpanic.mova" to "905afc8729daa77fff81b20d99b169f919879379a8e7dbe23546e350ed46ad22",
            "sh.paseo.assembly" to "8d229a78b0d7c22086e743c9edc3678b5ed7e9c5388aeb18013729568139b8ee",
            "sh.paseo" to "421698bdca5bb9168c970e24539781509b5aa23fab8ab406a15b3f9c5f04c647",
        )

    fun trusts(identity: AdapterIdentity): Boolean = identity is ExtensionIdentity && pinned[identity.packageName] == identity.signer
}

/** Which installed providers start enabled, and the user's remembered choice to turn one off. */
interface DefaultGrantPolicy {
    fun trusts(identity: AdapterIdentity): Boolean

    fun autoEnable(instance: String): Boolean

    fun setAutoEnable(
        instance: String,
        enabled: Boolean,
    )

    object None : DefaultGrantPolicy {
        override fun trusts(identity: AdapterIdentity) = false

        override fun autoEnable(instance: String) = false

        override fun setAutoEnable(
            instance: String,
            enabled: Boolean,
        ) = Unit
    }
}
