package com.navercorp.deview.mediaplayer

import android.graphics.SurfaceTexture
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.*
import kotlin.math.max
import kotlin.math.min

// http://distribution.bbb3d.renderfarming.net/video/mp4/bbb_sunflower_1080p_30fps_normal.mp4
private val MEDIA_FILE = "bbb_sunflower_1080p_30fps_normal.mp4"
private val TIMEOUT_US = 10_000L

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var view: AutoFitTextureView

    private val _surfaceLatch = CountDownLatch(1)
    private var _surface: Surface? = null
    private val surface: Surface
        get() {
            _surfaceLatch.await()
            return _surface!!
        }

    private var avPlayer: AVPlayer? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        view = textureView
        view.setAspectRatio(16, 9)
        view.surfaceTextureListener = this

        playOrPause.setOnClickListener {
            playOrStop()
        }

        seekBackward.setOnClickListener {
            avPlayer?.let {
                val position = it.position
                if (position >= 0) {
                    it.seekTo(position - 15_000, AVPlayer.SeekMode.PREVIOUS_SYNC)
                }
            }
//            mediaPlayer?.let {
//                val position = it.currentPosition
//                it.seekTo(position - 15_000)
//            }
        }

        seekForward.setOnClickListener {
            avPlayer?.let {
                val position = it.position
                if (position >= 0) {
                    it.seekTo(position + 15_000, AVPlayer.SeekMode.PREVIOUS_SYNC)
                }
            }
//            mediaPlayer?.let {
//                val position = it.currentPosition
//                it.seekTo(position + 15_000)
//            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        _surface = Surface(surface)
        _surfaceLatch.countDown()
    }

    private fun playOrStop() {
        if (avPlayer == null) {
            avPlayer = AVPlayer({
                assets.openFd(MEDIA_FILE).use {
                    MediaExtractor().apply { setDataSource(it) }
                }
            }, surface)

            avPlayer?.play()
        } else {
            //avPlayer?.stop()
            avPlayer?.release()
            avPlayer = null
        }
//        if (mediaPlayer == null) {
//            mediaPlayer = MediaPlayer().apply {
//                assets.openFd(MEDIA_FILE).use { setDataSource(it) }
//                setSurface(surface)
//                prepare()
//            }
//            mediaPlayer?.start()
//        } else {
//            mediaPlayer?.release()
//            mediaPlayer = null
//        }
    }

}

class AVPlayer(extractorSupplier: () -> MediaExtractor, surface: Surface) {

    enum class SeekMode {
        PREVIOUS_SYNC,
        NEXT_SYNC
    }

    private val audioExtractor: MediaExtractor
    private val videoExtractor: MediaExtractor
    private val audioTrackIndex: Int
    private val videoTrackIndex: Int
    private val videoDecoder: MediaCodec
    private val audioDecoder: MediaCodec
    private val audioTrack: AudioTrack
    private var audioInEos = false
    private var audioOutEos = false
    private var videoInEos = false
    private var videoOutEos = false
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private val videoBufferInfo = MediaCodec.BufferInfo()

    init {
        val extractor = extractorSupplier()
        val audioTrackIndex = extractor.firstAudioTrack
        val videoTrackIndex = extractor.firstVideoTrack
        extractor.release()

        if (audioTrackIndex == null || videoTrackIndex == null) {
            error("We need both audio and video")
        }

        audioExtractor = extractorSupplier().apply { selectTrack(audioTrackIndex) }
        videoExtractor = extractorSupplier().apply { selectTrack(videoTrackIndex) }
        this.audioTrackIndex = audioTrackIndex
        this.videoTrackIndex = videoTrackIndex

        audioDecoder = createDecoder(audioExtractor, audioTrackIndex)
        videoDecoder = createDecoder(videoExtractor, videoTrackIndex, surface)

        val format = audioExtractor.getTrackFormat(audioTrackIndex)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("AudioTrack doesn't support $channels channels")
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
//            .setBufferSizeInBytes(minBufferSize * 10)
//            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private val demuxThread = HandlerThread("DemuxThread").apply { start() }
    private val audioDecodeThread = HandlerThread("AudioDecodeThread").apply { start() }
    private val videoDecodeThread = HandlerThread("VideoDecodeThread").apply { start() }
    private val syncThread = HandlerThread("SyncThread").apply { start() }
    private val audioRenderThread = HandlerThread("AudioRenderThread").apply { start() }
    private val videoRenderThread = HandlerThread("VideoRenderThread").apply { start() }
    private val demuxHandler = Handler(demuxThread.looper)
    private val audioDecodeHandler = Handler(audioDecodeThread.looper)
    private val videoDecodeHandler = Handler(videoDecodeThread.looper)
    private val syncHandler = Handler(syncThread.looper)
    private val audioRenderHandler = Handler(audioRenderThread.looper)
    private val videoRenderHandler = Handler(videoRenderThread.looper)

    private val audioFrameQueue: Queue<AudioFrame> = ConcurrentLinkedQueue<AudioFrame>()
    private val videoFrameQueue: Queue<VideoFrame> = ConcurrentLinkedQueue<VideoFrame>()
    private var startTimeMs = -1L

    /**
     * in milliseconds
     */
    val position: Long
        get() = if (startTimeMs < 0) {
            startTimeMs
        } else {
            SystemClock.uptimeMillis() - startTimeMs
        }

    /**
     * in milliseconds
     */
    val duration: Long = kotlin.run {
        val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
        val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
        val audioDurationUs = audioFormat.getLong(MediaFormat.KEY_DURATION)
        val videoDurationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)
        max(audioDurationUs, videoDurationUs) / 1000L
    }

    private data class AudioFrame(
        val data: ByteBuffer,
        val bufferId: Int,
        val ptsUs: Long
    ) {
        override fun toString(): String = "AudioFrame(bufferId=$bufferId, ptsUs=$ptsUs)"
    }

    private data class VideoFrame(
        val bufferId: Int,
        val ptsUs: Long
    )

    private fun createDecoder(extractor: MediaExtractor, trackIndex: Int, surface: Surface? = null): MediaCodec {
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        return MediaCodec.createDecoderByType(mime).apply {
            configure(format, surface, null, 0)
        }
    }

    fun play() = synchronized(this) {
        audioInEos = false
        audioOutEos = false
        videoInEos = false
        videoOutEos = false
        startTimeMs = -1L

        audioDecoder.start()
        videoDecoder.start()
        audioFrameQueue.clear()
        videoFrameQueue.clear()

        postExtractAudio(0)
        postExtractVideo(0)
        postDecodeAudio(0)
        postDecodeVideo(0)
    }

    private fun postExtractAudio(delayMillis: Long) {
        demuxHandler.postDelayed({
            if (!audioInEos) {
                when (val inputIndex = audioDecoder.dequeueInputBuffer(0)) {
                    in 0..Int.MAX_VALUE -> {
                        val inputBuffer = audioDecoder.getInputBuffer(inputIndex)!!
                        val chunkSize = audioExtractor.readSampleData(inputBuffer, 0)
                        if (chunkSize < 0) {
                            audioDecoder.queueInputBuffer(inputIndex, 0, 0, -1,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioInEos = true
                        } else {
                            val sampleTimeUs = audioExtractor.sampleTime
                            audioDecoder.queueInputBuffer(inputIndex, 0, chunkSize,
                                sampleTimeUs, 0)
                            audioExtractor.advance()
                        }

                        postExtractAudio(0)
                    }
                    else -> postExtractAudio(10)
                }
            }
        }, delayMillis)
    }

    private fun postDecodeAudio(delayMillis: Long) {
        audioDecodeHandler.postDelayed({
            if (!audioOutEos) {
                when (val outputIndex = audioDecoder.dequeueOutputBuffer(audioBufferInfo, 0)) {
                    in 0..Int.MAX_VALUE -> {
                        if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            audioDecoder.releaseOutputBuffer(outputIndex, false)
                            audioOutEos = true
                        } else {
                            val outputBuffer = audioDecoder.getOutputBuffer(outputIndex)!!
                            outputBuffer.position(audioBufferInfo.offset)
                            outputBuffer.limit(audioBufferInfo.offset + audioBufferInfo.size)

                            queueAudio(outputBuffer, outputIndex,
                                audioBufferInfo.presentationTimeUs)
                        }

                        postDecodeAudio(0)
                        return@postDelayed
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> error("unexpected result from " +
                            "decoder.dequeueOutputBuffer: $outputIndex")
                }

                postDecodeAudio(10)
            }
        }, delayMillis)
    }

    private fun postExtractVideo(delayMillis: Long) {
        demuxHandler.postDelayed({
            if (!videoInEos) {
                when (val inputIndex = videoDecoder.dequeueInputBuffer(0)) {
                    in 0..Int.MAX_VALUE -> {
                        val inputBuffer = videoDecoder.getInputBuffer(inputIndex)!!
                        val chunkSize = videoExtractor.readSampleData(inputBuffer, 0)
                        if (chunkSize < 0) {
                            videoDecoder.queueInputBuffer(inputIndex, 0, 0, -1,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            videoInEos = true
                        } else {
                            val sampleTimeUs = videoExtractor.sampleTime
                            videoDecoder.queueInputBuffer(inputIndex, 0, chunkSize,
                                sampleTimeUs, 0)
                            videoExtractor.advance()
                        }

                        postExtractVideo(0)
                    }
                    else -> postExtractVideo(10)
                }
            }
        }, delayMillis)
    }

    private fun postDecodeVideo(delayMillis: Long) {
        videoDecodeHandler.postDelayed({
            if (!videoOutEos) {
                when (val outputIndex = videoDecoder.dequeueOutputBuffer(videoBufferInfo, 0)) {
                    in 0..Int.MAX_VALUE -> {
                        if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            videoDecoder.releaseOutputBuffer(outputIndex, false)
                            videoOutEos = true
                        } else {
                            queueVideo(outputIndex, videoBufferInfo.presentationTimeUs)
                        }

                        postDecodeVideo(0)
                        return@postDelayed
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> error("unexpected result from " +
                            "decoder.dequeueOutputBuffer: $outputIndex")
                }

                postDecodeVideo(10)
            }
        }, delayMillis)
    }

    private fun postRenderAudio(audioFrame: AudioFrame, delayMillis: Long) {
        audioRenderHandler.postDelayed({
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play()
            }

            Log.d("TEST", "[${SystemClock.uptimeMillis() - startTimeMs}] audio render ${audioFrame.ptsUs / 1000}, ${audioFrame.bufferId}")

            val size = audioFrame.data.remaining()
            audioTrack.write(audioFrame.data, size, AudioTrack.WRITE_NON_BLOCKING)

            audioDecoder.releaseOutputBuffer(audioFrame.bufferId, false)
        }, delayMillis)
    }

    private fun postRenderAudioAtTime(audioFrame: AudioFrame, uptimeMillis: Long) {
        audioRenderHandler.postAtTime({
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play()
            }

            Log.d("TEST", "[${SystemClock.uptimeMillis() - startTimeMs}] audio render ${audioFrame.ptsUs / 1000}, ${audioFrame.bufferId}")

            val size = audioFrame.data.remaining()
            audioTrack.write(audioFrame.data, size, AudioTrack.WRITE_NON_BLOCKING)

            audioDecoder.releaseOutputBuffer(audioFrame.bufferId, false)
        }, uptimeMillis)
    }

    private fun postRenderVideo(videoFrame: VideoFrame, delayMillis: Long) {
        videoRenderHandler.postDelayed({
            Log.d("TEST", "[${SystemClock.uptimeMillis() - startTimeMs}] video render ${videoFrame.ptsUs / 1000}, ${videoFrame.bufferId}")
            videoDecoder.releaseOutputBuffer(videoFrame.bufferId, true)
        }, delayMillis)
    }

    private fun postRenderVideoAtTime(videoFrame: VideoFrame, uptimeMillis: Long) {
        videoRenderHandler.postAtTime({
            Log.d("TEST", "[${SystemClock.uptimeMillis() - startTimeMs}] video render ${videoFrame.ptsUs / 1000}, ${videoFrame.bufferId}")
            videoDecoder.releaseOutputBuffer(videoFrame.bufferId, true)
        }, uptimeMillis)
    }

    private fun queueAudio(data: ByteBuffer, bufferId: Int, ptsUs: Long) {
        Log.d("TEST", "queueAudio: $bufferId, $ptsUs")
        audioFrameQueue.add(AudioFrame(data, bufferId, ptsUs))
        postSyncAudioVideo(0)
    }

    private fun queueVideo(bufferId: Int, ptsUs: Long) {
        Log.d("TEST", "queueVideo: $bufferId, $ptsUs")
        videoFrameQueue.add(VideoFrame(bufferId, ptsUs))
        postSyncAudioVideo(0)
    }

    private fun postSyncAudioVideo(delayMillis: Long) {
        syncHandler.postDelayed({
            val curTimeMs = SystemClock.uptimeMillis()

            val audioFrame: AudioFrame? = audioFrameQueue.peek()
            val videoFrame: VideoFrame? = videoFrameQueue.peek()

            if (audioFrame == null && videoFrame == null) {
                return@postDelayed
            }

//            Log.d("TEST", "postSyncAudioVideo: audio=$audioFrame, video=$videoFrame")

            if (startTimeMs < 0) {
                if (audioFrame == null || videoFrame == null) {
                    return@postDelayed
                }

                val startPtsUs = min(audioFrame.ptsUs, videoFrame.ptsUs)
                startTimeMs = curTimeMs - startPtsUs / 1000L

                val audioDelayMs = audioFrame.ptsUs / 1000L - (curTimeMs - startTimeMs)
                val videoDelayMs = videoFrame.ptsUs / 1000L - (curTimeMs - startTimeMs)
                Log.d("TEST", "clock=${curTimeMs - startTimeMs}, audio pts=${audioFrame.ptsUs / 1000L}, video pts=${videoFrame.ptsUs / 1000L}, audio delay=$audioDelayMs ms, video delay=$videoDelayMs ms")
            }

            if (audioFrame != null) {
                val renderTimeMs = startTimeMs + audioFrame.ptsUs / 1000L
                postRenderAudioAtTime(audioFrame, renderTimeMs)
                audioFrameQueue.remove()
            }

            if (videoFrame != null) {
                val renderTimeMs = startTimeMs + videoFrame.ptsUs / 1000L
                if (renderTimeMs >= curTimeMs) {
                    postRenderVideoAtTime(videoFrame, renderTimeMs)
                } else {
                    videoDecoder.releaseOutputBuffer(videoFrame.bufferId, false)
                }

                videoFrameQueue.remove()
            }

            if (!audioFrameQueue.isEmpty() || !videoFrameQueue.isEmpty()) {
                postSyncAudioVideo(0)
            } else {
                postSyncAudioVideo(10)
            }
        }, delayMillis)
    }

    fun seekTo(msec: Long, mode: SeekMode) = synchronized(this) {
        val seekPositionUs = min(max(msec, 0), duration) * 1000L

        val barrier = CyclicBarrier(6)
        val latch = CountDownLatch(6)

        demuxHandler.postAtFrontOfQueue {
            barrier.await()
            demuxHandler.removeCallbacksAndMessages(null)
            latch.countDown()
        }
        audioDecodeHandler.postAtFrontOfQueue {
            barrier.await()
            audioDecodeHandler.removeCallbacksAndMessages(null)
            latch.countDown()
        }
        videoDecodeHandler.postAtFrontOfQueue {
            barrier.await()
            videoDecodeHandler.removeCallbacksAndMessages(null)
            latch.countDown()
        }
        syncHandler.postAtFrontOfQueue {
            barrier.await()
            syncHandler.removeCallbacksAndMessages(null)
            latch.countDown()
        }
        audioRenderHandler.postAtFrontOfQueue {
            barrier.await()
            audioRenderHandler.removeCallbacksAndMessages(null)
            latch.countDown()
        }
        videoRenderHandler.postAtFrontOfQueue {
            barrier.await()
            videoRenderHandler.removeCallbacksAndMessages(null)
            latch.countDown()
        }

        latch.await()

        videoDecoder.flush()
        audioDecoder.flush()
        audioTrack.pause()
        audioTrack.flush()

        audioFrameQueue.clear()
        videoFrameQueue.clear()

        val flag = when (mode) {
            SeekMode.PREVIOUS_SYNC -> MediaExtractor.SEEK_TO_PREVIOUS_SYNC
            SeekMode.NEXT_SYNC -> MediaExtractor.SEEK_TO_NEXT_SYNC
        }
        videoExtractor.seekTo(seekPositionUs, flag)
        val sampleTimeUs = videoExtractor.sampleTime
        audioExtractor.seekTo(sampleTimeUs, flag)

        startTimeMs = -1L

        audioInEos = false
        videoInEos = false
        audioOutEos = false
        videoOutEos = false

        postExtractAudio(0)
        postExtractVideo(0)
        postDecodeAudio(0)
        postDecodeVideo(0)
    }

    fun release() = synchronized(this) {
        val latch = CountDownLatch(6)

        demuxHandler.postAtFrontOfQueue {
            demuxThread.quit()
            latch.countDown()
        }
        audioDecodeHandler.postAtFrontOfQueue {
            audioDecodeThread.quit()
            latch.countDown()
        }
        videoDecodeHandler.postAtFrontOfQueue {
            videoDecodeThread.quit()
            latch.countDown()
        }
        syncHandler.postAtFrontOfQueue {
            syncThread.quit()
            latch.countDown()
        }
        audioRenderHandler.postAtFrontOfQueue {
            audioRenderThread.quit()
            latch.countDown()
        }
        videoRenderHandler.postAtFrontOfQueue {
            videoRenderThread.quit()
            latch.countDown()
        }

        latch.await()

        audioFrameQueue.clear()
        videoFrameQueue.clear()

        audioDecoder.stop()
        videoDecoder.stop()
        audioTrack.stop()
        audioDecoder.release()
        videoDecoder.release()
        audioExtractor.release()
        videoExtractor.release()
        audioTrack.release()
    }
}

fun MediaExtractor.findFirstTrackFor(type: String): Int? {
    for (i in 0 until trackCount) {
        val mediaFormat = getTrackFormat(i)
        if (mediaFormat.getString(MediaFormat.KEY_MIME)!!.startsWith(type)) {
            return i
        }
    }

    return null
}

val MediaExtractor.firstVideoTrack: Int? get() = findFirstTrackFor("video/")
val MediaExtractor.firstAudioTrack: Int? get() = findFirstTrackFor("audio/")
