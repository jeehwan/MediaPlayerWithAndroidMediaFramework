package com.navercorp.deview.mediaplayer

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.CountDownLatch

// http://distribution.bbb3d.renderfarming.net/video/mp4/bbb_sunflower_1080p_60fps_normal.mp4
private val MEDIA_FILE = "bbb_sunflower_1080p_60fps_normal.mp4"

class MainActivity : AppCompatActivity(), MediaPlayController {

    private val latch = CountDownLatch(1)
    private var _surfaceTexture: SurfaceTexture? = null
    val surfaceTexture: SurfaceTexture
        get() {
            latch.await()
            return _surfaceTexture!!
        }

    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val videoView: TextureView = findViewById(R.id.textureView)
        videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
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
                _surfaceTexture = surface
                latch.countDown()

            }
        }

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

    override fun prepare() {
        TODO("Not yet implemented")
    }

    override fun start() {
    }

    override fun stop() {
        TODO("Not yet implemented")
    }

    override fun pause() {
    }

    override fun seekTo(seekTime: Int) {

    }

    override fun release() {
        TODO("Not yet implemented")
    }
}



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