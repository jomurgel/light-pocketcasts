package com.jomurgel.pocketcasts.playback

import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * The slice of LightAudioPlayer the controller uses, as an interface so tests
 * can fake it. LightPlayerHandle is the real adapter.
 */
interface PlayerHandle {
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    val currentMediaItemIndex: StateFlow<Int>
    val error: StateFlow<LightAudioError?>
    var speed: Float

    suspend fun awaitReady(): Boolean
    fun setMediaQueue(items: List<LightAudioItem>, startIndex: Int = 0)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(ms: Long)
    fun skipBack()
    fun skipForward()
    fun release()
}

class LightPlayerHandle(private val player: LightAudioPlayer) : PlayerHandle {
    override val positionMs: StateFlow<Long> get() = player.positionMs
    override val durationMs: StateFlow<Long> get() = player.durationMs
    override val isPlaying: StateFlow<Boolean> get() = player.isPlaying
    override val currentMediaItemIndex: StateFlow<Int> get() = player.currentMediaItemIndex
    override val error: StateFlow<LightAudioError?> get() = player.error

    override var speed: Float
        get() = player.speed
        set(value) {
            player.speed = value
        }

    override suspend fun awaitReady(): Boolean = player.awaitReady()
    override fun setMediaQueue(items: List<LightAudioItem>, startIndex: Int) =
        player.setMediaQueue(items, startIndex)

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() = player.stop()
    override fun seekTo(ms: Long) = player.seekTo(ms)
    override fun skipBack() = player.skipBack()
    override fun skipForward() = player.skipForward()
    override fun release() = player.release()
}
