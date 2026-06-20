package com.streamtwin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.streamtwin.ui.navigation.NavGraph
import com.streamtwin.ui.navigation.Screen
import com.streamtwin.ui.theme.StreamTwinTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        handleIntent(intent)
        
        setContent {
            StreamTwinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    // Observe auth success to navigate to home
                    LaunchedEffect(Unit) {
                        viewModel.authSuccess.collect { success ->
                            if (success) {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Connect.route) { inclusive = true }
                                }
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        viewModel.startModeRequests.collect { mode ->
                            navController.navigate(Screen.Home.route + "?startMode=$mode") {
                                launchSingleTop = true
                            }
                        }
                    }

                    NavGraph(navController = navController)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_CLIP_MODE) {
            viewModel.requestStartMode("CLIPPING_AUTO")
            return
        }

        val uri = intent?.data ?: return
        
        if (uri.scheme == "https" && uri.host == "localhost") {
            // Implicit grant flow appends tokens as fragment, not query parameter
            // e.g. https://localhost/streamtwin/callback#access_token=foo...
            val fragment = uri.fragment ?: return
            
            // Parse fragment for access_token=...
            val params = fragment.split("&").mapNotNull {
                val parts = it.split("=")
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            
            val token = params["access_token"]
            if (token != null) {
                viewModel.handleTwitchAuthToken(token)
            }
        }
    }

    companion object {
        const val ACTION_START_CLIP_MODE = "com.streamtwin.action.START_CLIP_MODE"
    }
}
