/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * 实现思路来自 qingning100607/LyricProvider-mi-music（Apache-2.0），
 * 已改写为本仓库 libxposed 入口风格，包名保持本模块。
 */

package io.github.proify.lyricon.miuiprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.highcapable.yukihookapi.extension.kavaref.toClass
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.miuiprovider.BuildConfig
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.qrckit.parser.QrcDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 小米音乐（com.miui.player，即 QQ 音乐小米版）歌词提供器 —— 走 QRC 逐字歌词。
 *
 * 取 songId：
 *  ① 权威：MusicPlayerHelper.getInstance().getPlaySong() → SongInfo.getId()（不联网）
 *  ② 兜底：MediaSession 的「歌名 + 歌手」搜 c.y.qq.com，歌名必须匹配才用
 *  ③ 再兜底：MEDIA_ID 若是 songmid，走 fcg_play_single_song
 * 拿到 songId 后 QrcDownloader.download(songId) → 已分轨 QRC → setSong。
 */
object MiuiMusic : YukiBaseHooker() {

    private const val TAG = "MiuiMusicProvider"
    private const val LOG_NAME = "lyricon_mi_debug.log"

    private const val MEDIA_SESSION_CLASS = "android.media.session.MediaSession"
    private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val SONG_MID_URL = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"

    /** MusicPlayerHelper 在不同版本里的包名（全部尝试） */
    private val HELPER_CLASSES = arrayOf(
        "com.tencent.qqmusiccommon.util.music.MusicPlayerHelper",   // 4.46.x
        "com.tencent.qqmusiccommon.util.dialog.MusicPlayerHelper"   // 4.42.x
    )

    private var provider: LyriconProvider? = null
    private var hostApp: Application? = null

    @Volatile private var helperHooked = false

    @Volatile private var currentSongId: String? = null
    @Volatile private var lastMid: String? = null

    @Volatile private var songTitle: String = ""
    @Volatile private var songArtist: String = ""
    @Volatile private var songDuration: Long = 0L

    private var lastSong: Song? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    override fun onHook() {
        flog("=== onHook: process=$processName ===")

        registerAppLifecycle(true) {
            onCreate {
                hostApp = this
                DiskSongCache.initialize(this)
                flog("app onCreate: dir=${filesDir.absolutePath}")
            }
            onTerminate { scope.cancel() }
        }

        runCatching { hookMediaSession() }.onFailure { flog("hookMediaSession failed: $it") }
        runCatching { ensureHelperHooked() }.onFailure { flog("hookPlayerHelper failed: $it") }
        scheduleHelperRetries()
    }

    private fun scheduleHelperRetries() {
        Thread {
            val delays = longArrayOf(1_000, 3_000, 6_000, 10_000, 15_000, 22_000)
            for (d in delays) {
                try {
                    Thread.sleep(d)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (helperHooked) return@Thread
                runCatching { ensureHelperHooked() }
            }
        }.apply { isDaemon = true }.start()
    }

    /* ------------------------------------------------------------------ */
    /* 入口：MusicPlayerHelper.getPlaySong()                               */
    /* ------------------------------------------------------------------ */

    private fun ensureHelperHooked(): Boolean {
        if (helperHooked) return true
        val loader = hostClassLoader ?: return false
        for (className in HELPER_CLASSES) {
            val cls = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: continue
            val hasTarget = runCatching {
                cls.declaredMethods.any { it.name == "getPlaySong" && it.parameterTypes.isEmpty() }
            }.getOrDefault(false)
            if (!hasTarget) {
                flog("△ $className 已加载但没有 getPlaySong")
                continue
            }
            cls.resolve().firstMethod {
                name = "getPlaySong"
                parameters()
            }.hook {
                after {
                    onSongInfo(result)
                }
            }
            helperHooked = true
            flog("✓ 安装成功: $className.getPlaySong()")
            return true
        }
        return false
    }

    private fun onSongInfo(songInfo: Any?) {
        if (songInfo == null) return
        val id = callLong(songInfo, "getId") ?: return
        if (id <= 0) return
        val idStr = id.toString()
        if (idStr == currentSongId) return
        callString(songInfo, "getName")?.takeIf { it.isNotBlank() }?.let { songTitle = it }
        callString(songInfo, "getSinger")?.takeIf { it.isNotBlank() }?.let { songArtist = it }
        callLong(songInfo, "getDuration")?.takeIf { it > 0 }?.let { songDuration = it }
        flog("SongInfo: id=$idStr title=$songTitle artist=$songArtist")
        push(idStr)
    }

    /* ------------------------------------------------------------------ */
    /* 入口：系统 MediaSession（换歌判定 + 兜底）                            */
    /* ------------------------------------------------------------------ */

    private fun hookMediaSession() {
        val loader = hostClassLoader ?: run {
            flog("✗ classloader 为空")
            return
        }

        MEDIA_SESSION_CLASS.toClass(loader)
            .resolve().apply {
                firstMethod {
                    name = "setMetadata"
                    parameters(MediaMetadata::class.java)
                }.hook {
                    after {
                        val md = args[0] as? MediaMetadata ?: return@after
                        onMetadata(md)
                    }
                }

                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        provider?.player?.setPlaybackState(args[0] as? PlaybackState)
                    }
                }
            }
        flog("hooked MediaSession")
    }

    private fun onMetadata(md: MediaMetadata) {
        val mid = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (mid.isNullOrBlank()) return
        // 同一条歌的元数据会被反复重设（小米音乐用它显示锁屏歌词），只处理一次
        if (mid == lastMid) return
        lastMid = mid

        md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?.let { songTitle = it }
        md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?.let { songArtist = it }
        md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }?.let { songDuration = it }
        flog("换歌: mid=$mid title=$songTitle artist=$songArtist dur=$songDuration")

        ensureHelperHooked()
        scope.launch { resolveAndPush(mid) }
    }

    /* ------------------------------------------------------------------ */
    /* 定位 songId：先权威，后搜索，再 mid                                   */
    /* ------------------------------------------------------------------ */

    private fun resolveAndPush(mid: String) {
        // 注意：这里绝不能按 mid 缓存 songId！SongInfo 的更新可能早于 MEDIA_ID 更新。
        readSongInfo()?.let { si ->
            val id = callLong(si, "getId")
            if (id != null && id > 0) {
                callString(si, "getName")?.takeIf { it.isNotBlank() }?.let { songTitle = it }
                callString(si, "getSinger")?.takeIf { it.isNotBlank() }?.let { songArtist = it }
                callLong(si, "getDuration")?.takeIf { it > 0 }?.let { songDuration = it }
                flog("SongInfo: id=$id title=$songTitle artist=$songArtist")
                push(id.toString())
                return
            }
        }

        flog("SongInfo 不可用，改用搜索")
        val found = resolveBySearch(songTitle, songArtist) ?: resolveByMid(mid)
        if (found == null) {
            flog("✗ 找不到这首歌: mid=$mid title=$songTitle artist=$songArtist")
            return
        }
        push(found)
    }

    /** 通过 getInstance().getPlaySong() 取当前歌曲对象（依次尝试各版本包名） */
    private fun readSongInfo(): Any? {
        val loader = hostClassLoader ?: return null
        for (className in HELPER_CLASSES) {
            val cls = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: continue
            val getInstance = runCatching { cls.getMethod("getInstance") }.getOrNull() ?: continue
            val instance = runCatching { getInstance.invoke(null) }.getOrNull() ?: continue
            val song = runCatching {
                instance.javaClass.getMethod("getPlaySong").invoke(instance)
            }.getOrNull()
            if (song != null) return song
        }
        return null
    }

    private fun resolveBySearch(title: String, artist: String): String? = runCatching {
        if (title.isBlank()) return@runCatching null
        val keyword = if (artist.isBlank()) title else "$title $artist"
        val url = "$SEARCH_URL?w=${URLEncoder.encode(keyword, "UTF-8")}" +
                "&format=json&p=1&n=5&cr=1&catZhida=1"
        val body = httpGet(url) ?: return@runCatching null
        val list = JSONObject(body).optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return@runCatching null
        if (list.length() == 0) return@runCatching null

        pickBest(title, artist, list)?.let { best ->
            val id = best.optLong("songid", 0L)
            flog("搜索「$keyword」→ 命中「${best.optString("songname")}」/ ${singersOf(best)} (songid=$id)")
            if (id > 0) return@runCatching id.toString()
        }
        flog("搜索「$keyword」没有足够匹配的结果")
        null
    }.onFailure { flog("resolveBySearch error: $it") }.getOrNull()

    private fun resolveByMid(mid: String): String? = runCatching {
        val body = httpGet("$SONG_MID_URL?songmid=$mid&platform=yqq&format=json") ?: return@runCatching null
        val arr = JSONObject(body).optJSONArray("data") ?: return@runCatching null
        if (arr.length() == 0) return@runCatching null
        val id = arr.getJSONObject(0).optLong("id", 0L)
        if (id > 0) id.toString() else null
    }.getOrNull()

    /** 挑最匹配的：歌名必须对上（分数门槛），歌手对上加分 */
    private fun pickBest(title: String, artist: String, list: JSONArray): JSONObject? {
        val nt = norm(title)
        val na = norm(artist)
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = norm(o.optString("songname"))
            val singers = norm(singersOf(o))
            var score = 0
            when {
                name == nt -> score += 4
                nt.isNotEmpty() && (name.contains(nt) || nt.contains(name)) -> score += 3
            }
            if (na.isNotEmpty() && singers.isNotEmpty() &&
                (singers.contains(na) || na.contains(singers))
            ) score += 2
            if (score > bestScore) {
                bestScore = score
                best = o
            }
        }
        return if (bestScore >= 3) best else null
    }

    private fun singersOf(o: JSONObject): String {
        val arr = o.optJSONArray("singer") ?: return ""
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        }
        return names.joinToString("/")
    }

    private fun norm(s: String): String = s.lowercase().replace(NORM_REGEX, "")

    private fun httpGet(urlStr: String): String? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://y.qq.com/")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) flog("HTTP $code for $urlStr")
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
        } finally {
            conn.disconnect()
        }
    }.onFailure { flog("httpGet error: $it") }.getOrNull()

    /* ------------------------------------------------------------------ */
    /* 推送                                                                */
    /* ------------------------------------------------------------------ */

    private fun push(songId: String) {
        if (songId == currentSongId) return
        currentSongId = songId
        flog("→ 推送 songId=$songId title=$songTitle")

        if (!ensureProvider()) {
            flog("✗ provider 未就绪")
            return
        }

        DiskSongCache.get(songId)?.let {
            flog("cache hit")
            setSong(it)
            return
        }

        // 显式空歌词：切歌瞬间先把上一首清掉，避免"新歌还没下载完时显示上一首的词"
        setSong(
            Song(
                id = songId,
                name = songTitle,
                artist = songArtist,
                duration = songDuration,
                lyrics = emptyList()
            )
        )

        if (!downloading.add(songId)) return
        scope.launch {
            try {
                val lines = QrcDownloader.download(songId).parsedLyric.richLyricLines
                flog("downloaded ${lines.size} lines for $songId")
                if (isInstrumentalPlaceholder(lines)) {
                    flog("纯音乐占位，跳过")
                    return@launch
                }
                if (lines.isNotEmpty()) {
                    val song = Song(
                        id = songId,
                        name = songTitle,
                        artist = songArtist,
                        duration = songDuration,
                        lyrics = lines.filterInvalidTranslation()
                    )
                    DiskSongCache.put(song)
                    if (songId == currentSongId) setSong(song)
                }
            } catch (e: Exception) {
                flog("✗ download failed for $songId: $e")
            } finally {
                downloading.remove(songId)
            }
        }
    }

    private fun ensureProvider(): Boolean {
        provider?.let { return true }
        val ctx = hostApp ?: return false
        synchronized(this) {
            provider?.let { return true }
            return runCatching {
                provider = LyriconFactory.createProvider(
                    context = ctx,
                    providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                    playerPackageName = Constants.MUSIC_PACKAGE_NAME,
                    logo = ProviderLogo.fromSvg(Constants.ICON)
                ).apply {
                    register()
                    player.setDisplayTranslation(true)
                }
                flog("provider registered")
                true
            }.onFailure { flog("✗ initProvider failed: $it") }.getOrDefault(false)
        }
    }

    private fun setSong(song: Song) {
        if (lastSong == song) return
        lastSong = song
        provider?.player?.setSong(song)
    }

    /** QQ 的翻译占位符 "//" 视为「无翻译」，置空后词幕按单行显示 */
    private fun List<RichLyricLine>.filterInvalidTranslation(): List<RichLyricLine> =
        onEach { line -> if (line.translation?.trim() == "//") line.translation = null }

    /** 纯音乐占位文档（"纯音乐，请欣赏"这类），不算歌词 */
    private fun isInstrumentalPlaceholder(lyrics: List<RichLyricLine>): Boolean {
        if (lyrics.size > 3) return false
        val text = lyrics.joinToString("") { it.text ?: "" }
        return text.contains("纯音乐") ||
                text.contains("没有填词") ||
                text.contains("instrumental") ||
                text.contains("Instrumental")
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                */
    /* ------------------------------------------------------------------ */

    private fun flog(msg: String) {
        // 正式版不带日志（release 里 BuildConfig.DEBUG = false，这段整个不会进来）
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, msg)
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} [$processName] $msg\n"
        LOG_PATHS.forEach { path ->
            runCatching {
                val f = File(path)
                f.parentFile?.mkdirs()
                f.appendText(line)
            }
        }
    }

    private fun callLong(target: Any, method: String): Long? =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? Long }.getOrNull()

    private fun callString(target: Any, method: String): String? =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? String }.getOrNull()

    private val NORM_REGEX = Regex("[\\s\\p{Punct}·・（）()【】\\[\\]《》～~、，。！？：；“”‘’—–]")

    private val LOG_PATHS = arrayOf(
        "/data/data/com.miui.player/files/$LOG_NAME",
        "/sdcard/Android/data/com.miui.player/files/$LOG_NAME"
    )
}
