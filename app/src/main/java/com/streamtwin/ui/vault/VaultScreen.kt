package com.streamtwin.ui.vault

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streamtwin.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onNavigateToHome: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel()
) {
    val clips by viewModel.clips.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var selectedClip by remember { mutableStateOf<ClipItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "THE VAULT",
                        fontFamily = Manrope,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        color = OnSurface,
                        letterSpacing = (-0.5).sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        bottomBar = {
            com.streamtwin.ui.components.StreamTwinBottomNav(
                currentRoute = "vault",
                onNavigate = { route ->
                    if (route == "home") onNavigateToHome()
                }
            )
        },
        containerColor = Background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Primary
                )
            } else if (clips.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Empty",
                        modifier = Modifier.size(64.dp),
                        tint = SurfaceContainerHighest
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No clips yet.",
                        fontFamily = Manrope,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = OnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Start clipping to fill your vault.",
                        fontFamily = Inter,
                        fontSize = 14.sp,
                        color = OnSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(clips) { clip ->
                        ClipCard(
                            clip = clip,
                            onClick = { selectedClip = clip }
                        )
                    }
                }
            }
        }
    }

    if (selectedClip != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedClip = null },
            sheetState = sheetState,
            containerColor = SurfaceContainerLow
        ) {
            val clip = selectedClip!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = clip.name,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = OnSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Divider(color = SurfaceContainerHighest)
                
                ListItem(
                    headlineContent = { Text("Play Video", fontFamily = Inter, color = OnSurface) },
                    leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(clip.uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(viewIntent)
                        scope.launch { sheetState.hide() }.invokeOnCompletion { selectedClip = null }
                    }
                )
                
                ListItem(
                    headlineContent = { Text("Share Clip", fontFamily = Inter, color = OnSurface) },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null, tint = LiveGreen) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, clip.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Clip"))
                        scope.launch { sheetState.hide() }.invokeOnCompletion { selectedClip = null }
                    }
                )
                
                ListItem(
                    headlineContent = { Text("Delete", fontFamily = Inter, color = ErrorRed) },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        showDeleteConfirm = true
                    }
                )
            }
        }
    }

    if (showDeleteConfirm && selectedClip != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = SurfaceContainerHigh,
            title = { Text("Delete Clip?", fontFamily = Manrope, fontWeight = FontWeight.Bold, color = OnSurface) },
            text = { Text("This will permanently delete this clip from your device.", fontFamily = Inter, color = OnSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteClip(selectedClip!!)
                        showDeleteConfirm = false
                        scope.launch { sheetState.hide() }.invokeOnCompletion { selectedClip = null }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text("Delete", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = OnSurfaceVariant, fontFamily = Inter)
                }
            }
        )
    }
}

@Composable
fun ClipCard(clip: ClipItem, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (clip.thumbnail != null) {
                Image(
                    bitmap = clip.thumbnail.asImageBitmap(),
                    contentDescription = "Thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SurfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = OnSurfaceVariant)
                }
            }
            
            // Duration Badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                val sec = (clip.durationMs / 1000) % 60
                val min = (clip.durationMs / 1000) / 60
                val durStr = String.format("%d:%02d", min, sec)
                Text(
                    text = durStr,
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Size Badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                val mb = clip.sizeBytes / (1024f * 1024f)
                Text(
                    text = String.format("%.1f MB", mb),
                    fontFamily = JetBrainsMono,
                    fontSize = 10.sp,
                    color = Color.White
                )
            }
        }
    }
}
