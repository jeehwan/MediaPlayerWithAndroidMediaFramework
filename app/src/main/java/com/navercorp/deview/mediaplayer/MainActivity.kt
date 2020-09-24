package com.navercorp.deview.mediaplayer

import android.graphics.SurfaceTexture
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        view = textureView
        view.setAspectRatio(16, 9)
        view.surfaceTextureListener = this

        playOrPause.setOnClickListener {
            playOrStop()
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
    }

}

class AVPlayer(extractorSupplier: () -> MediaExtractor, surface: Surface) {

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
            .setBufferSizeInBytes(1024 * 100)
            .build()
    }

    private val audioThread = HandlerThread("AudioThread").apply { start() }
    private val videoThread = HandlerThread("VideoThread").apply { start() }
    private val syncThread = HandlerThread("SyncThread").apply { start() }
    private val audioRenderThread = HandlerThread("AudioRenderThread").apply { start() }
    private val videoRenderThread = HandlerThread("VideoRenderThread").apply { start() }
    private val audioHandler = Handler(audioThread.looper)
    private val videoHandler = Handler(videoThread.looper)
    private val syncHandler = Handler(syncThread.looper)
    private val audioRenderHandler = Handler(audioRenderThread.looper)
    private val videoRenderHandler = Handler(videoRenderThread.looper)

    private val audioFrameQueue: Queue<AudioFrame> = ConcurrentLinkedQueue<AudioFrame>()
    private val videoFrameQueue: Queue<VideoFrame> = ConcurrentLinkedQueue<VideoFrame>()
    private var startTimeMs = -1L

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

    fun play() {
        audioInEos = false
        audioOutEos = false
        videoInEos = false
        videoOutEos = false
        audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        videoExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        audioDecoder.start()
        videoDecoder.start()
        audioFrameQueue.clear()
        videoFrameQueue.clear()
        startTimeMs = -1L
        postExtractAudio(0)
        postExtractVideo(0)
        postDecodeAudio(0)
        postDecodeVideo(0)
    }

    private fun postExtractAudio(delayMillis: Long) {
        audioHandler.postDelayed({
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
                    }
                    else -> Unit
                }

                postExtractAudio(10)
            }
        }, delayMillis)
    }

    private fun postDecodeAudio(delayMillis: Long) {
        audioHandler.postDelayed({
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
        videoHandler.postDelayed({
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
                    }
                    else -> Unit
                }

                postExtractVideo(10)
            }
        }, delayMillis)
    }

    private fun postDecodeVideo(delayMillis: Long) {
        videoHandler.postDelayed({
            if (!videoOutEos) {
                when (val outputIndex = videoDecoder.dequeueOutputBuffer(videoBufferInfo, 0)) {
                    in 0..Int.MAX_VALUE -> {
                        if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            videoDecoder.releaseOutputBuffer(outputIndex, false)
                            videoOutEos = true
                        } else {
                            queueVideo(outputIndex, videoBufferInfo.presentationTimeUs)
                        }
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

    private fun postRenderAudio(audioFrame: AudioFrame, uptimeMillis: Long) {
        audioRenderHandler.postAtTime({
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play()
            }

            val size = audioFrame.data.remaining()
            audioTrack.write(audioFrame.data, size, AudioTrack.WRITE_BLOCKING)

            audioDecoder.releaseOutputBuffer(audioFrame.bufferId, false)
        }, uptimeMillis)
    }

    private fun postRenderVideo(videoFrame: VideoFrame, uptimeMillis: Long) {
        videoRenderHandler.postAtTime({
            videoDecoder.releaseOutputBuffer(videoFrame.bufferId, true)
        }, uptimeMillis)
    }

    private fun queueAudio(data: ByteBuffer, bufferId: Int, ptsUs: Long) {
//        Log.d("TEST", "queueAudio: $bufferId, $ptsUs")
        audioFrameQueue.add(AudioFrame(data, bufferId, ptsUs))
        postSyncAudioVideo(0)
    }

    private fun queueVideo(bufferId: Int, ptsUs: Long) {
//        Log.d("TEST", "queueVideo: $bufferId, $ptsUs")
        videoFrameQueue.add(VideoFrame(bufferId, ptsUs))
        postSyncAudioVideo(0)
    }

    private fun postSyncAudioVideo(delayMillis: Long) {
        syncHandler.postDelayed({
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
                startTimeMs = SystemClock.uptimeMillis() - startPtsUs / 1000L
            }

            if (audioFrame != null) {
                postRenderAudio(audioFrame, startTimeMs + audioFrame.ptsUs / 1000L)
                audioFrameQueue.remove()
            }

            if (videoFrame != null) {
                postRenderVideo(videoFrame , startTimeMs + videoFrame.ptsUs / 1000L)
                videoFrameQueue.remove()
            }

            if (!audioFrameQueue.isEmpty() || !videoFrameQueue.isEmpty()) {
                postSyncAudioVideo(0)
            } else {
                postSyncAudioVideo(10)
            }
        }, delayMillis)
    }

    fun release() {
        val latch = CountDownLatch(5)

        audioHandler.postAtFrontOfQueue {
            audioThread.quit()
            latch.countDown()
        }
        videoHandler.postAtFrontOfQueue {
            videoThread.quit()
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
