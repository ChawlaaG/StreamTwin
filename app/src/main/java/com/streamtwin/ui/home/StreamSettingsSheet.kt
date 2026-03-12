package com.streamtwin.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamtwin.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamSettingsSheet(
    onDismissRequest: () -> Unit,
    currentQuality: String,
    onQualitySelected: (String) -> Unit,
    currentFps: Int,
    onFpsSelected: (Int) -> Unit,
    currentBitrateKbps: Int,
    onBitrateChanged: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
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
                .padding(24.dp)
        ) {
            Text(
                "Stream Settings",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge.copy(color = TextPrimary)
            )
            Spacer(modifier = Modifier.height(24.dp))

            // ── Resolution ──
            Text("Resolution", fontWeight = FontWeight.Medium, color = TextSecondary, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            val qualities = listOf("1080p", "720p", "480p")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                qualities.forEach { q ->
                    FilterChip(
                        selected = currentQuality == q,
                        onClick = { onQualitySelected(q) },
                        label = { Text(q) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TwitchPurple,
                            selectedLabelColor = PureWhite,
                            containerColor = DarkSurfaceElevated,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = BorderSubtle,
                            selectedBorderColor = TwitchPurple,
                            enabled = true,
                            selected = currentQuality == q
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── FPS ──
            Text("FPS", fontWeight = FontWeight.Medium, color = TextSecondary, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            val fpsList = listOf(60, 30)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fpsList.forEach { f ->
                    FilterChip(
                        selected = currentFps == f,
                        onClick = { onFpsSelected(f) },
                        label = { Text("${f} FPS") },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TwitchPurple,
                            selectedLabelColor = PureWhite,
                            containerColor = DarkSurfaceElevated,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = BorderSubtle,
                            selectedBorderColor = TwitchPurple,
                            enabled = true,
                            selected = currentFps == f
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Bitrate ──
            Text("Target Bitrate", fontWeight = FontWeight.Medium, color = TextSecondary, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = currentBitrateKbps.toFloat(),
                onValueChange = { onBitrateChanged(it.toInt()) },
                valueRange = 1000f..8000f,
                steps = 6,
                colors = SliderDefaults.colors(
                    thumbColor = TwitchPurple,
                    activeTrackColor = TwitchPurple,
                    inactiveTrackColor = BorderSubtle,
                    activeTickColor = TwitchPurpleLight,
                    inactiveTickColor = TextMuted
                )
            )
            Text(
                "$currentBitrateKbps kbps",
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
