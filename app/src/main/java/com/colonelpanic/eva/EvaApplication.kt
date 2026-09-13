package com.colonelpanic.eva

import android.app.Application
import com.colonelpanic.eva.adapters.android.AndroidIntentHost
import com.colonelpanic.eva.adapters.android.IntentBackend
import com.colonelpanic.eva.adapters.android.MapIntentBackend
import com.colonelpanic.eva.adapters.android.MessageIntentBackend
import com.colonelpanic.eva.adapters.android.NativeIntents
import com.colonelpanic.eva.adapters.android.NavigationIntentBackend
import com.colonelpanic.eva.audio.RealtimeMediaConfig
import com.colonelpanic.eva.audio.VoiceSessionService
import com.colonelpanic.eva.audio.webrtc.WebRtcMediaSessionFactory
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.conversation.ProviderSessionController
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.data.SqliteInvocationRepository
import com.colonelpanic.eva.providers.BrokerConversationProvider
import com.colonelpanic.eva.providers.BrokerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EvaApplication : Application() {
    val intentHost = AndroidIntentHost()

    private fun intent(
        success: String,
        missing: String,
        build: (Map<String, String>) -> android.content.Intent?,
    ) = IntentBackend(intentHost, success, missing, build)

    private val mediaFactory by lazy { WebRtcMediaSessionFactory(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val registry by lazy {
        CapabilityRegistry(
            mapOf(
                CapabilityRegistry.MAP_SEARCH to MapIntentBackend(intentHost),
                CapabilityRegistry.NAVIGATE to NavigationIntentBackend(intentHost),
                CapabilityRegistry.SMS_COMPOSE to MessageIntentBackend(intentHost),
                CapabilityRegistry.SET_ALARM to
                    intent("Alarm set.", "No clock app accepted this alarm.", NativeIntents::alarm),
                CapabilityRegistry.SET_TIMER to
                    intent("Timer started.", "No clock app accepted this timer.", NativeIntents::timer),
                CapabilityRegistry.DIAL to
                    intent("Dialer opened.", "No phone app is available.", NativeIntents::dial),
                CapabilityRegistry.WEB_SEARCH to
                    intent("Web search opened.", "No browser or search app is available.", NativeIntents::webSearch),
                CapabilityRegistry.OPEN_URL to
                    intent("Web page opened.", "No browser is available.", NativeIntents::openUrl),
                CapabilityRegistry.EMAIL_COMPOSE to
                    intent("Email draft opened. Send it from your mail app.", "No email app is available.", NativeIntents::email),
                CapabilityRegistry.CALENDAR_EVENT to
                    intent(
                        "Calendar event opened. Save it in your calendar.",
                        "No calendar app is available.",
                        NativeIntents::calendarEvent,
                    ),
                CapabilityRegistry.OPEN_APP to
                    intent("App opened.", "No installed app matches that name.") { NativeIntents.launchApp(this, it) },
                CapabilityRegistry.OPEN_SETTINGS to
                    intent("Settings opened.", "That settings screen is unavailable on this device.", NativeIntents::settings),
            ),
        )
    }
    val controller by lazy {
        val repository = SqliteInvocationRepository(this)
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
            scope = scope,
        ).also { controller ->
            scope.launch {
                controller.state
                    .map { it.voiceMode && it.providerStatus != ProviderStatus.DISCONNECTED }
                    .distinctUntilChanged()
                    .collect { active ->
                        if (active) VoiceSessionService.start(this@EvaApplication) else VoiceSessionService.stop(this@EvaApplication)
                    }
            }
        }
    }
}
