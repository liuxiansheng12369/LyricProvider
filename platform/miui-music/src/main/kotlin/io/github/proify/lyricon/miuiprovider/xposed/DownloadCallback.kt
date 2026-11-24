package io.github.proify.lyricon.miuiprovider.xposed

import io.github.proify.cloudlyric.ProviderLyrics

interface DownloadCallback {
    fun onDownloadFinished(response: List<ProviderLyrics>)
    fun onDownloadFailed(e: Exception)
}
