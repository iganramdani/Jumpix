package com.jumpropeai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.jumpropeai.ui.JumpRopeScreen
import com.jumpropeai.ui.JumpRopeViewModel
import com.jumpropeai.ui.theme.JumpRopeAITheme

class MainActivity : ComponentActivity() {

    private val viewModel: JumpRopeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            JumpRopeAITheme {
                JumpRopeScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Auto-save active workout immediately when app goes to background or screen turns off
        viewModel.autoSaveIfNeeded()
    }
}
