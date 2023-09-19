package io.github.mattpvaughn.chronicle.application

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.features.player.AudiobookPlaybackPreparer
import javax.inject.Inject

class PlayRecentlyListenedActivity : AppCompatActivity() {

    @Inject
    lateinit var preparer: AudiobookPlaybackPreparer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_currently_playing)

        val bundle: Bundle? = intent.extras
        val id = bundle?.get("id_value")

        intent.extras?.let { preparer.onPrepareFromMediaId(id as String, true, it) }

    }
}