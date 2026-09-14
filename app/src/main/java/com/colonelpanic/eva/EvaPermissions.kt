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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }

    fun missing(context: Context): List<String> =
        REQUIRED.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
}
