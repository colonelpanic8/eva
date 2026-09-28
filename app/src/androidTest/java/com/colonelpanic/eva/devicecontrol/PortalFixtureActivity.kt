package com.colonelpanic.eva.devicecontrol

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Exists only in the test APK; fixtures stay independent of the user's installed apps. */
class PortalFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
            }
        val status =
            TextView(this).apply {
                text = if (intent.data == null) "Fixture ready" else "URL opened"
                textSize = 22f
            }
        column.addView(status)
        column.addView(
            Button(this).apply {
                text = "Activate fixture"
                isAllCaps = false
                setOnClickListener { status.text = "Activated" }
                setOnLongClickListener {
                    status.text = "Long pressed"
                    true
                }
            },
        )
        column.addView(
            EditText(this).apply {
                contentDescription = "Fixture input"
                hint = "Fixture input"
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { _, _, _ ->
                    status.text = "Search submitted"
                    true
                }
            },
        )
        column.addView(
            EditText(this).apply {
                contentDescription = "Fixture password"
                hint = "Fixture password"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
        )
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(60) { row ->
            content.addView(
                TextView(this).apply {
                    text = "Fixture row $row"
                    textSize = 22f
                    setPadding(8, 26, 8, 26)
                },
            )
        }
        column.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
    }
}
