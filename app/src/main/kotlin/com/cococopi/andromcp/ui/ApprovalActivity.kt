package com.cococopi.andromcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cococopi.andromcp.ui.screens.ApprovalScreen
import com.cococopi.andromcp.ui.theme.AndroMCPTheme
import dagger.hilt.android.AndroidEntryPoint

/** Hosts the on-device OAuth approval screen; launched from the approval notification. */
@AndroidEntryPoint
class ApprovalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroMCPTheme {
                ApprovalScreen(onAllHandled = ::finish)
            }
        }
    }
}
