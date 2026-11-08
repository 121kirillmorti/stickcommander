package com.stickcommander

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.stickcommander.ui.StickCommanderApp
import com.stickcommander.ui.theme.StickCommanderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as StickCommanderApplication).container
        setContent {
            StickCommanderTheme {
                StickCommanderApp(container = container)
            }
        }
    }
}
