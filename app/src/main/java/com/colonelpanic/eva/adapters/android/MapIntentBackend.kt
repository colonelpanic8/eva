package com.colonelpanic.eva.adapters.android

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.colonelpanic.eva.capability.ExecutionBackend

class MapIntentBackend(
    private val host: AndroidIntentHost,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = host.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>) =
        host.launch(
            mapSearchIntent(arguments.getValue("destination")),
            "Map search opened.",
            "No map app is available. Install one and try again.",
        )

    companion object {
        fun mapSearchIntent(destination: String): Intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(destination)}".toUri())
    }
}
