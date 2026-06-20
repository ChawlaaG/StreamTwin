package com.streamtwin.data.clip

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

enum class TrackType { VIDEO, AUDIO }

data class DiskChunkRef(
    val file: File,
    val offset: Long,
    val bufferInfo: MediaCodec.BufferInfo,
    val trackType: TrackType
)

class CircularBufferSink(
    private val context: Context,
    @Volatile var durationSeconds: Int = 60
) {

    private val lock = ReentrantReadWriteLock()
    private val videoChunks = ArrayDeque<DiskChunkRef>()
    private val audioChunks = ArrayDeque<DiskChunkRef>()
    
    var videoFormat: MediaFormat? = null
    var audioFormat: MediaFormat? = null

    private val sinkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Files currently being read by a save operation — must not be deleted by pruning
    private val protectedFiles = mutableSetOf<String>()

    // Throttle the pruneOldChunks filesystem scan.
    // dir.listFiles() is a disk I/O call that warms the storage DMA bus.
    // Running it on every encoded frame (30fps × audio = ~200 calls/sec) is wasteful.
    // We only need to prune old files occasionally — once every 30 video frames (~1s)
    // and once every 100 audio chunks (~2s) is more than sufficient.
    private var videoPruneCounter = 0
    private var audioPruneCounter = 0

    // Video track file state
    private var videoFile: File? = null
    private var videoOutputStream: FileOutputStream? = null
    private var videoByteOffset = 0L
    private var videoFileStartTimeUs = -1L

    // Audio track file state — completely separate files so byte offsets never collide
    private var audioFile: File? = null
    private var audioOutputStream: FileOutputStream? = null
    private var audioByteOffset = 0L

    init {
        // Clear old artifacts natively when the sink is spawned
        File(context.cacheDir, "clip_buffers").deleteRecursively()
    }

    private fun getNextFile(prefix: String): File {
        val dir = File(context.cacheDir, "clip_buffers")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${prefix}_${System.currentTimeMillis()}.dat")
    }

    /** Only video drives file rotation so audio writes are never split mid-segment. */
    private fun rotateVideoFileIfNeeded(presentationTimeUs: Long) {
        val sizeOverflow = (videoFile?.length() ?: 0) > 50L * 1024 * 1024
        if (videoOutputStream == null || sizeOverflow || (videoFileStartTimeUs != -1L && presentationTimeUs - videoFileStartTimeUs > 15_000_000L)) {
            val oldStream = videoOutputStream
            
            // Create NEW stream first to detach state before launching background close
            videoFile = getNextFile("vid")
            videoOutputStream = FileOutputStream(videoFile, true)
            videoByteOffset = 0L
            videoFileStartTimeUs = presentationTimeUs

            sinkScope.launch {
                try {
                    oldStream?.flush()
                    (oldStream as? FileOutputStream)?.fd?.sync()
                    oldStream?.close()
                } catch (e: Exception) {
                    Log.w("CircularBufferSink", "Background video async close failed", e)
                }
            }
        }
    }

    /** Audio always appends to its own rolling file. Rotated independently so it never shares offsets with video. */
    private fun ensureAudioFile() {
        val sizeOverflow = (audioFile?.length() ?: 0) > 50L * 1024 * 1024
        if (audioOutputStream == null || sizeOverflow) {
            val oldStream = audioOutputStream

            // Create NEW stream first to detach state
            audioFile = getNextFile("aud")
            audioOutputStream = FileOutputStream(audioFile, true)
            audioByteOffset = 0L

            sinkScope.launch {
                try {
                    oldStream?.flush()
                    (oldStream as? FileOutputStream)?.fd?.sync()
                    oldStream?.close()
                } catch (e: Exception) {
                    Log.w("CircularBufferSink", "Background audio async close failed", e)
                    try { oldStream?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    fun onEncodedVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(bytes, 0, info.size)

        val clonedInfo = MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs, info.flags)
        }

        lock.writeLock().lock()
        try {
            rotateVideoFileIfNeeded(info.presentationTimeUs)
            val file = videoFile ?: return
            val out = videoOutputStream ?: return

            val offset = videoByteOffset
            out.write(bytes)
            // No flush() here — flushing per-frame (60x/s) issues a syscall each time
            // and keeps the I/O bus unnecessarily busy, contributing to device heating.
            // The explicit flushBuffers() call before snapshot() is the only place we
            // need kernel-level flush (once per clip save).
            videoByteOffset += bytes.size

            videoChunks.addLast(DiskChunkRef(file, offset, clonedInfo, TrackType.VIDEO))
            // Only prune once every 30 video frames (~1s at 30fps) instead of every frame.
            // pruneOldChunks calls dir.listFiles() which is a filesystem scan — doing it
            // 30x/sec keeps the storage DMA bus busy and contributes to device heating.
            if (++videoPruneCounter >= 30) {
                videoPruneCounter = 0
                pruneOldChunks()
            }
        } catch (e: Exception) {
            Log.e("CircularBufferSink", "Error writing video chunk to disk", e)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun onEncodedAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(bytes, 0, info.size)

        val clonedInfo = MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs, info.flags)
        }

        lock.writeLock().lock()
        try {
            ensureAudioFile()
            val file = audioFile ?: return
            val out = audioOutputStream ?: return

            val offset = audioByteOffset
            out.write(bytes)
            // No flush() here — same reasoning as onEncodedVideo above.
            audioByteOffset += bytes.size

            audioChunks.addLast(DiskChunkRef(file, offset, clonedInfo, TrackType.AUDIO))
            // Only prune once every 100 audio chunks (~2s) instead of every chunk.
            if (++audioPruneCounter >= 100) {
                audioPruneCounter = 0
                pruneOldChunks()
            }
        } catch (e: Exception) {
            Log.e("CircularBufferSink", "Error writing audio chunk to disk", e)
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun pruneOldChunks() {
        val latestVideoTime = videoChunks.lastOrNull()?.bufferInfo?.presentationTimeUs ?: return
        // Keep an extra 5 seconds of footage in the buffer so we always have a keyframe slightly older than the requested duration
        val cutoffTime = latestVideoTime - ((durationSeconds + 5) * 1_000_000L)

        while (videoChunks.isNotEmpty() && videoChunks.first().bufferInfo.presentationTimeUs < cutoffTime) {
            videoChunks.removeFirst()
        }
        while (audioChunks.isNotEmpty() && audioChunks.first().bufferInfo.presentationTimeUs < cutoffTime) {
            audioChunks.removeFirst()
        }

        // Garbage-collect chunk files that no longer have any chunk references
        val activeFiles = mutableSetOf<String>()
        videoChunks.forEach { activeFiles.add(it.file.absolutePath) }
        audioChunks.forEach { activeFiles.add(it.file.absolutePath) }
        activeFiles.add(videoFile?.absolutePath ?: "")
        activeFiles.add(audioFile?.absolutePath ?: "")

        val dir = File(context.cacheDir, "clip_buffers")
        dir.listFiles()?.forEach { file ->
            if (!activeFiles.contains(file.absolutePath) && !protectedFiles.contains(file.absolutePath)) {
                file.delete()
            }
        }
    }

    /** Flush both open file streams so any in-kernel buffer bytes are visible to RandomAccessFile readers. */
    fun flushBuffers() {
        lock.writeLock().lock()
        try {
            try { videoOutputStream?.flush() } catch (e: Exception) { Log.w("CircularBufferSink", "video flush failed", e) }
            try { audioOutputStream?.flush() } catch (e: Exception) { Log.w("CircularBufferSink", "audio flush failed", e) }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun snapshot(): Pair<List<DiskChunkRef>, List<DiskChunkRef>> {
        lock.readLock().lock()
        return try {
            Pair(videoChunks.toList(), audioChunks.toList())
        } finally {
            lock.readLock().unlock()
        }
    }

    /**
     * Takes a snapshot AND marks all referenced files as protected from pruning.
     * Must be paired with [releaseProtectedFiles] after the save operation completes.
     */
    fun snapshotProtected(): Pair<List<DiskChunkRef>, List<DiskChunkRef>> {
        lock.writeLock().lock()
        return try {
            val vSnap = videoChunks.toList()
            val aSnap = audioChunks.toList()
            // Protect every file referenced by the snapshot from being garbage-collected
            vSnap.forEach { protectedFiles.add(it.file.absolutePath) }
            aSnap.forEach { protectedFiles.add(it.file.absolutePath) }
            Pair(vSnap, aSnap)
        } finally {
            lock.writeLock().unlock()
        }
    }

    /** Release file protection after a save operation completes. */
    fun releaseProtectedFiles() {
        lock.writeLock().lock()
        try {
            protectedFiles.clear()
        } finally {
            lock.writeLock().unlock()
        }
    }

    /** Clears the in-memory chunk lists and file pointers. Used when orientation changes. */
    fun clearAll() {
        lock.writeLock().lock()
        try {
            videoChunks.clear()
            audioChunks.clear()
            videoFormat = null
            audioFormat = null

            // Close current streams to force fresh files for the new orientation
            try { videoOutputStream?.close() } catch (e: Exception) {}
            videoOutputStream = null
            videoFile = null
            videoFileStartTimeUs = -1L
            videoByteOffset = 0L

            try { audioOutputStream?.close() } catch (e: Exception) {}
            audioOutputStream = null
            audioFile = null
            audioByteOffset = 0L

            Log.d("CircularBufferSink", "Buffer and file state fully reset for orientation change")
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun clear() {
        lock.writeLock().lock()
        try {
            videoChunks.clear()
            audioChunks.clear()
            protectedFiles.clear()
            videoOutputStream?.close()
            videoOutputStream = null
            audioOutputStream?.close()
            audioOutputStream = null
            File(context.cacheDir, "clip_buffers").deleteRecursively()
        } catch (e: Exception) {
            Log.e("CircularBufferSink", "Error clearing buffers", e)
        } finally {
            lock.writeLock().unlock()
        }
    }
}
