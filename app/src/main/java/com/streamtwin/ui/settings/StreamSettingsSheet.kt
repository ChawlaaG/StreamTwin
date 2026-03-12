package com.streamtwin.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streamtwin.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamSettingsSheet(
    onDismiss: () -> Unit,
    viewModel: StreamSettingsViewModel = hiltViewModel()
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceCard,
        scrimColor = DarkBackground.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp),
                color = BorderSubtle,
                shape = RoundedCornerShape(3.dp)
            ) {
                Spacer(modifier = Modifier.size(width = 40.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                "Stream Quality",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TwitchPurpleLight
            )
            Spacer(modifier = Modifier.height(16.dp))

            SettingsRow(label = "Resolution", value = "720p") {
                viewModel.updateStreamQuality("720p")
            }
            SettingsRow(label = "Video quality", value = "Adaptive") {}
            SettingsRow(label = "Bitrate", value = "2500 kbps") {}
            SettingsRow(label = "Frame rate", value = "30 fps") {}
            SettingsRow(label = "Keyframe interval", value = "2s") {}

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Screen Settings",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TwitchPurpleLight
            )
            Spacer(modifier = Modifier.height(16.dp))

            SettingsRow(label = "Aspect ratio", value = "16:9") {}

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = TextPrimary)
        Text(value, fontSize = 15.sp, color = TextMuted, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = DividerDark, thickness = 0.5.dp)
}
