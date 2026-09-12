package com.colonelpanic.eva

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.colonelpanic.eva.ui.EvaApp
import com.colonelpanic.eva.ui.theme.EvaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EvaTheme {
                EvaApp()
            }
        }
    }
}
