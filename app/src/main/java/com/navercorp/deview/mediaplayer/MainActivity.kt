package com.navercorp.deview.mediaplayer

import android.graphics.SurfaceTexture
import android.media.*
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// http://distribution.bbb3d.renderfarming.net/video/mp4/bbb_sunflower_1080p_30fps_normal.mp4
private val MEDIA_FILE = "bbb_sunflower_1080p_30fps_normal.mp4"
private val TIMEOUT_US = 10_000L

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var view: AutoFitTextureView

    private lateinit var avPlayer: AVPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        view = textureView
        view.setAspectRatio(16, 9)
        view.surfaceTextureListener = this

        avPlayer = AVPlayer {
            assets.openFd(MEDIA_FILE).use {
                MediaExtractor().apply { setDataSource(it) }
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        avPlayer.setSurface(Surface(surface))
        avPlayer.start()
    }

}

class AVPlayer(private val extractorSupplier: () -> MediaExtractor) {

    private lateinit var surface: Surface

    fun setSurface(surface: Surface) {
        this.surface = surface
    }

    fun start() {
        createVideoThread()
        createAudioThread()
    }

    private fun createVideoThread() = thread {
        val extractor = extractorSupplier()
        val trackIndex = extractor.firstVideoTrack ?: error("")

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("")

        val decoder = MediaCodec.createDecoderByType(mime).apply {
            configure(format, surface, null, 0)
            start()
        }

        extractor.selectTrack(trackIndex)

        try {
            doVideoExtract(extractor, decoder)
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }
    }

    private fun doVideoExtract(extractor: MediaExtractor, decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()

        var inEos = false
        var outEos = false

        var startTimeUs = -1L

        while (!outEos) {
            if (!inEos) {
                when (val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)) {
                    in 0..Int.MAX_VALUE -> {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                        val chunkSize = extractor.readSampleData(inputBuffer, 0)
                        if (chunkSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, -1,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            val sampleTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputIndex, 0, chunkSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                    else -> Unit
                }
            }

            if (!outEos) {
                when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    in 0..Int.MAX_VALUE -> {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoder.releaseOutputBuffer(outputIndex, false)
                            outEos = true
                        } else {
                            val curTimeUs = System.nanoTime() / 1000L
                            if (startTimeUs < 0) {
                                startTimeUs = curTimeUs
                            } else {
                                val sleepTimeUs = info.presentationTimeUs - (curTimeUs - startTimeUs)
                                if (sleepTimeUs > 0) {
                                    TimeUnit.MICROSECONDS.sleep(sleepTimeUs)
                                } else {
                                    // TODO
                                }
                            }

                            decoder.releaseOutputBuffer(outputIndex, true)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> error("unexpected result from decoder.dequeueOutputBuffer: $outputIndex")
                }
            }
        }
    }

    private fun createAudioThread() = thread {
        TimeUnit.SECONDS.sleep(1)  // this will corrupt a/v sync

        val extractor = extractorSupplier()
        val trackIndex = extractor.firstAudioTrack ?: error("")

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        val decoder = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }

        val audioTrack = AudioTrack.Builder()
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

        audioTrack.play()
        extractor.selectTrack(trackIndex)

        try {
            doAudioExtract(extractor, decoder, audioTrack)
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
            audioTrack.stop()
            audioTrack.release()
        }
    }

    private fun doAudioExtract(extractor: MediaExtractor, decoder: MediaCodec, audioTrack: AudioTrack) {
        val info = MediaCodec.BufferInfo()

        var inEos = false
        var outEos = false

        while (!outEos) {
            if (!inEos) {
                when (val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)) {
                    in 0..Int.MAX_VALUE -> {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                        val chunkSize = extractor.readSampleData(inputBuffer, 0)
                        if (chunkSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, -1,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            val sampleTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputIndex, 0, chunkSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                    else -> Unit
                }
            }

            if (!outEos) {
                when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    in 0..Int.MAX_VALUE -> {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoder.releaseOutputBuffer(outputIndex, false)
                            outEos = true
                        } else {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)!!
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            audioTrack.write(outputBuffer, info.size, AudioTrack.WRITE_BLOCKING)
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> error("unexpected result from decoder.dequeueOutputBuffer: $outputIndex")
                }
            }
        }
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
