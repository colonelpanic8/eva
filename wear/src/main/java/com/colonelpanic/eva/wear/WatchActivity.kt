package com.colonelpanic.eva.wear

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
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
    private lateinit var taskLayout: LinearLayout
    private var taskCollection: Job? = null
    private var taskTicker: Job? = null
    private var dictationThread: String? = null
    private var dictationQuestion: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dictationThread = savedInstanceState?.getString("dictationThread")
        dictationQuestion = savedInstanceState?.getString("dictationQuestion")
        val padding = (32 * resources.displayMetrics.density).toInt()
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }
        taskLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(taskLayout)
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
        runtime.tasks.refresh()
        taskCollection = scope.launch { runtime.tasks.state.collect(::renderTasks) }
        taskTicker =
            scope.launch {
                while (true) {
                    delay(5_000)
                    runtime.tasks.poll()
                }
            }
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
        taskCollection?.cancel()
        taskTicker?.cancel()
        ticker?.cancel()
        collection?.cancel()
        super.onStop()
    }

    private fun renderTasks(state: WatchTaskUi) {
        taskLayout.removeAllViews()

        fun button(
            label: Int,
            action: () -> Unit,
        ) {
            taskLayout.addView(
                Button(this).apply {
                    setText(label)
                    setOnClickListener { action() }
                },
            )
        }

        fun text(value: String) {
            if (value.isNotBlank()) taskLayout.addView(TextView(this).apply { text = value })
        }
        button(R.string.dictate_task) { dictate(null, null) }
        text(state.receipt)
        state.error?.let(::text)
        if (state.threadId == null) {
            text(getString(R.string.recent_tasks))
            for (conversation in state.conversations) {
                taskLayout.addView(
                    Button(this).apply {
                        text = getString(R.string.task_summary, conversation.title, conversation.status)
                        setOnClickListener { runtime.tasks.open(conversation.id) }
                    },
                )
            }
        } else {
            button(R.string.recent_tasks) { runtime.tasks.open(null) }
            text(state.status)
            for (entry in state.entries) text(getString(R.string.task_entry, entry.role, entry.text))
            state.question?.let(::text)
            button(if (state.questionId != null) R.string.dictate_answer else R.string.dictate_followup) {
                dictate(state.threadId, state.questionId)
            }
            button(R.string.stop_task) { runtime.tasks.stop() }
        }
        if (state.nextBefore != null) button(R.string.older_tasks) { runtime.tasks.refresh(older = true) }
        button(R.string.refresh_tasks) { runtime.tasks.refresh() }
    }

    @Suppress("DEPRECATION")
    private fun dictate(
        threadId: String?,
        questionId: String?,
    ) {
        dictationThread = threadId
        dictationQuestion = questionId
        try {
            startActivityForResult(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(
                        RecognizerIntent.EXTRA_PROMPT,
                        getString(
                            if (questionId !=
                                null
                            ) {
                                R.string.dictate_answer
                            } else {
                                R.string.dictate_task
                            },
                        ),
                    )
                },
                DICTATION,
            )
        } catch (_: ActivityNotFoundException) {
            runtime.tasks.failure(getString(R.string.no_recognizer))
        } catch (_: SecurityException) {
            runtime.tasks.failure(getString(R.string.recognizer_denied))
        }
    }

    @Deprecated("Activity result bridge for system dictation")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DICTATION) return
        if (resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            runtime.tasks.submit(text, dictationThread, dictationQuestion)
        }
        dictationThread = null
        dictationQuestion = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("dictationThread", dictationThread)
        outState.putString("dictationQuestion", dictationQuestion)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val DICTATION = 42
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
