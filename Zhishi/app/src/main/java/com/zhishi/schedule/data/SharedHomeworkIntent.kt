package com.zhishi.schedule.data

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

object SharedHomeworkIntent {
    fun isShare(intent: Intent) = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE

    fun files(intent: Intent): List<Uri> {
        if (!isShare(intent)) return emptyList()
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        val candidates = if (streams.isNotEmpty()) streams else intent.clipData?.let { clip ->
            (0 until minOf(clip.itemCount, 11)).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
        return candidates.filter { it.scheme == "content" }.distinct().take(11)
    }
}
