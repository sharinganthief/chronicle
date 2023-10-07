package io.github.mattpvaughn.chronicle.features.bookdetails

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.Quadruple
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.databinding.ListItemAudiobookTrackBinding
import io.github.mattpvaughn.chronicle.databinding.ListItemDiscNumberSectionHeadingBinding
import io.github.mattpvaughn.chronicle.features.bookdetails.ChapterListAdapter.ChapterListModel.ChapterItemModel
import io.github.mattpvaughn.chronicle.features.bookdetails.ChapterListAdapter.ChapterListModel.SectionHeaderWrapper
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingViewModel
import io.github.mattpvaughn.chronicle.util.DoubleLiveData
import timber.log.Timber


class ChapterListAdapter(val clickListener: TrackClickListener, val headerClickListener: HeaderClickListener) :
    ListAdapter<ChapterListAdapter.ChapterListModel, RecyclerView.ViewHolder>(
        ChapterItemDiffCallback()
    ) {


    /** Wrapper around [Chapter] and a section header */
    sealed class ChapterListModel {
        companion object {
            const val CHAPTER_TYPE = 1
            const val SECTION_HEADER_TYPE = 2
        }

        internal data class ChapterItemModel(val chapter: Chapter, val isActive: Boolean, val isVisible: Boolean) :
            ChapterListModel()

        internal data class SectionHeaderWrapper(val section: SectionHeaderModel) :
            ChapterListModel()
    }

    class SectionHeaderModel(val text: String, var disc: Int){
    }

    private var activeChapter = Triple(-1L, -1, -1L)
    private var hiddenDiscs = mutableListOf<Int>()
    private var downloadedTracks = mutableListOf<Int>()
    private var chapters = emptyList<Chapter>()

    private fun runChapters() {
        submitChapters(this.chapters)
        Handler(Looper.getMainLooper()).postDelayed({ this.notifyDataSetChanged() }, 500)
    }


    fun submitChapters(chapters: List<Chapter>?) {

        val firstChaps = !this.chapters.any();

        if (chapters != null) {
            this.chapters = chapters
        }

//        if(this.chapters != null && this.chapters.any()){
//            this.chapters.forEach {
//                if(downloadedTracks.contains(it.trackId.toInt())){
//                    it.downloaded = true
//                }
//            }
//        }

        var defaultDiscs = firstChaps && this.chapters.any();
        val discsToHide = mutableListOf<Int>()

        // Add disc headers only if necessary. We use disc numbers if the final track is owned by
        // a disc other than 1 (discNumber defaults to 1)
        if (!chapters.isNullOrEmpty() && chapters.last().discNumber > 1) {
            // iterate through chapters, insert section headers as indicated by [Chapter.discNumber]

            val firstChapter = chapters.first();

            if(defaultDiscs){
                this.hiddenDiscs.add(firstChapter.discNumber)
            }

            val discHeading = parseDiscHeading(firstChapter)

            val listWithSections = mutableListOf<ChapterListModel>()
            listWithSections.add(
                SectionHeaderWrapper(
                    SectionHeaderModel(
                        discHeading, firstChapter.discNumber
                    )
                )
            )
            var first = ChapterItemModel(firstChapter, isActive(firstChapter), isVisible(firstChapter))
            listWithSections.add(first)
            chapters.fold(firstChapter) { prev, curr ->
                // avoid edge cases at start/end, id is guaranteed to be different for unique
                // chapters/tracks by Plex
                if (curr.id == prev.id) {
                    return@fold curr
                }

                if (curr.discNumber > prev.discNumber) {

                    if(defaultDiscs){
                        hiddenDiscs.add(curr.discNumber)
                    }

                    val discHeading = parseDiscHeading(curr)

                    listWithSections.add(
                        SectionHeaderWrapper(
                            SectionHeaderModel(
                                discHeading, curr.discNumber
                            )
                        )
                    )
                }

                listWithSections.add(ChapterItemModel(curr, isActive(curr), isVisible(curr)))
                curr
            }

            super.submitList(listWithSections)
        }
        else {
            if (chapters.isNullOrEmpty()) {
                super.submitList(mutableListOf<ChapterListModel>())
            } else {
                super.submitList(chapters.map { ChapterItemModel(it, isActive(it), isVisible(it)) })
            }
        }
    }

    private fun parseDiscHeading(chapter: Chapter): String {

        if (!chapter.album.isNullOrEmpty()){
            return chapter.album
        }

        var parsed = parseDiscName(chapter.title)

        if (!parsed.first)
            return chapter.title

        if (parsed.third.isNullOrEmpty() && parsed.fourth.isNullOrEmpty() && !parsed.second.isNullOrEmpty())
             return parsed.second!!

        return "${parsed.third!!} - ${parsed.fourth!!}"
    }

    private val bookRegex = Regex("(.*?)\\s\\-\\sBooks?\\s([\\d|\\.|․|\\-|\\s]*)\\s\\-\\s(.*)")
    private val showRegex = Regex("(.*)\\s\\-\\sS(\\d+)E(\\d+)\\s\\-\\s(.*)")

    private fun parseDiscName(title: String): Quadruple<Boolean,String?, String?, String?> {

        val bookResult = bookRegex.find(title);
        if (bookResult == null || bookResult!!.groupValues.size <= 1) {
            val showResult = showRegex.find(title)
            if (showResult == null || showResult!!.groupValues.size <= 1) {
                return Quadruple(false,null, null, title)
            }

//            var seriesName = showResult!!.groupValues[1]
            var seasonNum = showResult!!.groupValues[2]
            var episodeNum = showResult!!.groupValues[3]
            var episodeTitle = showResult!!.groupValues[4]
            return Quadruple(true, "S${seasonNum}E${episodeNum} - $episodeTitle", null, null)

        }

        var seriesName = bookResult!!.groupValues[1];
        var bookNum = bookResult!!.groupValues[2];
        var bookTitle = bookResult!!.groupValues[3];

        if (seriesName.isNullOrEmpty() || bookNum.isNullOrEmpty() || bookTitle.isNullOrEmpty()) {
            return Quadruple(false, null, null, title);
        }

        return Quadruple(true, seriesName, bookNum, bookTitle);
    }

    fun isActive(chapter: Chapter) = chapter.trackId == activeChapter.first &&
        chapter.discNumber == activeChapter.second &&
        chapter.index == activeChapter.third

    private fun isVisible(chapter: Chapter): Boolean {
        return ! hiddenDiscs.contains(chapter.discNumber)
    };

    fun updateCurrentChapter(trackId: Long, discNumber: Int, chapterIndex: Long) {
        activeChapter = Triple(trackId, discNumber, chapterIndex)
        Timber.i("Updating current chapter: ($trackId, $discNumber, $chapterIndex)")
        submitChapters(chapters)
    }

    override fun submitList(list: MutableList<ChapterListModel>?) {
        throw IllegalAccessException("Clients must use ChapterListAdapter.submitChapters()")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            ChapterListModel.CHAPTER_TYPE -> ChapterViewHolder.from(parent, clickListener)
            ChapterListModel.SECTION_HEADER_TYPE -> SectionHeaderViewHolder.from(parent, headerClickListener)
            else -> throw NoWhenBranchMatchedException()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ChapterItemModel -> ChapterListModel.CHAPTER_TYPE
            is SectionHeaderWrapper -> ChapterListModel.SECTION_HEADER_TYPE
            else -> throw NoWhenBranchMatchedException()
        }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (viewHolder) {
            is ChapterViewHolder -> {
                viewHolder.bind(
                    (item as ChapterItemModel).chapter,
                    item.isActive,
                    item.isVisible
                )

                if(item.isVisible){
                    viewHolder.itemView.setVisibility(View.VISIBLE)
                    viewHolder.itemView.setLayoutParams(
                        RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                else{
                    viewHolder.itemView.setVisibility(View.GONE);
                    viewHolder.itemView.setLayoutParams(
                        RecyclerView.LayoutParams(0, 0))
                }
            }
            is SectionHeaderViewHolder -> viewHolder.bind((item as SectionHeaderWrapper).section)
            else -> throw NoWhenBranchMatchedException()
        }
    }

    fun toggleHiddenDisc(disc: Int) {
        if(this.hiddenDiscs.contains(disc)){
            this.hiddenDiscs.remove(disc)
        }
        else {
            this.hiddenDiscs.add(disc)
        }

        runChapters()
    }

    fun toggleDownloaded(track: Int) {
        if(this.downloadedTracks.contains(track)){
            this.downloadedTracks.remove(track)
        }
        else {
            this.downloadedTracks.add(track)
        }

        runChapters()
    }

    class ChapterViewHolder private constructor(
        private val binding: ListItemAudiobookTrackBinding,
        private val clickListener: TrackClickListener
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(chapter: Chapter, isActive: Boolean, isVisible: Boolean) {
            binding.chapter = chapter
            binding.isActive = isActive
            binding.isVisible = isVisible
            binding.clickListener = clickListener
//            binding.cacheListener = cacheListener
            binding.executePendingBindings()
        }

        companion object {
            fun from(parent: ViewGroup, clickListener: TrackClickListener): ChapterViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemAudiobookTrackBinding.inflate(layoutInflater, parent, false)
                return ChapterViewHolder(binding, clickListener)
            }
        }
    }

    class SectionHeaderViewHolder private constructor(
        private val binding: ListItemDiscNumberSectionHeadingBinding,
        private val headerClickListener: HeaderClickListener
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(heading: SectionHeaderModel) {
            binding.sectionHeader = heading
            binding.headerClickListener = headerClickListener;
            binding.executePendingBindings()
        }

        companion object {
            fun from(parent: ViewGroup, headerClickListener: HeaderClickListener): SectionHeaderViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding =
                    ListItemDiscNumberSectionHeadingBinding.inflate(layoutInflater, parent, false)
                return SectionHeaderViewHolder(binding, headerClickListener)
            }
        }
    }

    private class ChapterItemDiffCallback : DiffUtil.ItemCallback<ChapterListModel>() {
        override fun areItemsTheSame(
            oldItem: ChapterListModel,
            newItem: ChapterListModel
        ): Boolean {
            return when {
                oldItem is ChapterItemModel && newItem is ChapterItemModel -> {
                    oldItem.chapter.id == newItem.chapter.id
                }
                oldItem is SectionHeaderWrapper && newItem is SectionHeaderWrapper -> {
                    oldItem.section.text == newItem.section.text
                }
                else -> false
            }
        }

        /**
         * For the purposes of [ChapterListAdapter], the full content of the tracks should not be
         * compared, as certain fields like might differ but not require a redraw of the view
         */
        override fun areContentsTheSame(
            oldItem: ChapterListModel,
            newItem: ChapterListModel
        ): Boolean {
            return when {
                oldItem is ChapterItemModel && newItem is ChapterItemModel -> {
                    oldItem.chapter.index == newItem.chapter.index &&
                        oldItem.chapter.title == newItem.chapter.title &&
                        oldItem.isActive == newItem.isActive
                }
                oldItem is SectionHeaderWrapper && newItem is SectionHeaderWrapper -> {
                    oldItem.section.text == newItem.section.text
                }
                else -> false
            }
        }
    }
}

interface TrackClickListener {
    fun onClick(chapter: Chapter)
}

interface CacheStatusListener {
    fun getStatus(chapter: Chapter):Boolean
}

interface HeaderClickListener {
    fun onClick(section: ChapterListAdapter.SectionHeaderModel)
}
