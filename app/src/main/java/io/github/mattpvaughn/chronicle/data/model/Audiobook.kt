package io.github.mattpvaughn.chronicle.data.model

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import io.github.mattpvaughn.chronicle.data.sources.MediaSource
import io.github.mattpvaughn.chronicle.data.sources.MediaSource.Companion.NO_SOURCE_FOUND
import io.github.mattpvaughn.chronicle.data.sources.SourceManager
import io.github.mattpvaughn.chronicle.data.sources.plex.*
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import io.github.mattpvaughn.chronicle.features.player.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TypeConverters(ChapterListConverter::class)
@Entity
data class Audiobook constructor(
    @PrimaryKey
    val id: Int,
    /** Unique long representing a [MediaSource] in [SourceManager] */
    val source: Long,
    val title: String = "",
    var titleDisplay: String = "",
    var subTitle: String = "",
    var titleSearch: String = "",
    val titleSort: String = "",
    val author: String = "",
    val thumb: String = "",
    val parentId: Int = -1,
    val genre: String = "",
    val summary: String = "",
    val year: Int = 0,
    val addedAt: Long = 0L,
    /** last Unix timestamp that some metadata was changed in server */
    val updatedAt: Long = 0L,
    /** last Unix timestamp that the book was listened to */
    val lastViewedAt: Long = 0L,
    /** duration of the entire audiobook in milliseconds */
    val duration: Long = 0L,
    /** Whether the book is cached by [ICachedFileManager]*/
    val isCached: Boolean = false,
    /** The current progress into the audiobook in milliseconds */
    val progress: Long = 0L,
    val favorited: Boolean = false,
    /** The number of time's individual tracks have been completed */
    val viewedLeafCount: Long = 0L,
    /** The number of tracks in the book */
    val leafCount: Long = 0L,
    /** The number of times the book has been listened to */
    val viewCount: Long = 0L,
    /** Chapter metadata corresponding to m4b chapter metadata in the m4b files */
    var chapters: List<Chapter> = emptyList(),
) {

    companion object {
        fun from(dir: PlexDirectory) : Audiobook {
            val collections = dir.collections?.map{ it.tag}?.joinToString(separator = " ")

            return Audiobook(
                id = dir.ratingKey.toInt(),
                source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
                title = dir.title,
                titleDisplay = dir.title,
                subTitle = "",
                titleSearch = getTitleSearch(dir.title, dir.parentTitle, collections),
                titleSort = dir.titleSort.takeIf { it.isNotEmpty() } ?: dir.title,
                author = dir.parentTitle,
                thumb = dir.thumb,
                parentId = dir.parentRatingKey,
                genre = dir.plexGenres.joinToString(separator = ", "),
                summary = dir.summary,
                year = dir.year.takeIf { it != 0 } ?: dir.parentYear,
                addedAt = dir.addedAt,
                updatedAt = dir.updatedAt,
                lastViewedAt = dir.lastViewedAt,
                viewedLeafCount = dir.viewedLeafCount,
                leafCount = dir.leafCount,
                viewCount = dir.viewCount,
            )
        }

        private fun getTitleSearch(bookTitle: String, author: String, collections: String?): String {
            return ("$bookTitle $author $collections").replace("[^A-Za-z0-9 ]".toRegex(), "").lowercase().trim();
        }

        private fun getDisplayTitle(bookTitle: String): String {
            val regex = Regex("(.*?)\\s\\-\\s(Books?\\s[\\d|\\.|․|\\-|\\s]*)\\s\\-\\s(.*)");
            var matchResult = regex.find(bookTitle);
            if (matchResult == null || matchResult!!.groupValues.size <= 1) {
                return bookTitle
            }

            var seriesName = matchResult!!.groupValues[1];
            var bookNum = matchResult!!.groupValues[2];
            var bookTitle = matchResult!!.groupValues[3];

            if (seriesName.isNullOrEmpty() || bookNum.isNullOrEmpty() || bookTitle.isNullOrEmpty()) {
                return "";
            }

            return  seriesName + System.lineSeparator() + bookNum.replace("Book ", "");
        }

        /**
         * Merges updated local fields with a network copy of the book. Respects network metadata
         * as the authoritative source of truth with the follow exceptions:
         *
         * Retains the following local fields only if the local copy is more recent: [lastViewedAt].
         * This is because even if the network copy is more up to date, retaining the most recent
         * [lastViewedAt] from the local copy is preferred.
         *
         * Always retain fields from local copy: [duration], [isCached], [favorited], [chapters],
         * [source]. We retain [chapters], [duration], and [progress] because they can be calculated
         * only when all child [MediaItemTrack]'s of the Audiobook are loaded. We retain [duration],
         * [source], and [isCached] because they are explicitly local values, they do not even exist
         * on the server.
         */
        fun merge(network: Audiobook, local: Audiobook, forceNetwork: Boolean = false): Audiobook {

            var finalBook: Audiobook

            if (network.lastViewedAt > local.lastViewedAt || forceNetwork) {
                finalBook = network.copy(
                    duration = local.duration,
                    progress = local.progress,
                    isCached = local.isCached,
                    favorited = local.favorited,
                    chapters = local.chapters,
                    source = local.source,
                )
            } else {
                finalBook = network.copy(
                    duration = local.duration,
                    progress = local.progress,
                    source = local.source,
                    isCached = local.isCached,
                    lastViewedAt = local.lastViewedAt,
                    favorited = local.favorited,
                    chapters = local.chapters,
                )
            }

            if (!finalBook.chapters.isNullOrEmpty()){
                val firstChapter = finalBook.chapters.first();
                val lastChapter = finalBook.chapters.last();

                var discs = mutableListOf<Triple<String, String, String>>()

                //if we have multiple discs
                if (lastChapter.discNumber > firstChapter.discNumber) {

                    val parsedFirst = parseDiscName(firstChapter.title);
                    if(parsedFirst.third.length > 2)
                        discs.add(parsedFirst)

                    finalBook.chapters.fold(firstChapter) { prev, curr ->
                        if(prev.discNumber < curr.discNumber){
                            val parsed = parseDiscName(curr.title);
                            if(parsed.third.length > 2)
                                discs.add(parsed)
                        }
                        curr
                    }

                    val titles: List<String> = discs.map { it.third.lowercase() }
//                    val nums: List<String> = discs.map { it.second }

                    val allSearchTerms = mutableListOf(finalBook.titleSearch)
                    allSearchTerms.addAll(titles);
                    val uniqueSearchTerms = allSearchTerms.distinct()

                    finalBook.titleSearch = uniqueSearchTerms.joinToString(" " ).replace("[^A-Za-z0-9 ]".toRegex(), "")
//                    finalBook.subTitle = "${nums.first()} - ${nums.last()}"
//                    finalBook.titleDisplay = finalBook.title + System.lineSeparator() + finalBook.subTitle
                }
            }

            return finalBook;
        }

        private val regex = Regex("(.*?)\\s\\-\\sBooks?\\s([\\d|\\.|․|\\-|\\s]*)\\s\\-\\s(.*)")

        private fun parseDiscName(title: String): Triple<String, String,String> {

            var matchResult = regex.find(title);
            if (matchResult == null || matchResult!!.groupValues.size <= 1) {
                return Triple("", "", title);
            }

            var seriesName = matchResult!!.groupValues[1];
            var bookNum = matchResult!!.groupValues[2];
            var bookTitle = matchResult!!.groupValues[3];

            if (seriesName.isNullOrEmpty() || bookNum.isNullOrEmpty() || bookTitle.isNullOrEmpty()) {
                return Triple("", "", title);
            }

            return Triple(seriesName, bookNum, bookTitle);
        }

        const val SORT_KEY_TITLE = "title"
        const val SORT_KEY_RANDOM = "random"
        const val SORT_KEY_AUTHOR = "author"
        const val SORT_KEY_GENRE = "title"
        const val SORT_KEY_RELEASE_DATE = "release_date"
        const val SORT_KEY_YEAR = "year"
        const val SORT_KEY_DURATION = "duration"
        const val SORT_KEY_RATING = "rating"
        const val SORT_KEY_CRITIC_RATING = "critic_rating"
        const val SORT_KEY_DATE_ADDED = "date_added"
        const val SORT_KEY_DATE_PLAYED = "date_played"
        const val SORT_KEY_PLAYS = "plays"

        val SORT_KEYS = listOf(
            SORT_KEY_TITLE,
//            SORT_KEY_RANDOM,
            SORT_KEY_AUTHOR,
            SORT_KEY_GENRE,
            SORT_KEY_RELEASE_DATE,
            SORT_KEY_YEAR,
            SORT_KEY_RATING,
            SORT_KEY_CRITIC_RATING,
            SORT_KEY_DATE_ADDED,
            SORT_KEY_DATE_PLAYED,
            SORT_KEY_PLAYS,
            SORT_KEY_DURATION
        )
    }
}

fun Audiobook.toAlbumMediaMetadata(): MediaMetadataCompat {
    val metadataBuilder = MediaMetadataCompat.Builder()
    metadataBuilder.id = this.id.toString()
    metadataBuilder.title = this.title
    metadataBuilder.displayTitle = this.titleDisplay
    metadataBuilder.displaySubtitle = this.subTitle
    metadataBuilder.albumArtUri = this.thumb
    metadataBuilder.album = this.title
    metadataBuilder.artist = this.author
    metadataBuilder.genre = this.genre
    return metadataBuilder.build()
}

/**
 * Converts an audiobook to a [MediaBrowserCompat.MediaItem] for use in
 * [androidx.media.MediaBrowserServiceCompat.onSearch] and
 * [androidx.media.MediaBrowserServiceCompat.onLoadChildren], and respective clients
 */
fun Audiobook.toMediaItem(plexConfig: PlexConfig): MediaBrowserCompat.MediaItem {
    val mediaDescription = MediaDescriptionCompat.Builder()
    mediaDescription.setTitle(title)
    mediaDescription.setMediaId(id.toString())
    mediaDescription.setSubtitle(author)
    mediaDescription.setIconUri(plexConfig.makeThumbUri(this.thumb))
    val extras = Bundle()
    extras.putBoolean(EXTRA_IS_DOWNLOADED, isCached)
    extras.putInt(
        EXTRA_PLAY_COMPLETION_STATE,
        if (progress == 0L) {
            STATUS_NOT_PLAYED
        } else {
            STATUS_PARTIALLY_PLAYED
        }
    )
    mediaDescription.setExtras(extras)

    return MediaBrowserCompat.MediaItem(mediaDescription.build(), FLAG_PLAYABLE)
}

fun Audiobook.isCompleted(): Boolean {
    return progress < 10.seconds.inWholeMilliseconds || progress > (duration - 2.minutes.inWholeMilliseconds)
}

fun Audiobook.uniqueId(): Int {
    return (source * id).toInt()
}

const val NO_AUDIOBOOK_FOUND_ID = -22321
const val NO_AUDIOBOOK_FOUND_TITLE = "No audiobook found"
val EMPTY_AUDIOBOOK = Audiobook(NO_AUDIOBOOK_FOUND_ID, NO_SOURCE_FOUND, NO_AUDIOBOOK_FOUND_TITLE)
