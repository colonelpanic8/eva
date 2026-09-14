package com.colonelpanic.eva.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun InstalledAppIcon(packages: List<String>) {
    val manager = LocalContext.current.packageManager
    val icon by produceState<ImageBitmap?>(null, manager, packages) {
        value =
            withContext(Dispatchers.IO) {
                packages.firstNotNullOfOrNull { name ->
                    try {
                        manager.getApplicationIcon(name).toBitmap(96, 96).asImageBitmap()
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    }
                }
            }
    }
    icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
}
