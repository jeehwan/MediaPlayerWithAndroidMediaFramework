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
import java.util.concurrent.TimeUnit
import kotlin.math.max

// http://distribution.bbb3d.renderfarming.net/video/mp4/bbb_sunflower_1080p_30fps_normal.mp4
private val MEDIA_FILE = "bbb_sunflower_1080p_30fps_normal.mp4"
private val TIMEOUT_MS = 10L

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var view: AutoFitTextureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        view = textureView
        view.setAspectRatio(16, 9)
        view.surfaceTextureListener = this
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val avPlayer = AVPlayer({
            assets.openFd(MEDIA_FILE).use {
                MediaExtractor().apply { setDataSource(it) }
            }
        }, Surface(surface))
        avPlayer.start()
    }

}

class AVPlayer(extractorSupplier: () -> MediaExtractor, surface: Surface) {

    private val audioExtractor: MediaExtractor
    private val videoExtractor: MediaExtractor
    private val audioTrackIndex: Int
    private val videoTrackIndex: Int
    private val audioDecoder: MediaCodec
    private val videoDecoder: MediaCodec
    private val audioTrack: AudioTrack

    private var audioInEos = false
    private var audioOutEos = false
    private var videoInEos = false
    private var videoOutEos = false
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private val videoBufferInfo = MediaCodec.BufferInfo()
    private var startTimeUs = -1L

    init {
        val extractor = extractorSupplier()
        val audioTrackIdx = extractor.firstAudioTrack
        val videoTrackIdx = extractor.firstVideoTrack
        extractor.release()

        if (audioTrackIdx == null || videoTrackIdx == null) {
            error("We need both audio and video")
        }

        audioTrackIndex = audioTrackIdx
        videoTrackIndex = videoTrackIdx

        audioExtractor = extractorSupplier().apply { selectTrack(audioTrackIndex) }
        videoExtractor = extractorSupplier().apply { selectTrack(videoTrackIndex) }

        audioDecoder = createDecoder(audioExtractor, audioTrackIndex)
        videoDecoder = createDecoder(videoExtractor, videoTrackIndex, surface)

        audioTrack = createAudioTrack(audioExtractor.getTrackFormat(audioTrackIndex))
    }

    private val demuxThread = HandlerThread("DemuxThread").apply { start() }
    private val audioDecodeThread = HandlerThread("AudioDecodeThread").apply { start() }
    private val videoDecodeThread = HandlerThread("VideoDecodeThread").apply { start() }
    private val audioRenderThread = HandlerThread("AudioRenderThread").apply { start() }
    private val videoRenderThread = HandlerThread("VideoRenderThread").apply { start() }
    private val demuxHandler = Handler(demuxThread.looper)
    private val audioDecodeHandler = Handler(audioDecodeThread.looper)
    private val videoDecodeHandler = Handler(videoDecodeThread.looper)
    private val audioRenderHandler = Handler(audioRenderThread.looper)
    private val videoRenderHandler = Handler(videoRenderThread.looper)

    private fun createDecoder(
        extractor: MediaExtractor,
        trackIndex: Int,
        surface: Surface? = null
    ): MediaCodec{
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        return MediaCodec.createDecoderByType(mime).apply {
            configure(format, surface, null, 0)
        }
    }

    private fun createAudioTrack(format: MediaFormat): AudioTrack {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("AudioTrack doesn't support $channels channels")
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBufferSize * 10)
            .build()
    }

    fun start() {
        audioDecoder.start()
        videoDecoder.start()
        audioTrack.play()

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
                    else -> postExtractAudio(TIMEOUT_MS)
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

                            postRenderAudio(outputIndex, outputBuffer, 0)
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

                postDecodeAudio(TIMEOUT_MS)
            }
        }, delayMillis)
    }

    private fun postRenderAudio(bufferId: Int, buffer: ByteBuffer, delayMillis: Long) {
        audioRenderHandler.postDelayed({
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play()
            }

            val size = buffer.remaining()
            audioTrack.write(buffer, size, AudioTrack.WRITE_BLOCKING)

            audioDecoder.releaseOutputBuffer(bufferId, false)
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
                    else -> postExtractVideo(TIMEOUT_MS)
                }
            }
        }, delayMillis)
    }

    private fun postDecodeVideo(delayMillis: Long) {
        videoDecodeHandler.postDelayed({
            if (!videoOutEos) {
                val info = videoBufferInfo
                when (val outputIndex = videoDecoder.dequeueOutputBuffer(info, 0)) {
                    in 0..Int.MAX_VALUE -> {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            videoDecoder.releaseOutputBuffer(outputIndex, false)
                            videoOutEos = true
                        } else {
                            val curTimeUs = SystemClock.uptimeMillis() * 1000L
                            if (startTimeUs < 0) {
                                startTimeUs = curTimeUs
                            }
                            val curPtsUs = curTimeUs - startTimeUs
                            val sleepTimeUs = info.presentationTimeUs - curPtsUs

                            postRenderVideo(outputIndex, max(sleepTimeUs / 1000L, 0L))
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

                postDecodeVideo(TIMEOUT_MS)
            }
        }, delayMillis)
    }

    private fun postRenderVideo(bufferId: Int, delayMillis: Long) {
        videoRenderHandler.postDelayed({
            videoDecoder.releaseOutputBuffer(bufferId, true)
        }, delayMillis)
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
