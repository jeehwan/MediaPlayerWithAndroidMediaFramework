package com.navercorp.deview.mediaplayer

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import kotlin.concurrent.thread

// http://distribution.bbb3d.renderfarming.net/video/mp4/bbb_sunflower_1080p_30fps_normal.mp4
private val MEDIA_FILE = "bbb_sunflower_1080p_30fps_normal.mp4"
private val TIMEOUT_US = 10_000L

class MainActivity : AppCompatActivity() {

    private lateinit var view: AutoFitTextureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        view = textureView
        view.setAspectRatio(16, 9)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) = Unit

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                createVideoThread(Surface(surface))
            }
        }
    }

    private fun createVideoThread(surface: Surface) = thread {
        val extractor = assets.openFd(MEDIA_FILE).use {
            MediaExtractor().apply { setDataSource(it) }
        }

        val trackIndex = extractor.firstVideoTrack
            ?: error("This media file doesn't contain any video tracks")

        extractor.selectTrack(trackIndex)

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: error("Video track must have the mime type")

        val decoder = MediaCodec.createDecoderByType(mime).apply {
            configure(format, surface, null, 0)
            start()
        }

        doExtract(extractor, decoder)

        decoder.stop()
        decoder.release()
        extractor.release()
    }

    private fun doExtract(extractor: MediaExtractor, decoder: MediaCodec) {
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
