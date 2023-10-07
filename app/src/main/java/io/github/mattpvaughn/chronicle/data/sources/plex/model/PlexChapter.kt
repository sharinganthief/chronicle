package io.github.mattpvaughn.chronicle.data.sources.plex.model

import com.squareup.moshi.JsonClass
import io.github.mattpvaughn.chronicle.data.model.Chapter

@JsonClass(generateAdapter = true)
data class PlexChapter(
    var id: Long = 0L,
    val filter: String = "",
    var tag: String = "",
    val index: Long = 0L,
    val discNumber: Int = 0,
    val startTimeOffset: Long = 0L,
    val endTimeOffset: Long = 0L
)

fun PlexChapter.toChapter(trackId: Long, trackDiscNumber: Int, downloaded: Boolean): Chapter {
    return Chapter(
        title = tag.takeIf { it.isNotEmpty() } ?: "Chapter $index",
        id = id,
        index = index,
        discNumber = discNumber.takeIf { it != 0 } ?: trackDiscNumber,
        startTimeOffset = startTimeOffset,
        endTimeOffset = endTimeOffset,
        downloaded = downloaded,
        trackId = trackId
    )
}
