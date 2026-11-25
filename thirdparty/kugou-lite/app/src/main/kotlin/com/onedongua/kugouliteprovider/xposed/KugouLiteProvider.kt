package com.onedongua.kugouliteprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.onedongua.kugouliteprovider.BuildConfig
import io.github.proify.lyricon.krckit.KrcDecryptor
import io.github.proify.lyricon.krckit.KrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import java.io.File

object KugouLiteProvider : YukiBaseHooker() {
    private var provider: LyriconProvider? = null
    private var lastMediaSignature: String? = null

    private var kugouFile: File? = null

    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) YLog.debug(msg = msg)
    }

    private val lyricHandler by lazy { Handler(HandlerThread("LyricFetcher").apply { start() }.looper) }

    override fun onHook() {
        debugLog(msg = "Process: $processName")
        if (!shouldHookProcess()) return

        onAppLifecycle {
            onCreate {
                kugouFile = File(getExternalFilesDir(null), "kugou")
                initProvider()
            }
        }

        hookMediaSession()
    }

    fun shouldHookProcess(): Boolean {
        return processName.endsWith(":support")
                || processName.endsWith(".support")
    }

    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON),
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
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)

                    val signature = calculateSignature(id, title, artist, album)
                    if (signature == lastMediaSignature) {
                        debugLog(msg = "Same metadata, skip")
                        return@after
                    }
                    lastMediaSignature = signature

                    debugLog(msg = "Metadata: id=$id, title=$title, artist=$artist, album=$album")

                    /*provider?.player?.setSong(
                        Song(
                            name = title,
                            artist = artist,
                        )
                    )*/

                    var attempts = 0
                    val maxAttempts = 5
                    val fetchLyricTask = object : Runnable {
                        override fun run() {
                            attempts++
                            var file = getLyricFile(artist, title)
                            if (file == null && attempts >= maxAttempts) {
                                debugLog(msg = "Exact match failed, trying fuzzy match...")
                                file = getLyricFileFuzzy(artist, title)
                            }
                            val krc = file?.readBytes()
                            if (krc != null && krc.isNotEmpty()) {
                                val decrypted = KrcDecryptor.decrypt(krc)
                                val rich = KrcParser.parse(decrypted).richLyricLines
                                provider?.player?.setSong(Song().apply {
                                    var duration =
                                        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                                    if (duration <= 0L) {
                                        duration = rich.lastOrNull()?.end ?: 0L
                                    }
                                    this.id = id
                                    name = title
                                    this.artist = artist
                                    lyrics = rich
                                    this.duration = duration
                                })
                                debugLog(msg = "Successfully fetched lyrics on attempt $attempts")
                            } else {
                                if (attempts < maxAttempts) {
                                    debugLog(msg = "Lyric not found, retrying ($attempts/$maxAttempts)...")
                                    lyricHandler.postDelayed(this, 500)
                                } else {
                                    debugLog(msg = "Failed to fetch lyrics after $maxAttempts attempts")
                                }
                            }
                        }
                    }
                    lyricHandler.postDelayed(fetchLyricTask, 500)
                }
            }
        }
    }

    private fun calculateSignature(vararg data: String?): String {
        return data.joinToString("").md5()
    }

    private fun getLyricFile(artist: String?, title: String?): File? {
        val kugouDir = kugouFile
        if (kugouDir == null || artist.isNullOrBlank() || title.isNullOrBlank()) return null
        val target = "$artist - $title"
            .replace(":", "：")
            .replace("/", "／")
            .replace("\\", "＼")
            .replace("*", "×")
            .replace("?", "？")
            .replace("\"", "＂")
            .replace("|", "｜")
        val lyricsDir = File(kugouDir, "lyrics")
        if (lyricsDir.isDirectory) {
            val list = lyricsDir.list()
            if (list != null && list.size > 1) {
                debugLog(msg = "finding: $target")
                list.forEach { name ->
                    if (name.startsWith(target)) {
                        debugLog(msg = "found: $name")
                        return File(lyricsDir, name)
                    }
                }
            }
        }
        debugLog(msg = "finding: lyrics$target")
        val rootList = kugouDir.list()
        if (rootList != null) {
            rootList.forEach { name ->
                if (name.startsWith("lyrics$target")) {
                    debugLog(msg = "found: $name")
                    return File(kugouDir, name)
                }
            }
        }
        return null
    }

    private fun getLyricFileFuzzy(artist: String?, title: String?): File? {
        val kugouDir = kugouFile
        if (kugouDir == null || artist.isNullOrBlank() || title.isNullOrBlank()) return null
        val target = "$artist - $title"
        val lyricsDir = File(kugouDir, "lyrics")
        if (lyricsDir.isDirectory) {
            val list = lyricsDir.list()
            if (list != null && list.size > 1) {
                list.forEach { name ->
                    val compareName = name.substringBeforeLast("-").trim()
                    val similarity = calculateSimilarity(target, compareName)
                    val distance = (1.0 - similarity) * maxOf(target.length, compareName.length)
                    if (similarity > 0.94 || distance <= 1.1) {
                        debugLog(msg = "Fuzzy match found in lyrics ($similarity | $distance): $name")
                        return File(lyricsDir, name)
                    }
                }
            }
        }
        kugouDir.list()?.forEach { name ->
            if (name.startsWith("lyrics")) {
                val compareName = name.removePrefix("lyrics").substringBeforeLast("-").trim()
                val similarity = calculateSimilarity(target, compareName)
                val distance = (1.0 - similarity) * maxOf(target.length, compareName.length)
                if (similarity > 0.94 || distance <= 1.1) {
                    debugLog(msg = "Fuzzy match found in root ($similarity | $distance): $name")
                    return File(kugouDir, name)
                }
            }
        }
        return null
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
        val str1 = s1.lowercase()
        val str2 = s2.lowercase()
        if (str1 == str2) return 1.0
        val len1 = str1.length
        val len2 = str2.length
        if (len1 == 0 || len2 == 0) return 0.0
        val dp = IntArray(len2 + 1) { it }
        for (i in 1..len1) {
            var prev = i
            var lastShow = i - 1
            for (j in 1..len2) {
                val next = if (str1[i - 1] == str2[j - 1]) lastShow else minOf(lastShow, prev, dp[j]) + 1
                lastShow = dp[j]
                dp[j] = next
                prev = next
            }
        }
        return 1.0 - dp[len2].toDouble() / maxOf(len1, len2)
    }
}