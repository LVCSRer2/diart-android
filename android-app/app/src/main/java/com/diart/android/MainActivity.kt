package com.diart.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.diart.android.ui.DiarizationScreen
import com.diart.android.ui.SettingsScreen
import com.diart.android.ui.theme.DiartTheme

class MainActivity : ComponentActivity() {

    private val vm: DiarizationViewModel by viewModels()
    private var permissionGranted by mutableStateOf(false)

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermission.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            DiartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val showSettings by vm.showSettings.collectAsState()
                    val settings by vm.settings.collectAsState()

                    if (showSettings) {
                        SettingsScreen(
                            current = settings,
                            onApply = { vm.applySettings(it) },
                            onBack = { vm.closeSettings() },
                        )
                    } else {
                        DiarizationScreen(vm = vm)
                    }
                }
            }
        }
    }
}
