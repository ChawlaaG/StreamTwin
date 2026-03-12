package com.streamtwin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.streamtwin.ui.navigation.NavGraph
import com.streamtwin.ui.navigation.Screen
import com.streamtwin.ui.theme.StreamTwinTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var isAuthInProgress = false

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
                    lifecycleScope.launch {
                        viewModel.authSuccess.collect { success ->
                            if (success && isAuthInProgress) {
                                isAuthInProgress = false
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Connect.route) { inclusive = true }
                                }
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
                isAuthInProgress = true
                viewModel.handleTwitchAuthToken(token)
            }
        }
    }
}
