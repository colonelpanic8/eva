package com.colonelpanic.eva.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object MicrophonePermission {
    const val PERMISSION = Manifest.permission.RECORD_AUDIO

    fun isGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
