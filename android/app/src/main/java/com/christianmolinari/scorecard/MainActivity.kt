package com.christianmolinari.scorecard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.christianmolinari.scorecard.ui.ScoreCardApp
import com.christianmolinari.scorecard.ui.theme.ScoreCardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ScoreCardApplication).container
        setContent {
            ScoreCardTheme {
                ScoreCardApp(container)
            }
        }
    }
}
