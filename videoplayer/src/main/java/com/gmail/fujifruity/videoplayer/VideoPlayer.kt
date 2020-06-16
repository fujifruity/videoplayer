package com.gmail.fujifruity.videoplayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Surface
import java.util.concurrent.SynchronousQueue
import kotlin.math.absoluteValue

fun logv(tag: String, lazyMsg: () -> String) {
    if (BuildConfig.DEBUG) Log.v(tag, lazyMsg())
}

fun logd(tag: String, lazyMsg: () -> String) {
    if (BuildConfig.DEBUG) Log.d(tag, lazyMsg())
}

/**
 * Simple video player with MediaCodec async mode.
 * When an object is no longer being used, call [close] to free up resources.
 * ```
 * Player(context, surface).also {
 *     it.play(videoUrl)
 *     Thread.sleep(3000)
 *     it.playbackSpeed = 2.0
 *     Thread.sleep(3000)
 *     it.seekTo(7000)
 *     Thread.sleep(3000)
 *     it.close()
 * }
 * ```
 */
class VideoPlayer(private val context: Context, private val surface: Surface) {
    val durationMs: Long
        get() = session!!.durationUs / 1000
    val videoUri: Uri
        get() = session!!.videoUri
    val currentPositionMs: Long
        get() = session!!.currentPositionMs
    var playbackSpeed: Double
        get() = session!!.playbackSpeed
        set(value) {
            session!!.playbackSpeed = value
        }
    var lastNonZeroPlaybackSpeed = 1.0
    private var session: PlaybackSession? = null
    private val playbackThread = HandlerThread("playbackThread").also { it.start() }
    private val playbackThreadHandler = Handler(playbackThread.looper)

    /**
     * @param async if `false` (default) it blocks current thread until playback starts.
     */
    fun play(videoUri: Uri, startingPositionMs: Long = 0, async: Boolean = false) {
        session?.also { it.close() }
        playbackThreadHandler.post {
            session = PlaybackSession(
                videoUri,
                startingPositionMs,
                context,
                surface,
                playbackThread.looper
            )
        }
        if (async) return
        // block caller thread until above posting is consumed
        val queue = SynchronousQueue<Unit>()
        playbackThreadHandler.post { queue.put(Unit) }
        queue.take()
    }

    fun close() {
        session?.close()
        // TODO: quit safely to make sure that the decoder stopped
        playbackThread.quit()
    }

    val isPlaying: Boolean
        get() = session != null && playbackSpeed != 0.0

    fun togglePause() {
        if (playbackSpeed != 0.0) {
            lastNonZeroPlaybackSpeed = playbackSpeed
            playbackSpeed = 0.0
        } else {
            playbackSpeed = lastNonZeroPlaybackSpeed
        }
    }

    fun seekTo(positionMs: Long, mode: Int = MediaExtractor.SEEK_TO_PREVIOUS_SYNC) {
        session!!.seekTo(positionMs, mode)
    }
}

/**
 * Represents playback of video specified by [videoUri]. Immediately starts playback on instantiation.
 */
class PlaybackSession(
    val videoUri: Uri,
    startingPositionMs: Long = 0,
    private val context: Context,
    private val surface: Surface,
    looper: Looper
) {
    /** The position of the latest rendered frame. */
    var currentPositionMs: Long = 0L
        private set

    /** "renderingTime" is defined in [handler]'s postBufferRelease method. */
    private var lastAcceptedRenderingTimeUs = 0L
    private var intermissionUs = 30_000L
    private var hasFormatChanged = false
    private var hasFirstKeyframeProcessed = false
    private var firstKeyframeTimestampUs = 0L
    private var isCaughtUp = true
    private var pendingSeekRequest: Runnable? = null

    /** Elapsed real-time since system boot. */
    private fun ertNs() = System.nanoTime()

    /** The last elapsed real-time at which [playbackSpeed] is changed, call [seekTo], or instantiate. */
    private var startingErtNs = 0L

    /** The last position at which [playbackSpeed] is changed or start playback. */
    private var startingPositionUs = 0L

    private val expectedPositionUs: Long
        get() {
            val ertSinceStartUs = (ertNs() - startingErtNs) / 1000
            val predictedPosition = startingPositionUs + (ertSinceStartUs * playbackSpeed).toLong()
            return predictedPosition.coerceAtMost(durationUs)
        }

    private val handler = object : Handler(looper) {
        /**
         * Buffer release messages that are pending will go invalid when [playbackSpeed] is changed or
         * [seekTo] is called. While [seekTo] only requires to remove all messages, [playbackSpeed]'s
         * setter has to recreate messages and send them again so as not to skip any frame.
         * Since there is probably no way to peek messages ([Looper.dump] does, but its format is not
         * reliable), the recreation needs all pending messages' properties.
         * - who adds an element: [sendReleaseMessage]
         * - who removes an element: [handler]'s onMessage callback
         * - who removes all elements: [seekTo] and [playbackSpeed]'s setter
         */
        val queuedReleaseProperties = mutableListOf<ReleaseProperty>()
        private val WHAT_RELEASE_BUFFER = 1001
        private val ONE_HOUR_US: Long = 3600_000_000
        private val ONE_HOUR_MS: Long = 3600_000

        inner class ReleaseProperty(
            val outputBufferId: Int,
            val presentationTimeUs: Long,
            val hasToRender: Boolean
        ) {
            // inner class cannot be data class
            operator fun component1() = outputBufferId
            operator fun component2() = presentationTimeUs
            operator fun component3() = hasToRender
            override fun toString() =
                "ReleaseProperty($outputBufferId, ${presentationTimeUs / 1000}ms, $hasToRender)"
        }

        override fun handleMessage(msg: Message) {
            if (msg.what != WHAT_RELEASE_BUFFER) return
            val property = msg.obj as ReleaseProperty
            queuedReleaseProperties.remove(property)
            val (bufId, presentationTimeUs, hasToRender) = property
            val presentationTimeMs = presentationTimeUs / 1000
            if (hasToRender) currentPositionMs = presentationTimeMs
            logv(TAG) { "outputBuffer$bufId ($presentationTimeMs) release, exp=${expectedPositionUs / 1000}, cur=${currentPositionMs}${if (hasToRender) ", rendered" else ""}" }
            // simultaneously renders the buffer onto the surface.
            decoder.releaseOutputBuffer(bufId, hasToRender)
        }

        private fun sendReleaseMessage(prop: ReleaseProperty, timeoutMs: Long, tag: String) {
            val msg = obtainMessage(WHAT_RELEASE_BUFFER, prop)
            sendMessageDelayed(msg, timeoutMs)
            queuedReleaseProperties.add(prop)
            val (outputBufferId, presentationTimeUs, hasToRender) = prop
            logv(TAG) { "outputBuffer$outputBufferId (${presentationTimeUs / 1000}) will be ${if (hasToRender) "rendered" else "released"} in ${timeoutMs}ms ($tag)" }
        }

        /** Schedules releasing output buffer after calculating timeout. */
        fun postBufferRelease(outputBufferId: Int, presentationTimeUs: Long, tag: String) {
            val (timeoutMs, hasToRender) = run {
                if (pendingSeekRequest != null) {
                    // prevents another oOBA callback from outliving flush()
                    logv(TAG) { "seek request exists; suspend buffer release" }
                    return@run ONE_HOUR_MS to false
                }
                val expectedPositionUs = expectedPositionUs
                if (!isCaughtUp && presentationTimeUs >= expectedPositionUs) {
                    // when perform seek with very small playbackSpeed, we want to immediately
                    // render next frame no matter how long the rendering timeout is.
                    logv(TAG) { "playback caught up" }
                    isCaughtUp = true
                    return@run 0L to true
                }
                val timeoutCandidateUs =
                    ((presentationTimeUs - expectedPositionUs) / playbackSpeed).toLong()
                        .coerceAtMost(ONE_HOUR_US)
                if (timeoutCandidateUs < 0) return@run 0L to false
                // tricky definition: position + real time
                val renderingTimeUs = expectedPositionUs + timeoutCandidateUs
                val isWellSeparated =
                    (lastAcceptedRenderingTimeUs - renderingTimeUs).absoluteValue >= intermissionUs
                if (isWellSeparated) {
                    lastAcceptedRenderingTimeUs = renderingTimeUs
                    timeoutCandidateUs / 1000 to true
                } else 0L to false
            }
            sendReleaseMessage(
                ReleaseProperty(outputBufferId, presentationTimeUs, hasToRender),
                timeoutMs,
                tag
            )
            /*
        .startingPosition  \\          .expectedPosition->      presentationTime
           x1.0 | x2.0     //                  |                      |
        -------------------\\--------------------------------------------> position
                | <--2.0 * ertSinceSpeedSet--> | <---2.0 * timeout--> |
                |          \\                  |                      |

        .expectedPosition :=  .startingPosition + 2.0 * ertSinceSpeedSet
                  timeout := (presentationTime - .expectedPosition) / 2.0

                |        |       |             |<-intermission->|
              --|--------|-------|-------------|---------+------+---+---> time
                |<----------timeout0---------->|         |          |
                |        |<----------timeout1----------->|          |
                +        |       |<-------------timeout2----------->|
        expectedPosition0 +      |             +         |          |
               expectedPosition1 +      renderingTime0   +          |
                        expectedPosition2          renderingTime1   +
                                                             renderingTime2
        renderingTime_n := expectedPosition_n + timeout_n
          hasToRender_n := timeout_n > 0 && |renderingTime_n - lastAcceptedRenderingTime| > intermission
            return value = hasToRender_n ? timeout_n : 0

        Caller1 should not render the buffer it holds because renderingTime1 is too close to renderingTime0.
        Return values for these callers (onOutputBufferAvailable or playbackSpeed) will be:
        caller0: timeout0
        caller1: 0
        caller2: timeout2
             */
        }

        fun cancelBufferRelease() {
            removeMessages(WHAT_RELEASE_BUFFER)
//            val msg=queuedReleaseProperties.map{ "${it.outputBufferId}(${it.presentationTimeUs/1000})"  }
//            Log.d(TAG, "buffers to be released=$msg")
            queuedReleaseProperties.clear()
        }

        fun updateBufferRelease(tag: String) {
            val props = queuedReleaseProperties.toList()
            cancelBufferRelease()
            props.forEach { (bufId, presentationTimeUs, _) ->
                postBufferRelease(bufId, presentationTimeUs, tag)
            }
        }

        fun postPendingSeekRequest() {
            // TODO:  short delay causes crash
            postDelayed(pendingSeekRequest!!, 20)
        }
    }

    private val decoderCallback: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(decoder: MediaCodec, bufferId: Int) {
            val bufIdWithTimestamp = "inputBuffer$bufferId (${extractor.sampleTime / 1000})"
            logv(TAG) { "$bufIdWithTimestamp available" }
            val inputBuffer = decoder.getInputBuffer(bufferId)!!
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize == -1) {
                logd(TAG) { "$bufIdWithTimestamp BUFFER_FLAG_END_OF_STREAM" }
                decoder.queueInputBuffer(bufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                decoder.queueInputBuffer(bufferId, 0, sampleSize, extractor.sampleTime, 0)
                extractor.advance()
            }
        }

        override fun onOutputBufferAvailable(
            decoder: MediaCodec,
            bufferId: Int,
            bufferInfo: MediaCodec.BufferInfo
        ) {
            val timestampUs = bufferInfo.presentationTimeUs
            val bufIdWithTimestamp =
                "outputBuffer$bufferId (${timestampUs / 1000})"
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                logd(TAG) { "$bufIdWithTimestamp BUFFER_FLAG_END_OF_STREAM" }
            } else if (!hasFirstKeyframeProcessed) {
                if (timestampUs == firstKeyframeTimestampUs) {
                    logv(TAG) { "$bufIdWithTimestamp has the first keyframe" }
                    hasFirstKeyframeProcessed = true
                    handler.postBufferRelease(bufferId, timestampUs, "oOBA")
                } else {
                    logv(TAG) { "$bufIdWithTimestamp is not the first keyframe, skipped" }
                }
            } else {
                handler.postBufferRelease(bufferId, timestampUs, "oOBA")
            }
        }

        override fun onOutputFormatChanged(decoder: MediaCodec, format: MediaFormat) {
            logd(TAG) { "onOutputFormatChanged, format: $format" }
            hasFormatChanged = true
            pendingSeekRequest?.also {
                handler.postPendingSeekRequest()
            }
        }

        override fun onError(decoder: MediaCodec, exception: MediaCodec.CodecException) {
            Log.e(TAG, "onError", exception)
        }
    }

    private val pfd = context.contentResolver.openFileDescriptor(videoUri, "r")!!

    private val extractor = MediaExtractor().apply {
        Log.i(TAG, "creating extractor on $videoUri")
        setDataSource(pfd.fileDescriptor)
    }

    private val decoder: MediaCodec = run {
        Log.i(TAG, "creating decoder")
        val (trackId, format, mime) =
            0.until(extractor.trackCount).map { trackId ->
                val format = extractor.getTrackFormat(trackId)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                Triple(trackId, format, mime)
            }.find { (_, _, mime) ->
                mime.startsWith("video/")
            }!!
        // Configure extractor with video track.
        extractor.selectTrack(trackId)
        MediaCodec.createDecoderByType(mime).apply {
            setCallback(decoderCallback)
            configure(format, surface, null, 0)
        }
    }

    /**
     * Free up all resources. [PlaybackSession] instance cannot be used no more.
     */
    fun close() {
        // TODO: postAtFront
        handler.post {
            handler.cancelBufferRelease()
            Log.i(TAG, "releasing extractor and decoder")
            decoder.stop()
            decoder.release()
            extractor.release()
            pfd.close()
        }
    }

    /**
     * - Note: actual playback speed depends on low-level implementations. Maybe 0x to 6x is manageable.
     * - Known issue: setting value right after seekTo() will cause crash.
     */
    var playbackSpeed: Double = 1.0
        set(value) {
            if (value == playbackSpeed) return
            handler.post {
                Log.i(TAG, "set playback speed x$value")
                // used to calculate buffer release timeout
                startingPositionUs = expectedPositionUs
                startingErtNs = ertNs()
                field = value
                // prevents over-paced rendering
                intermissionUs = (30_000 * playbackSpeed).toLong()
                logv(TAG) { "intermissionMs=${intermissionUs / 1000}" }
                // scheduled buffer releases are invalid now, reschedule.
                handler.updateBufferRelease("spd setter")
            }
        }

    /**
     * @param seekMode:
     *  - [MediaExtractor.SEEK_TO_CLOSEST_SYNC]
     *  - [MediaExtractor.SEEK_TO_NEXT_SYNC]
     *  - [MediaExtractor.SEEK_TO_PREVIOUS_SYNC] (default)
     */
    fun seekTo(positionMs: Long, seekMode: Int = MediaExtractor.SEEK_TO_PREVIOUS_SYNC) {
        // while delaying execution of seek, any post of releaseOutputBuffer will be suspended
        // by our handler; we can safely call flush() without outliving onOutputBufferAvailable (cause of crash)
        pendingSeekRequest = Runnable {
            val positionUs = (positionMs * 1000).coerceIn(0, durationUs)
            Log.i(TAG, "seek to ${positionMs}ms (actual: ${positionUs / 1000}ms)")
            startingPositionUs = positionUs
            startingErtNs = ertNs()
            lastAcceptedRenderingTimeUs = 0L
            isCaughtUp = false
            hasFirstKeyframeProcessed = false
            handler.cancelBufferRelease()
//            handler.looper.dump({ s -> logd(TAG, s) }, ">")
            val seekMode = if (positionUs == 0L) MediaExtractor.SEEK_TO_CLOSEST_SYNC else seekMode
            extractor.seekTo(positionUs, seekMode)
            firstKeyframeTimestampUs = extractor.sampleTime
            decoder.flush()
            decoder.start()
            pendingSeekRequest = null
        }
        // stalls decoder by suspending buffer release
        handler.updateBufferRelease("seekTo")
        if (hasFormatChanged) {
            logv(TAG) { "post seek request" }
            handler.postPendingSeekRequest()
        } else {
            logv(TAG) { "decoder has not recieved format change; suspend seek request" }
        }
    }

    /**
     * Accurate video duration extracted via MediaExtractor.
     * Note: media metadata duration ([MediaMetadataRetriever.METADATA_KEY_DURATION])
     * may not be equal to the timestamp of the last sample.
     */
    var durationUs = run {
        Log.i(TAG, "extracting duration of the video")
        var sampleTimeUs = 0L
        extractor.seekTo(Long.MAX_VALUE, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (extractor.sampleTime != -1L) {
            sampleTimeUs = extractor.sampleTime
            extractor.advance()
        }
        sampleTimeUs
    }

    private fun retrieveDurationUs(): Long {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val durationMsStr =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
        retriever.release()
        return durationMsStr.toLong() * 1000
    }

    init {
        startingPositionUs = (startingPositionMs * 1000).coerceIn(0, durationUs)
        currentPositionMs = startingPositionUs / 1000
        // TODO: cannot seek to 0s (always seeks to around 1s)
        extractor.seekTo(startingPositionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        firstKeyframeTimestampUs = extractor.sampleTime
        startingErtNs = ertNs()
        decoder.start()
        Log.i(TAG, "start")
    }

    companion object {
        private val TAG = PlaybackSession::class.java.simpleName
    }
}

