package com.colonelpanic.eva

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class SpotifyRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.data?.let { (application as EvaApplication).completeSpotifyRedirect(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
