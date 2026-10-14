package com.colonelpanic.eva.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WatchActivity : Activity() {
    private val runtime get() = (application as WatchApplication).runtime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collection: Job? = null
    private var ticker: Job? = null
    private lateinit var toggle: Switch
    private lateinit var status: TextView
    private lateinit var instructions: TextView
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (32 * resources.displayMetrics.density).toInt()
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }
        toggle =
            Switch(this).apply {
                setText(R.string.enable)
                setOnCheckedChangeListener { _, enabled ->
                    if (!rendering) {
                        if (enabled &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                        }
                        runtime.toggle(enabled)
                    }
                }
            }
        status = TextView(this)
        layout.addView(toggle)
        instructions = TextView(this)
        layout.addView(instructions)
        layout.addView(status)
        layout.addView(
            Button(this).apply {
                setText(R.string.refresh)
                setOnClickListener { runtime.refresh() }
            },
        )
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onStart() {
        super.onStart()
        runtime.reconcile()
        runtime.refresh()
        ticker =
            scope.launch {
                while (true) {
                    runtime.recentNearMisses()
                    delay(1000)
                }
            }
        collection =
            scope.launch {
                runtime.state.collect { state ->
                    rendering = true
                    toggle.isChecked = state.enabled
                    rendering = false
                    instructions.text =
                        resources.getQuantityString(
                            R.plurals.instructions,
                            state.requiredPulses / 2,
                            state.requiredPulses / 2,
                            state.requiredPulses,
                        )
                    status.text = getString(R.string.status, state.status, state.lastDetection, runtime.recentNearMisses())
                }
            }
    }

    override fun onStop() {
        ticker?.cancel()
        collection?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
