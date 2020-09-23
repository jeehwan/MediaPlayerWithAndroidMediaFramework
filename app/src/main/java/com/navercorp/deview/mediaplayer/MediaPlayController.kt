package com.navercorp.deview.mediaplayer

interface MediaPlayController {
    //prepare(), start(), stop(), pause(), seekTo(), release()
    //TODO add param videoSource
    fun prepare()
    fun start()
    fun stop()
    fun pause()

    //TODO add param seek second
    fun seekTo(seekTime: Int)
    fun release()
}
