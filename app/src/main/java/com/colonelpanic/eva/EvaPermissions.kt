package com.colonelpanic.eva

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.colonelpanic.eva.audio.MicrophonePermission

/**
 * Every runtime permission EVA uses, asked for together when the app opens. A voice session keeps
 * running while the phone is locked or another app is in front, and no permission dialog can be
 * shown from there, so a grant that only arrives when a capability first runs arrives too late.
 */
object EvaPermissions {
    val REQUIRED: List<String> =
        buildList {
            add(MicrophonePermission.PERMISSION)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }

    /** The grants the messaging screen reports, in the order it shows them. */
    val MESSAGING: List<String> =
        listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
        )

    fun missing(context: Context): List<String> = REQUIRED.filterNot { isGranted(context, it) }

    fun isGranted(
        context: Context,
        permission: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
