package com.atan.starkaudio

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.atan.starkaudio.ui.StarkSignalApp
import com.atan.starkaudio.ui.theme.StarkSignalTheme
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.WindowManager

class MainActivity : FragmentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        handleShareIntent(intent)
        lifecycleScope.launch {
            (application as StarkSignalApplication).services.settings.settings.collect { settings ->
                if (settings.appLockEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent { StarkSignalTheme { StarkSignalApp(viewModel, this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            uri?.let { viewModel.inspect(it) }
        }
    }
}
