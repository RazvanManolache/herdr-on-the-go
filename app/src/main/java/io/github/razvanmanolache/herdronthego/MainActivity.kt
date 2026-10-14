package io.github.razvanmanolache.herdronthego

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider

class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    val controller = BridgeController(application)
    override fun onCleared() { controller.shutdown() }
}

class MainActivity : ComponentActivity() {
    private lateinit var controller: BridgeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NOTE: targetSdk 35 enforces edge-to-edge; declare it so the
        // system dispatches window insets, which the screens below pad.
        enableEdgeToEdge()
        controller = ViewModelProvider(this)[ControllerViewModel::class.java].controller
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent { HerdrOnTheGoApp(controller) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // NOTE: singleTop delivers notification taps here while the app
        // is already open instead of stacking a second activity.
        handleDeepLink(intent)
    }

    override fun onPause() {
        super.onPause()
        if (::controller.isInitialized) controller.appPaused()
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) {
            controller.appResumed()
            controller.resumeConnections()
        }
    }

    /** Notification tap: land on the finished tab, reconnecting if needed. */
    private fun handleDeepLink(intent: Intent?) {
        val key = intent?.getStringExtra(AgentNotif.EXTRA_SERVER) ?: return
        val pane = intent.getStringExtra(AgentNotif.EXTRA_PANE) ?: return
        val tab = intent.getStringExtra(AgentNotif.EXTRA_TAB) ?: return
        controller.deepLink(key, pane, tab, intent.getStringExtra(AgentNotif.EXTRA_SESSION))
    }
}
