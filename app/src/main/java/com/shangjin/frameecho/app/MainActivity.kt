package com.shangjin.frameecho.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shangjin.frameecho.app.incoming.IncomingMediaViewModel
import com.shangjin.frameecho.app.intents.MediaShareIntents
import com.shangjin.frameecho.app.ui.FrameEchoNavHost
import com.shangjin.frameecho.app.ui.theme.FrameEchoTheme

/**
 * Main entry point for FrameEcho. Also receives files shared with it (SEND,
 * SEND_MULTIPLE) or opened in it (VIEW), on launch and, being singleTask, while running.
 */
class MainActivity : ComponentActivity() {

    private val incoming: IncomingMediaViewModel by viewModels {
        viewModelFactory { initializer { IncomingMediaViewModel.create(applicationContext) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A restored activity already handled its launch intent before it was recreated.
        val openedWithMedia = savedInstanceState == null && receive(intent)
        addOnNewIntentListener { receive(it) }
        setContent {
            FrameEchoTheme {
                FrameEchoNavHost(incoming = incoming, openedWithMedia = openedWithMedia)
            }
        }
    }

    /** Returns whether the intent carried any files. */
    private fun receive(intent: Intent?): Boolean {
        intent ?: return false
        val uris = MediaShareIntents.readUris(intent)
        incoming.receive(
            uris = uris,
            intentType = intent.type,
            persistable = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        )
        return uris.isNotEmpty()
    }
}
