package io.github.mattpvaughn.chronicle.features.home

import android.app.Application
import android.app.Notification.Action
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.*
import com.google.android.material.internal.ContextUtils.getActivity
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.application.MainActivity.Companion.FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel
import io.github.mattpvaughn.chronicle.application.PlayRecentlyListenedActivity
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.getProgress
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import io.github.mattpvaughn.chronicle.features.download.DownloadNotificationWorker
import io.github.mattpvaughn.chronicle.features.library.LibraryViewModel
import io.github.mattpvaughn.chronicle.features.player.AudiobookPlaybackPreparer
import io.github.mattpvaughn.chronicle.util.DoubleLiveData
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.observeOnce
import io.github.mattpvaughn.chronicle.util.postEvent
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject

class HomeViewModel(
    private val plexConfig: PlexConfig,
    private val bookRepository: IBookRepository,
    private val trackRepository: ITrackRepository,
    private val prefsRepo: PrefsRepo
) : ViewModel() {

    @Suppress("UNCHECKED_CAST")
    class Factory @Inject constructor(
        private val plexConfig: PlexConfig,
        private val bookRepository: IBookRepository,
        private val trackRepository: ITrackRepository,
        private val prefsRepo: PrefsRepo
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                return HomeViewModel(
                    plexConfig,
                    bookRepository,
                    trackRepository,
                    prefsRepo
                ) as T
            } else {
                throw IllegalArgumentException("Cannot instantiate $modelClass from HomeViewModel.Factory")
            }
        }
    }

    private var _offlineMode = MutableLiveData(prefsRepo.offlineMode)
    val offlineMode: LiveData<Boolean>
        get() = _offlineMode


    fun makeOpenBookPendingIntent(bookId: Int): PendingIntent? {
        val applicationContext = Injector.get().applicationContext();
        val intent = Intent()
        val activity = applicationContext.packageManager.getPackageInfo(
            applicationContext.packageName,
            PackageManager.GET_ACTIVITIES
        ).activities.find { it.name.contains("MainActivity") }
        intent.setPackage(applicationContext.packageName)
        intent.putExtra(MainActivity.FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID, bookId)
        intent.component = ComponentName(applicationContext.packageName, activity?.name ?: "")
        return PendingIntent.getActivity(
            applicationContext,
            MainActivity.REQUEST_CODE_PREFIX_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID + bookId,
            intent,
            0
        )
    }

    val recentlyListened =
        DoubleLiveData(bookRepository.getRecentlyListened(), _offlineMode) { recents, offline ->
            val data: List<Audiobook> =
             if (offline == true) {
                recents?.filter { it.isCached }
            } else {
                Timber.i("Recently listened: $recents")
                recents
            } ?: emptyList()

//            val context = Injector.get().applicationContext();

//            ShortcutManagerCompat.removeAllDynamicShortcuts(context);
//
//            data.take(3).forEachIndexed { index, audiobook ->
//                val intent = Intent(context, MainActivity::class.java)
//                intent.putExtra(FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID, audiobook.id)
//                intent.putExtra(MainActivity.FLAG_FORCE_PLAY_AUDIOBOOK, true);
//
//                intent.action = Intent.ACTION_VIEW;
//                val shortcut = ShortcutInfoCompat.Builder(context, "recently-listened-$index")
//                    .setShortLabel(/* shortLabel = */ "Play - ${audiobook.titleDisplay}")
//                    .setLongLabel(/* longLabel = */ "Play - ${audiobook.titleDisplay}")
//                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_recent))
//                    .setIntent(intent)
//                    .build()
//
//                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
//            }

            return@DoubleLiveData data
        }

    private var _isRefreshing = MutableLiveData<Boolean>()
    val isRefreshing: LiveData<Boolean>
        get() = _isRefreshing

    private var _messageForUser = MutableLiveData<Event<String>>()
    val messageForUser: LiveData<Event<String>>
        get() = _messageForUser

    var recentlyAdded: DoubleLiveData<List<Audiobook>, Boolean, List<Audiobook>> =
        DoubleLiveData(bookRepository.getRecentlyAdded(), _offlineMode) { recents, offline ->
            /** We only want books which have actually been listened to! */
            if (offline == true) {
                return@DoubleLiveData recents?.filter { book -> book.isCached } ?: emptyList()
            } else {
                return@DoubleLiveData recents ?: emptyList()
            }
        }

    val downloaded: LiveData<List<Audiobook>> = bookRepository.getCachedAudiobooks()

    private var _isSearchActive = MutableLiveData<Boolean>()
    val isSearchActive: LiveData<Boolean>
        get() = _isSearchActive

    private var _searchResults = MutableLiveData<List<Audiobook>>()
    val searchResults: LiveData<List<Audiobook>>
        get() = _searchResults

    private val offlineModeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PrefsRepo.KEY_OFFLINE_MODE -> _offlineMode.postValue(prefsRepo.offlineMode)
                else -> { /* Do nothing */
                }
            }
        }

    private val serverConnectionObserver = Observer<Boolean> { isConnectedToServer ->
        if (isConnectedToServer) {
            viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
                val millisSinceLastRefresh =
                    System.currentTimeMillis() - prefsRepo.lastRefreshTimeStamp
                val minutesSinceLastRefresh = millisSinceLastRefresh / 1000 / 60
                val bookCount = bookRepository.getBookCount()
                val shouldRefresh =
                    minutesSinceLastRefresh > prefsRepo.refreshRateMinutes || bookCount == 0
                Timber.i("$minutesSinceLastRefresh minutes since last libraryrefresh,${prefsRepo.refreshRateMinutes} needed")
                if (shouldRefresh) {
                    refreshData()
                }
            }
        }
    }

    init {
        Timber.i("HomeViewModel init")
        if (plexConfig.isConnected.value == true) {
            // if already connected, call it just once
            serverConnectionObserver.onChanged(true)
        }
        plexConfig.isConnected.observeForever(serverConnectionObserver)
        prefsRepo.registerPrefsListener(offlineModeListener)
    }

    override fun onCleared() {
        plexConfig.isConnected.removeObserver(serverConnectionObserver)
        prefsRepo.unregisterPrefsListener(offlineModeListener)
        super.onCleared()
    }

    fun setSearchActive(isSearchActive: Boolean) {
        _isSearchActive.postValue(isSearchActive)
    }

    fun disableOfflineMode() {
        prefsRepo.offlineMode = false
    }

    /** Searches for books which match the provided text */
    fun search(query: String) {
        if (query.isEmpty()) {
            _searchResults.postValue(emptyList())
        } else {
            bookRepository.search(query).observeOnce(
                Observer {
                    _searchResults.postValue(it)
                }
            )
        }
    }

    /**
     * Pull most recent data from server and update repositories.
     *
     * Update book info for fields where child tracks serve as source of truth, like how
     * [Audiobook.duration] serves as a delegate for [List<MediaItemTrack>.getDuration()]
     *
     * TODO: migrate to [MainActivityViewModel] so code isn't duplicated b/w [HomeViewModel] and
     * [LibraryViewModel]
     */
    fun refreshData() {
        viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
            try {
                _isRefreshing.postValue(true)
                bookRepository.refreshDataPaginated()
                trackRepository.refreshDataPaginated()
            } catch (e: Throwable) {
                _messageForUser.postEvent("Failed to refresh data: ${e.message}")
            } finally {
                _isRefreshing.postValue(false)
            }

            // Update audiobooks which depend on track data
            val audiobooks = bookRepository.getAllBooksAsync()
            val tracks = trackRepository.getAllTracksAsync()
            audiobooks.forEach { book ->
                // TODO: O(n^2) so could be bad for big libs, grouping by tracks first would be O(n)

                // Not necessarily in the right order, but it doesn't matter for updateTrackData
                val tracksInAudiobook = tracks.filter { it.parentKey == book.id }
                bookRepository.updateTrackData(
                    bookId = book.id,
                    bookProgress = tracksInAudiobook.getProgress(),
                    bookDuration = tracksInAudiobook.getDuration(),
                    trackCount = tracksInAudiobook.size
                )
            }
        }
    }
}
