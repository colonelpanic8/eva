package com.colonelpanic.eva.adapters.android

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.colonelpanic.eva.capability.ExecutionBackend

class NavigationIntentBackend(
    private val host: AndroidIntentHost,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = host.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>) =
        host.launch(
            navigationIntent(arguments.getValue("destination")),
            "Driving navigation requested.",
            "No app supports this navigation request. Install Google Maps or a compatible navigation app.",
        )

    companion object {
        fun navigationIntent(destination: String): Intent =
            Intent(Intent.ACTION_VIEW, "google.navigation:q=${Uri.encode(destination)}&mode=d".toUri())
    }
}
