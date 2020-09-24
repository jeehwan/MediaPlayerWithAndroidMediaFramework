package com.navercorp.deview.mediaplayer

import android.graphics.SurfaceTexture
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            avPlayer?.stop()
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
    private var videoStartTimeUs = -1L

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
            .build()
    }

    private val audioThread = HandlerThread("AudioThread").apply { start() }
    private val videoThread = HandlerThread("VideoThread").apply { start() }
    private val audioHandler = Handler(audioThread.looper)
    private val videoHandler = Handler(videoThread.looper)

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
        postExtractAudio(0)
        postExtractVideo(0)
        postDecodeAudio(0)
        postDecodeVideo(0)
    }

    fun stop() {
        val latch = CountDownLatch(2)

        audioHandler.postAtFrontOfQueue {
            audioHandler.removeCallbacksAndMessages(null)
            latch.countDown()
        }
        videoHandler.postAtFrontOfQueue {
            videoHandler.removeCallbacksAndMessages(null)
            latch.countDown()
        }

        latch.await()

        audioDecoder.stop()
        videoDecoder.stop()
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

                            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                audioTrack.play()
                            }

                            audioTrack.write(outputBuffer, audioBufferInfo.size, AudioTrack.WRITE_BLOCKING)
                            audioDecoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> error("unexpected result from decoder.dequeueOutputBuffer: $outputIndex")
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
                            val curTimeUs = System.nanoTime() / 1000L
                            if (videoStartTimeUs < 0) {
                                videoStartTimeUs = curTimeUs
                            } else {
                                val sleepTimeUs = videoBufferInfo.presentationTimeUs - (curTimeUs - videoStartTimeUs)
                                if (sleepTimeUs > 0) {
                                    TimeUnit.MICROSECONDS.sleep(sleepTimeUs)
                                }
                            }

                            videoDecoder.releaseOutputBuffer(outputIndex, true)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> error("unexpected result from decoder.dequeueOutputBuffer: $outputIndex")
                }

                postDecodeVideo(10)
            }
        }, delayMillis)
    }

    fun release() {
        val latch = CountDownLatch(2)

        audioHandler.postAtFrontOfQueue {
            audioThread.quit()
            latch.countDown()
        }
        videoHandler.postAtFrontOfQueue {
            videoThread.quit()
            latch.countDown()
        }

        latch.await()

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
