package com.navercorp.deview.mediaplayer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import kotlin.concurrent.thread

// http://distribution.bbb3d.renderfarming.net/video/mp4/bbb_sunflower_1080p_60fps_normal.mp4
private val MEDIA_FILE = "bbb_sunflower_1080p_30fps_normal.mp4"

class MainActivity : AppCompatActivity() {

    private lateinit var view: AutoFitTextureView

    private var isPlaying = false
    private var videoThread: Thread? = null
    private var isVideoRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        view = textureView
        view.setAspectRatio(720, 1280)

        seekForward.setOnClickListener {
            seekTo(5)
        }

        playOrPause.setOnClickListener {
            Log.d("TEST", "playOrPause: $isPlaying")
            if (!isPlaying) {
                start()
                playOrPause.background = ContextCompat.getDrawable(this, R.drawable.pause)
                isPlaying = true
            } else {
                pause()
                playOrPause.background = ContextCompat.getDrawable(this, R.drawable.play)
                isPlaying = false
            }
        }

        seekReplay.setOnClickListener {
            seekTo(-5)
        }
    }

    private val TIMEOUT_US = 10_000L

    private fun createVideoThread(surface: Surface) = thread {
        val extractor = MediaExtractor().apply { setDataSource(MEDIA_FILE) }

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

    fun start() {
        val assetManager = this.assets
        isVideoRunning = true
        videoThread = thread {
            val demuxer = MediaExtractor().apply { setDataSource(MEDIA_FILE) }

            val videoTrackIndex = demuxer.firstVideoTrack
                ?: error("This media file doesn't contain any video tracks")

            demuxer.selectTrack(videoTrackIndex)

            val videoTrackFormat = demuxer.getTrackFormat(videoTrackIndex)

            val videoMimeType = videoTrackFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Video track must have the mime type")

            val videoDecoder = MediaCodec.createDecoderByType(videoMimeType)
            videoDecoder.configure(videoTrackFormat, surface, null, 0)
            videoDecoder.start()

            var inEos = false
            var outEos = false

            val info = MediaCodec.BufferInfo()

            while (!inEos && !outEos && isVideoRunning) {
                inputLoop@ while (!inEos && isVideoRunning) {
                    when (val inputIndex = videoDecoder.dequeueInputBuffer(10_000L)) {
                        in 0..Int.MAX_VALUE -> {
                            val sampleTimeUs = demuxer.sampleTime
                            if (sampleTimeUs < 0) {
                                videoDecoder.queueInputBuffer(inputIndex, 0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inEos = true
                                break@inputLoop
                            }

                            val inputBuffer = videoDecoder.getInputBuffer(inputIndex)!!
                            val sampleSize = demuxer.readSampleData(inputBuffer, 0)

                            videoDecoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)

                            demuxer.advance()
                        }
                        else -> break@inputLoop
                    }
                }

                if (!outEos && isVideoRunning) {
                    when (val outputIndex = videoDecoder.dequeueOutputBuffer(info, 10_000L)) {
                        in 0..Int.MAX_VALUE -> {
                            when {
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ->
                                    videoDecoder.releaseOutputBuffer(outputIndex, false)
                                (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 -> {
                                    videoDecoder.releaseOutputBuffer(outputIndex, false)
                                    outEos = true
                                }
                                else -> videoDecoder.releaseOutputBuffer(outputIndex, true)
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> error("")
                    }
                }
            }

            videoDecoder.release()
            demuxer.release()

            isVideoRunning = false
        }
    }

    fun stop() {

    }

    fun pause() {
    }

    fun seekTo(seekTime: Int) {

    }

    fun release() {
        TODO("Not yet implemented")
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

//fun test() {
//
//    kotlin
//        .runCatching {
//            val demuxer = assets.openFd(MEDIA_FILE).use {
//                MediaExtractor().apply { setDataSource(it) }
//            }
//
//            val videoTrack = demuxer.firstVideoTrack
//                ?: error("This media file doesn't have any video tracks")
//
//            val videoFormat = demuxer.getTrackFormat(videoTrack)
//            val videoType = videoFormat.getString(MediaFormat.KEY_MIME)!!
//
//            val decoder = MediaCodec.createDecoderByType(videoType).apply {
//                configure(videoFormat, Surface(surface), null, 0)
//                start()
//            }
//
//            demuxer to decoder
//        }
//        .onFailure {
//            it.printStackTrace()
//        }
//        .onSuccess { (demuxer, decoder) ->
//            this@MainActivity.demuxer = demuxer
//            videoDecoder = decoder
//        }
//}