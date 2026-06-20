package com.streamtwin.ui.vault

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _clips = MutableStateFlow<List<ClipItem>>(emptyList())
    val clips: StateFlow<List<ClipItem>> = _clips

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadClips()
    }

    fun loadClips() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            
            val clipList = mutableListOf<ClipItem>()
            val resolver = context.contentResolver
            val videoCollection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                android.provider.MediaStore.Video.Media._ID,
                android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                android.provider.MediaStore.Video.Media.DATA,
                android.provider.MediaStore.Video.Media.SIZE,
                android.provider.MediaStore.Video.Media.DURATION,
                android.provider.MediaStore.Video.Media.DATE_ADDED
            )

            // Select only StreamTwin clips
            val selection = "${android.provider.MediaStore.Video.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("%StreamTwin%")
            val sortOrder = "${android.provider.MediaStore.Video.Media.DATE_ADDED} DESC"

            try {
                resolver.query(videoCollection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)
                    val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.SIZE)
                    val durationCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DURATION)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "clip.mp4"
                        val data = cursor.getString(dataCol)
                        val size = cursor.getLong(sizeCol)
                        var durationMs = cursor.getLong(durationCol)
                        val uri = android.content.ContentUris.withAppendedId(videoCollection, id)
                        val file = File(data)

                        // Attempt to load thumbnail
                        var bitmap: Bitmap? = null
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            if (durationMs == 0L) {
                                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                durationMs = durStr?.toLongOrNull() ?: 0L
                            }
                            val frame = retriever.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) 
                                ?: retriever.getFrameAtTime(0)
                            
                            if (frame != null) {
                                val maxDim = 512f
                                val scale = Math.min(maxDim / frame.width, maxDim / frame.height)
                                if (scale < 1f) {
                                    val scaled = Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
                                    if (scaled != frame) {
                                        frame.recycle()
                                    }
                                    bitmap = scaled
                                } else {
                                    bitmap = frame
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            try { retriever.release() } catch (e: Exception) {}
                        }

                        clipList.add(
                            ClipItem(
                                id = id.toString(),
                                file = file,
                                name = name,
                                uri = uri,
                                sizeBytes = size,
                                durationMs = durationMs,
                                thumbnail = bitmap
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            _clips.value = clipList
            _isLoading.value = false
        }
    }

    fun deleteClip(clip: ClipItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (clip.file.exists()) {
                clip.file.delete()
                loadClips()
            }
        }
    }
}

data class ClipItem(
    val id: String,
    val file: File,
    val name: String,
    val uri: android.net.Uri,
    val sizeBytes: Long,
    val durationMs: Long,
    val thumbnail: Bitmap?
)
