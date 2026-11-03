package com.auto.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Application-scoped holder so `AutoState` and running export/upscale jobs are not destroyed
 * if `MainActivity` is recreated on configuration change or background navigation (Priority 2).
 */
object AutoAppHolder {
    private var instance: AutoState? = null

    @Synchronized
    fun getOrCreate(act: ComponentActivity): AutoState {
        val existing = instance
        return if (existing != null) {
            existing.attachActivity(act)
            existing
        } else {
            AutoState(act).also { instance = it }
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var st: AutoState
    private var mode = 1
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        st.onPicked(mode, uris)
    }
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        st = AutoAppHolder.getOrCreate(this)
        st.pick = { m -> mode = m; picker.launch(arrayOf("*/*")) }
        requestNotificationPermissionIfNeeded()
        setContent { AutoShell(st) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                try {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Throwable) {}
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            st.release()
            st.releaseClick()
        }
        super.onDestroy()
    }
}
