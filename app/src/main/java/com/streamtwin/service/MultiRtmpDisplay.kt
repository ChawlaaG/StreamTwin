package com.streamtwin.service

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.library.base.DisplayBase
import com.pedro.library.util.streamclient.RtmpStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer

/**
 * A custom DisplayBase subclass that sends encoded H.264 video and AAC audio
 * to a primary RTMP destination AND forwards the same packets to zero or more
 * secondary [RtmpClient] instances (e.g. YouTube, Kick).
 *
 * This avoids creating multiple MediaCodec encoders or opening the microphone
 * more than once, which is the root cause of the "mic stall" bug.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class MultiRtmpDisplay(
    context: Context,
    connectChecker: ConnectChecker
) : DisplayBase(context, true) {

    companion object {
        private const val TAG = "MultiRtmpDisplay"
    }

    // ── Primary destination (Twitch) ────────────────────────────────
    private val streamClientListener = object : StreamClientListener {
        override fun onRequestKeyframe() {
            requestKeyFrame()
        }
    }
    private val primaryClient = RtmpClient(connectChecker)
    private val streamClient = RtmpStreamClient(primaryClient, streamClientListener)

    // ── Secondary destinations (YouTube, Kick, etc.) ────────────────
    private val secondaryClients = mutableMapOf<String, RtmpClient>()

    // ── Cached SPS/PPS/VPS for secondary reconnects ─────────────────
    // The hardware encoder only emits SPS/PPS once (at start).
    // RtmpClient.disconnect(true) clears the H264Packet via reset(true),
    // so reconnecting a secondary client loses the codec config.
    // We cache the buffers here and resend on every connectSecondary().
    private var cachedSps: ByteBuffer? = null
    private var cachedPps: ByteBuffer? = null
    private var cachedVps: ByteBuffer? = null
    private var cachedSampleRate: Int = 0
    private var cachedIsStereo: Boolean = false

    /**
     * Register a secondary RTMP destination.
     * Call this BEFORE [startStream].
     *
     * @param name   identifier for this destination (e.g. "YOUTUBE")
     * @param client a pre-configured [RtmpClient] with its own [ConnectChecker]
     */
    fun addSecondaryClient(name: String, client: RtmpClient) {
        secondaryClients[name] = client
        Log.d(TAG, "Added secondary client: $name")
    }

    /**
     * Remove and disconnect a secondary destination.
     */
    fun removeSecondaryClient(name: String) {
        secondaryClients.remove(name)?.disconnect()
        Log.d(TAG, "Removed secondary client: $name")
    }

    /**
     * Connect (or reconnect) a secondary client to its RTMP URL.
     * Automatically re-sends cached SPS/PPS/VPS and audio info so the
     * server can decode the stream from any point.
     */
    fun connectSecondary(name: String, url: String) {
        val client = secondaryClients[name] ?: return
        // Mirror primary video settings to the secondary client
        if (videoEncoder.rotation == 90 || videoEncoder.rotation == 270) {
            client.setVideoResolution(videoEncoder.height, videoEncoder.width)
        } else {
            client.setVideoResolution(videoEncoder.width, videoEncoder.height)
        }
        client.setFps(videoEncoder.fps)

        // Re-send cached audio info (cleared by disconnect → rtmpSender.stop)
        if (cachedSampleRate > 0) {
            client.setAudioInfo(cachedSampleRate, cachedIsStereo)
        }
        // Re-send cached SPS/PPS/VPS (cleared by disconnect → H264Packet.reset)
        cachedSps?.let { sps ->
            client.setVideoInfo(sps.duplicate(), cachedPps?.duplicate(), cachedVps?.duplicate())
            Log.d(TAG, "$name: Re-sent cached SPS/PPS on reconnect")
        }

        client.connect(url)
        Log.d(TAG, "Connecting secondary client $name to $url")
    }

    /**
     * Re-sends cached headers to a secondary client after connection is established.
     * This is necessary because connect() may clear internal buffers.
     */
    fun syncSecondary(name: String) {
        val client = secondaryClients[name] ?: return
        Log.d(TAG, "syncSecondary: Resending headers to $name")
        if (cachedSampleRate > 0) {
            client.setAudioInfo(cachedSampleRate, cachedIsStereo)
        }
        cachedSps?.let { sps ->
            client.setVideoInfo(sps.duplicate(), cachedPps?.duplicate(), cachedVps?.duplicate())
        }
    }

    /**
     * Disconnect a secondary client without removing it from the registry.
     */
    fun disconnectSecondary(name: String) {
        secondaryClients[name]?.disconnect()
    }

    // ── DisplayBase / StreamBase overrides ───────────────────────────

    override fun setVideoCodecImp(codec: VideoCodec) {
        primaryClient.setVideoCodec(codec)
        secondaryClients.values.forEach { it.setVideoCodec(codec) }
    }

    override fun setAudioCodecImp(codec: AudioCodec) {
        primaryClient.setAudioCodec(codec)
        secondaryClients.values.forEach { it.setAudioCodec(codec) }
    }

    override fun getStreamClient(): RtmpStreamClient = streamClient

    override fun onAudioInfoImp(isStereo: Boolean, sampleRate: Int) {
        Log.d(TAG, "onAudioInfoImp: sampleRate=$sampleRate, stereo=$isStereo")
        // Cache for secondary reconnects
        cachedSampleRate = sampleRate
        cachedIsStereo = isStereo

        primaryClient.setAudioInfo(sampleRate, isStereo)
        secondaryClients.values.forEach { it.setAudioInfo(sampleRate, isStereo) }
    }

    override fun startStreamImp(url: String) {
        Log.d(TAG, "startStreamImp: $url")
        if (videoEncoder.rotation == 90 || videoEncoder.rotation == 270) {
            primaryClient.setVideoResolution(videoEncoder.height, videoEncoder.width)
        } else {
            primaryClient.setVideoResolution(videoEncoder.width, videoEncoder.height)
        }
        primaryClient.setFps(videoEncoder.fps)
        primaryClient.connect(url)
    }

    /**
     * Resends the cached SPS/PPS and audio info to the primary client.
     * This is crucial for platforms like YouTube if they connect after
     * the encoder has already started and produced the initial headers.
     */
    fun syncPrimary() {
        Log.d(TAG, "syncPrimary: Resending headers to primary client")
        cachedSps?.let { sps ->
            primaryClient.setVideoInfo(sps.duplicate(), cachedPps?.duplicate(), cachedVps?.duplicate())
        }
        if (cachedSampleRate > 0) {
            primaryClient.setAudioInfo(cachedSampleRate, cachedIsStereo)
        }
    }

    /**
     * Check if a secondary client is currently streaming.
     * Used by StreamingService to decide whether to stop the service
     * when the primary (Twitch) exhausts its retries.
     */
    fun isSecondaryStreaming(name: String): Boolean {
        return secondaryClients[name]?.isStreaming == true
    }

    override fun stopStreamImp() {
        primaryClient.disconnect()
        secondaryClients.values.forEach { runCatching { it.disconnect() } }
    }

    // ── The crucial data forwarding hooks ────────────────────────────

    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        Log.d(TAG, "onVideoInfoImp: SPS size=${sps.remaining()}, PPS size=${pps?.remaining() ?: 0}")
        // Cache deep copies for secondary reconnects
        cachedSps = deepCopy(sps)
        cachedPps = pps?.let { deepCopy(it) }
        cachedVps = vps?.let { deepCopy(it) }

        primaryClient.setVideoInfo(sps.duplicate(), pps?.duplicate(), vps?.duplicate())
        // Forward SPS/PPS/VPS to all currently-connected secondary clients
        secondaryClients.values.forEach { client ->
            runCatching {
                client.setVideoInfo(sps.duplicate(), pps?.duplicate(), vps?.duplicate())
            }
        }
    }

    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Save buffer position — sendVideo consumes it, secondaries need the same data
        val savedPos = videoBuffer.position()

        // Send to primary (Master)
        primaryClient.sendVideo(videoBuffer, info)
        
        // Log once a second to avoid spamming
        if (System.currentTimeMillis() % 1000 < 20) {
            Log.d(TAG, "Forwarding video: ${info.size} bytes to ${secondaryClients.size} secondaries")
        }

        // Forward the exact same encoded NALUs to every secondary client
        secondaryClients.forEach { (name, client) ->
            if (client.isStreaming) {
                videoBuffer.position(savedPos) // restore for each secondary
                runCatching { client.sendVideo(videoBuffer, info) }
            } else if (System.currentTimeMillis() % 1000 < 20) {
                Log.w(TAG, "Secondary $name is not streaming, skipping packet")
            }
        }
    }

    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Save buffer position — sendAudio consumes it, secondaries need the same data
        val savedPos = audioBuffer.position()

        // Send to primary
        primaryClient.sendAudio(audioBuffer, info)
        // Forward to every secondary client
        secondaryClients.forEach { (name, client) ->
            if (client.isStreaming) {
                audioBuffer.position(savedPos) // restore for each secondary
                runCatching { client.sendAudio(audioBuffer, info) }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Deep-copy a ByteBuffer so the cached version survives the original
     * being recycled by the encoder.
     */
    private fun deepCopy(src: ByteBuffer): ByteBuffer {
        val copy = ByteBuffer.allocateDirect(src.remaining())
        val pos = src.position()
        copy.put(src)
        copy.flip()
        src.position(pos) // restore original position
        return copy
    }
}
