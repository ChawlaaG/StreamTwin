package com.streamtwin.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import com.streamtwin.ui.theme.*

@Composable
fun StreamTwinBottomNav(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        containerColor = Background.copy(alpha = 0.8f),
        contentColor = OnSurfaceVariant,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets.navigationBars
    ) {
        NavigationBarItem(
            selected = currentRoute == "home",
            onClick = { onNavigate("home") },
            icon = { Icon(Icons.Filled.Sensors, contentDescription = "Studio") },
            label = {
                Text(
                    "STUDIO",
                    fontFamily = Inter,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.05.em
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Primary,
                selectedTextColor = Primary,
                indicatorColor = SurfaceContainerHigh,
                unselectedIconColor = OnSurfaceVariant.copy(alpha = 0.6f),
                unselectedTextColor = OnSurfaceVariant.copy(alpha = 0.6f)
            )
        )

        NavigationBarItem(
            selected = currentRoute == "vault",
            onClick = { onNavigate("vault") },
            icon = { Icon(Icons.Filled.GridView, contentDescription = "Vault") },
            label = {
                Text(
                    "VAULT",
                    fontFamily = Inter,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.05.em
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Primary,
                selectedTextColor = Primary,
                indicatorColor = SurfaceContainerHigh,
                unselectedIconColor = OnSurfaceVariant.copy(alpha = 0.6f),
                unselectedTextColor = OnSurfaceVariant.copy(alpha = 0.6f)
            )
        )

        NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = { onNavigate("settings") },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
            label = {
                Text(
                    "SETTINGS",
                    fontFamily = Inter,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.05.em
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Primary,
                selectedTextColor = Primary,
                indicatorColor = SurfaceContainerHigh,
                unselectedIconColor = OnSurfaceVariant.copy(alpha = 0.6f),
                unselectedTextColor = OnSurfaceVariant.copy(alpha = 0.6f)
            )
        )
    }
}
