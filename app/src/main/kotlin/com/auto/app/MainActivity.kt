package com.auto.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

/** Thin host: pages are in AutoShell.kt / HomeScreen.kt / SettingsScreen.kt, the editor in EditorScreen.kt, its logic in AutoState.kt. */
class MainActivity : ComponentActivity() {
    private lateinit var st: AutoState
    private var mode = 1
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> st.onPicked(mode, uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        st = AutoState(this)
        st.pick = { m -> mode = m; picker.launch(arrayOf("*/*")) }
        setContent { AutoShell(st) }
    }

    override fun onDestroy() { st.release(); super.onDestroy() }
}
