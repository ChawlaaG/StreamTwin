package com.streamtwin.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pedro.library.rtmp.RtmpDisplay
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages continuous rolling segment recording while streaming.
 * Records in short segments (~15s each) and keeps the last ~6 segments (~90s).
 * When a clip is requested, stitches the last ~4 completed segments (~60s)
 * into one MP4 using Android's MediaExtractor + MediaMuxer.
 */
class ClipManager(
    private val context: android.content.Context,
    private val scope: CoroutineScope
) {
    private val TAG = "ClipManager"
    private val SEGMENT_DURATION_MS = 15_000L   // 15 second segments
    private val MAX_SEGMENTS = 6                 // keep ~90s worth of segments
    private val CLIP_SEGMENTS = 4                // stitch ~60s for a clip

    private val segments = CopyOnWriteArrayList<File>()
    private var rotationJob: Job? = null
    private var isRecording = false
    private var screenCapture: RtmpDisplay? = null

    private val segmentsDir: File by lazy {
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "StreamTwinSegments").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    private val clipsDir: File by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamTwin").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    fun startContinuousRecording(capture: RtmpDisplay) {
        if (isRecording) return
        screenCapture = capture
        isRecording = true

        // Clean any leftover segments
        segmentsDir.listFiles()?.forEach { it.delete() }
        segments.clear()

        startNewSegment()
        startRotationLoop()
        Log.d(TAG, "Continuous recording started")
    }

    fun stopContinuousRecording() {
        if (!isRecording) return
        rotationJob?.cancel()
        rotationJob = null

        try {
            screenCapture?.stopRecord()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping final segment recording", e)
        }
        isRecording = false

        // Clean up segment files
        scope.launch(Dispatchers.IO) {
            delay(1000)
            segmentsDir.listFiles()?.forEach { it.delete() }
            segments.clear()
        }
        Log.d(TAG, "Continuous recording stopped")
    }

    fun saveClip(): Boolean {
        if (!isRecording) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Must be live to create a clip", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        // Rotate to a fresh segment so the current one is finalized
        try {
            screenCapture?.stopRecord()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping current segment for clip", e)
        }

        // Take a snapshot of completed segments
        val completedSegments = segments.filter { it.exists() && it.length() > 0 }
            .takeLast(CLIP_SEGMENTS)

        if (completedSegments.isEmpty()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "No clip data available yet, try again shortly", Toast.LENGTH_SHORT).show()
            }
            // Restart recording
            startNewSegment()
            return false
        }

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Saving clip...", Toast.LENGTH_SHORT).show()
        }

        // Start a new segment immediately so recording continues
        startNewSegment()

        // Stitch in background
        scope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val outputFile = File(clipsDir, "Clip_$timestamp.mp4")

                stitchSegments(completedSegments, outputFile)

                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Clip saved: ${outputFile.name}", Toast.LENGTH_LONG).show()
                }
                Log.d(TAG, "Clip saved to ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stitch clip", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to save clip", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }

    private fun startNewSegment() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val segmentFile = File(segmentsDir, "seg_$timestamp.mp4")

        try {
            screenCapture?.startRecord(segmentFile.absolutePath)
            segments.add(segmentFile)
            Log.d(TAG, "Started new segment: ${segmentFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start segment recording", e)
        }

        // Purge old segments beyond our buffer
        while (segments.size > MAX_SEGMENTS) {
            val old = segments.removeAt(0)
            scope.launch(Dispatchers.IO) {
                try {
                    delay(500)
                    if (old.exists()) old.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete old segment", e)
                }
            }
        }
    }

    private fun startRotationLoop() {
        rotationJob = scope.launch {
            while (isActive && isRecording) {
                delay(SEGMENT_DURATION_MS)
                if (!isRecording) break

                try {
                    screenCapture?.stopRecord()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping segment in rotation", e)
                }

                // Small delay for the file to finalize
                delay(300)
                startNewSegment()
            }
        }
    }

    /**
     * Stitches multiple MP4 segment files into a single output MP4
     * using Android's MediaExtractor and MediaMuxer.
     */
    private fun stitchSegments(segmentFiles: List<File>, outputFile: File) {
        // Filter out invalid/empty segments
        val validFiles = segmentFiles.filter { file ->
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                val trackCount = extractor.trackCount
                extractor.release()
                trackCount > 0
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid segment: ${file.name}", e)
                false
            }
        }

        if (validFiles.isEmpty()) {
            throw IllegalStateException("No valid segments to stitch")
        }

        // If only one valid file, just copy it
        if (validFiles.size == 1) {
            validFiles.first().copyTo(outputFile, overwrite = true)
            return
        }

        // Use the first segment to get the track formats
        val firstExtractor = MediaExtractor()
        firstExtractor.setDataSource(validFiles.first().absolutePath)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackIndexMap = mutableMapOf<Int, Int>() // source track index -> muxer track index

        for (i in 0 until firstExtractor.trackCount) {
            val format = firstExtractor.getTrackFormat(i)
            val muxerTrackIndex = muxer.addTrack(format)
            trackIndexMap[i] = muxerTrackIndex
        }

        muxer.start()
        firstExtractor.release()

        val bufferSize = 1024 * 1024 // 1MB buffer
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        var timeOffsetUs = 0L

        for (file in validFiles) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)

                var maxTimestampUs = 0L

                // Process each track in this segment
                for (trackIndex in 0 until extractor.trackCount) {
                    if (trackIndexMap.containsKey(trackIndex)) {
                        extractor.selectTrack(trackIndex)
                    }
                }

                // Read all samples and write to muxer
                var sawEOS = false
                while (!sawEOS) {
                    val trackIndex = extractor.sampleTrackIndex
                    if (trackIndex < 0) {
                        sawEOS = true
                        continue
                    }

                    val muxerTrackIdx = trackIndexMap[trackIndex]
                    if (muxerTrackIdx != null) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize > 0) {
                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize
                            bufferInfo.presentationTimeUs = extractor.sampleTime + timeOffsetUs
                            bufferInfo.flags = extractor.sampleFlags

                            muxer.writeSampleData(muxerTrackIdx, buffer, bufferInfo)

                            if (extractor.sampleTime > maxTimestampUs) {
                                maxTimestampUs = extractor.sampleTime
                            }
                        }
                    }

                    if (!extractor.advance()) {
                        sawEOS = true
                    }
                }

                // Offset the next segment's timestamps
                timeOffsetUs += maxTimestampUs + 33333 // add ~1 frame gap (30fps)
            } catch (e: Exception) {
                Log.w(TAG, "Error processing segment: ${file.name}", e)
            } finally {
                extractor.release()
            }
        }

        try {
            muxer.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping muxer", e)
        }
        muxer.release()
    }
}
