package com.colonelpanic.eva

import android.app.Application
import com.colonelpanic.eva.adapters.android.AndroidIntentHost
import com.colonelpanic.eva.adapters.android.MapIntentBackend
import com.colonelpanic.eva.adapters.android.MessageIntentBackend
import com.colonelpanic.eva.adapters.android.NavigationIntentBackend
import com.colonelpanic.eva.audio.RealtimeMediaConfig
import com.colonelpanic.eva.audio.webrtc.WebRtcMediaSessionFactory
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.conversation.ProviderSessionController
import com.colonelpanic.eva.data.SqliteInvocationRepository
import com.colonelpanic.eva.providers.BrokerConversationProvider
import com.colonelpanic.eva.providers.BrokerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EvaApplication : Application() {
    val intentHost = AndroidIntentHost()
    private val mediaFactory by lazy { WebRtcMediaSessionFactory(this) }
    val controller by lazy {
        val repository = SqliteInvocationRepository(this)
        val registry =
            CapabilityRegistry(
                mapOf(
                    CapabilityRegistry.MAP_SEARCH to MapIntentBackend(intentHost),
                    CapabilityRegistry.NAVIGATE to NavigationIntentBackend(intentHost),
                    CapabilityRegistry.SMS_COMPOSE to MessageIntentBackend(intentHost),
                ),
            )
        ProviderSessionController(
            registry = registry,
            dispatcher = CapabilityDispatcher(registry, repository),
            providerFactory = { link -> BrokerConversationProvider(BrokerEndpoint.parse(link)) },
            mediaFactory = { mode -> mediaFactory.create(RealtimeMediaConfig(mode)) },
            voiceProviderFactory = { link, audio ->
                val endpoint = BrokerEndpoint.parse(link)
                BrokerConversationProvider(endpoint, offerSdp = audio.createOffer(), onAnswer = audio::acceptAnswer)
            },
            repository = repository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
