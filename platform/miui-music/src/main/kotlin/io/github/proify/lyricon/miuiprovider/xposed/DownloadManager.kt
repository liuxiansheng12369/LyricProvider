package io.github.proify.lyricon.miuiprovider.xposed

import io.github.proify.cloudlyric.CloudLyrics
import io.github.proify.cloudlyric.SearchOptions
import io.github.proify.cloudlyric.provider.qq.QQMusicProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object DownloadManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val cloudLyrics = CloudLyrics(
        listOf(QQMusicProvider())
    )

    private var currentTask: Job? = null

    fun search(downloadCallback: DownloadCallback, block: SearchOptions.() -> Unit) {
        currentTask = scope.launch {
            try {
                val response = cloudLyrics.search(block)
                downloadCallback.onDownloadFinished(response)
            } catch (e: Exception) {
                downloadCallback.onDownloadFailed(e)
            }
        }
    }

    fun cancel() {
        currentTask?.cancel()
        currentTask = null
    }
}
