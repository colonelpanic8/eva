package com.colonelpanic.eva.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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

/** The first installed package's icon, or [fallback] once none of them has one. */
@Composable
internal fun InstalledAppIcon(
    packages: List<String>,
    fallback: @Composable () -> Unit = {},
) {
    val manager = LocalContext.current.packageManager
    val icon by produceState<Result<ImageBitmap?>?>(null, manager, packages) {
        value =
            withContext(Dispatchers.IO) {
                Result.success(
                    packages.firstNotNullOfOrNull { name ->
                        try {
                            manager.getApplicationIcon(name).toBitmap(96, 96).asImageBitmap()
                        } catch (_: PackageManager.NameNotFoundException) {
                            null
                        } catch (_: SecurityException) {
                            null
                        }
                    },
                )
            }
    }
    when (val loaded = icon) {
        null -> Box(Modifier.size(40.dp))
        else -> loaded.getOrNull()?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) } ?: fallback()
    }
}
