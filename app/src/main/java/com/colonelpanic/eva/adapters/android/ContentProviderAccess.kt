package com.colonelpanic.eva.adapters.android

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.core.content.ContextCompat
import com.colonelpanic.eva.EvaPermissions

/** Device-local access, separate from the package's portable capability grants. */
data class ContentProviderAccess(
    val authority: String,
    val problem: String?,
    val permission: String? = null,
    val canRequest: Boolean = false,
) {
    companion object {
        const val MOVA_READ_TODOS = "com.colonelpanic.mova.permission.READ_TODOS"
        val supportedPermissions = EvaPermissions.REQUIRED.toSet() + MOVA_READ_TODOS

        fun requiredPermission(
            context: Context,
            authority: String,
        ): String? =
            context.packageManager
                .resolveContentProvider(authority, 0)
                ?.readPermission
                ?.takeIf { it in supportedPermissions }

        fun inspect(
            context: Context,
            authority: String,
        ): ContentProviderAccess {
            val provider =
                context.packageManager.resolveContentProvider(authority, 0)
                    ?: return ContentProviderAccess(
                        authority,
                        "Install or enable the app providing $authority and ensure EVA can see its provider.",
                    )
            if (!provider.enabled || !provider.exported || provider.applicationInfo?.enabled == false) {
                return ContentProviderAccess(authority, "The provider $authority is disabled or not exported.")
            }
            val permission = provider.readPermission ?: return ContentProviderAccess(authority, null)
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                return ContentProviderAccess(authority, null, permission)
            }
            val dangerous =
                runCatching {
                    context.packageManager.getPermissionInfo(permission, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE ==
                        PermissionInfo.PROTECTION_DANGEROUS
                }.getOrDefault(false)
            val declared =
                context.packageManager
                    .getPackageInfo(
                        context.packageName,
                        PackageManager.GET_PERMISSIONS,
                    ).requestedPermissions
                    .orEmpty()
            val canRequest = permission in supportedPermissions && permission in declared && dangerous
            return ContentProviderAccess(
                authority,
                if (canRequest) {
                    "Authorize $permission in extension settings to read this provider."
                } else {
                    "The provider requires $permission, which this EVA build cannot request. Check the provider's access policy."
                },
                permission,
                canRequest,
            )
        }
    }
}
