package io.github.mattpvaughn.chronicle.data.model

import android.text.format.DateUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.util.DoubleLiveData
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@Entity
data class Chapter constructor(
    var title: String = "",
    val album: String = "",
    @PrimaryKey
    val id: Long = 0L,
    val index: Long = 0L,
    val discNumber: Int = 1,
    // The number of milliseconds from the start of the containing track and the start of the chapter
    val startTimeOffset: Long = 0L,
    // The number of milliseconds between the start of the containing track and the end of the chapter
    val endTimeOffset: Long = 0L,
    val downloaded: Boolean = false,
    val trackId: Long = TRACK_NOT_FOUND.toLong(),
    val bookId: Long = NO_AUDIOBOOK_FOUND_ID.toLong()
) : Comparable<Chapter> {

    val durationStr: String
        get() = DateUtils.formatElapsedTime(
            StringBuilder(),
            (endTimeOffset - startTimeOffset) / 1000
        )

    /** A string representing the index but padded to [length] characters with zeroes */
    fun paddedIndex(length: Int): String {
        return index.toString().padStart(length, '0')
    }

    override fun compareTo(other: Chapter): Int {
        val discCompare = discNumber.compareTo(other.discNumber)
        if (discCompare != 0) {
            return discCompare
        }
        return index.compareTo(other.index)
    }
}

val EMPTY_CHAPTER = Chapter("")

/**
 * Returns the chapter which contains the [timeStamp] (the playback progress of the track containing
 * this chapter), or [EMPTY_TRACK] if there is no chapter
 */
fun List<Chapter>.getChapterAt(trackId: Long, timeStamp: Long): Chapter {
    for (chapter in this) {
        if (chapter.trackId == trackId && timeStamp in chapter.startTimeOffset..chapter.endTimeOffset) {
            return chapter
        }
    }
    return EMPTY_CHAPTER
}

class ChapterListConverter {

    @TypeConverter
    fun toChapterList(s: String): List<Chapter> {
        if (s.isEmpty()) {
            return emptyList()
        }
        return s.split("®").map {
            val split = it.split("©")
            val discNumber = if (split.size >= 7) split[6].toInt() else 1
            val downloaded = if (split.size >= 8) split[7].toBoolean() else false
            val trackId = if (split.size >= 9) split[8].toLong() else TRACK_NOT_FOUND.toLong()
            val bookId = if (split.size >= 10) split[9].toLong() else NO_AUDIOBOOK_FOUND_ID.toLong()
            Chapter(
                title = split[0],
                album = split[1],
                id = split[2].toLong(),
                index = split[3].toLong(),
                startTimeOffset = split[4].toLong(),
                endTimeOffset = split[5].toLong(),
                discNumber = discNumber,
                downloaded = downloaded,
                trackId = trackId,
                bookId = bookId
            )
        }
    }

    // A little yikes but funny
    @TypeConverter
    fun toString(chapters: List<Chapter>): String {
        return chapters.joinToString("®") { "${it.title}©${it.album}©${it.id}©${it.index}©${it.startTimeOffset}©${it.endTimeOffset}©${it.discNumber}©${it.downloaded}©${it.trackId}©${it.bookId}" }
    }
}
