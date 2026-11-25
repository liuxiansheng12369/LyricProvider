package io.github.proify.lyricon.miuiprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.highcapable.yukihookapi.extension.kavaref.toClass
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.cloudlyric.ProviderLyrics
import io.github.proify.extensions.md5
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

/**
 * 小米音乐（澎湃 OS 自带，com.miui.player）歌词源。
 *
 * 小米音乐 MediaSession 里的 ARTIST 是「歌名-歌手」拼起来的，TITLE 只是当前歌词行，
 * 所以真正的曲名要从 ARTIST 里拆；拿到名字后交给云取词引擎（QQ 音乐）匹配。
 */
object MiuiMusic : YukiBaseHooker(), DownloadCallback {
    private const val TAG: String = "MiuiMusicProvider"

    private var provider: LyriconProvider? = null
    private var lastMediaSignature: String? = null

    private var curMediaMetadata: MediaMetadata? = null

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "进程: $processName")

        registerAppLifecycle(true) {
            onCreate {
                initProvider()
            }
        }

        hookMediaSession()
    }

    private fun initProvider() {
        val context = hostApplication ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON),
            processName = processName
        ).apply {
            player.setDisplayTranslation(true)
            register()
        }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = args[0] as? PlaybackState
                    provider?.player?.setPlaybackState(state)
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters(MediaMetadata::class.java)
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after

                    val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)

                    val (songName, singerName) = splitTitleArtist(rawTitle, rawArtist)

                    val signature = calculateSignature(id, songName, singerName, album)
                    if (signature == lastMediaSignature) {
                        YLog.debug(tag = TAG, msg = "Same metadata, skip")
                        return@after
                    }
                    lastMediaSignature = signature
                    curMediaMetadata = metadata

                    YLog.debug(
                        tag = TAG,
                        msg = "Metadata: id=$id, rawTitle=$rawTitle, rawArtist=$rawArtist -> song=$songName, singer=$singerName, album=$album"
                    )

                    provider?.player?.setSong(
                        Song(
                            name = songName,
                            artist = singerName,
                        )
                    )

                    DownloadManager.cancel()

                    DownloadManager.search(this@MiuiMusic) {
                        trackName = songName
                        artistName = singerName
                        albumName = album
                        perProviderLimit = 5
                        maxTotalResults = 1
                    }
                }
            }
        }
    }

    /**
     * 小米音乐的 ARTIST 形如「歌名-歌手」，拆不开就按原值交给引擎。
     */
    private fun splitTitleArtist(rawTitle: String?, rawArtist: String?): Pair<String, String> {
        val artist = rawArtist?.trim().orEmpty()
        if (artist.isNotEmpty()) {
            val dashIndex = artist.indexOf('-')
            if (dashIndex > 0 && dashIndex < artist.length - 1) {
                val name = artist.substring(0, dashIndex).trim()
                val singer = artist.substring(dashIndex + 1).trim()
                if (name.isNotEmpty() && singer.isNotEmpty()) return name to singer
            }
        }
        return rawTitle?.trim().orEmpty() to artist
    }

    private fun calculateSignature(vararg data: String?): String {
        return data.joinToString("").md5()
    }

    override fun onDownloadFinished(response: List<ProviderLyrics>) {
        if (response.isEmpty()) {
            YLog.debug(tag = TAG, msg = "No lyrics found")
            return
        }
        YLog.debug(tag = TAG, msg = "Download finished: $response")
        val song = response.firstOrNull()?.lyrics?.toSong()
        provider?.player?.setSong(song)
    }

    override fun onDownloadFailed(e: Exception) {
        YLog.error(tag = TAG, msg = "Download failed: ${e.message}")
    }

    private fun LyricsResult.toSong() = Song().apply {
        var duration = curMediaMetadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        if (duration == 0L) {
            duration = rich.lastOrNull()?.end ?: 0L
        }

        name = trackName
        artist = artistName
        lyrics = rich
        this.duration = duration
    }
}
