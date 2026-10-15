package com.lifedeck.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lifedeck.app.ui.LifeDeckRoot

/** Single-activity host for the Compose UI. */
class MainActivity : ComponentActivity() {

    data class DeepLink(val taskId: String? = null, val screen: String? = null, val sharedText: String? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        publishDeepLink(intent)
        val sharedText = intent?.getStringExtra(IntentExtra.SHARE_TEXT)
        setContent {
            LifeDeckRoot(initialSharedText = sharedText)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishDeepLink(intent)
    }

    private fun publishDeepLink(intent: android.content.Intent?) {
        if (intent == null) return
        val taskId = intent.getStringExtra(IntentExtra.OPEN_TASK_ID)
        val screen = intent.getStringExtra(IntentExtra.OPEN_SCREEN)
        val share = intent.getStringExtra(IntentExtra.SHARE_TEXT)
        if (taskId != null || screen != null || share != null) {
            deepLinks.tryEmit(DeepLink(taskId = taskId, screen = screen, sharedText = share))
        }
    }

    object IntentExtra {
        const val SHARE_TEXT = "android.intent.extra.TEXT"
        const val OPEN_TASK_ID = "com.lifedeck.app.OPEN_TASK_ID"
        const val OPEN_SCREEN = "com.lifedeck.app.OPEN_SCREEN"
    }

    companion object {
        /** Handed to the UI shell; carries notification/share deep links. */
        val deepLinks = kotlinx.coroutines.flow.MutableSharedFlow<DeepLink>(replay = 1, extraBufferCapacity = 4)
    }
}
