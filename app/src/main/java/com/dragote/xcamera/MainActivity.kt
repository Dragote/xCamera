package com.dragote.xcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dragote.xcamera.navigation.AppNavGraph
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import com.ramcosta.composedestinations.DestinationsNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XCameraTheme {
                DestinationsNavHost(navGraph = AppNavGraph)
            }
        }
    }
}
