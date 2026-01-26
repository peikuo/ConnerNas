package com.peik.cornernas

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.peik.cornernas.util.NetworkUtils
import com.peik.cornernas.util.applyKeepScreenOnIfEnabled
import com.peik.cornernas.util.clearKeepScreenOn

class VideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_video)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
    }

    override fun onStart() {
        super.onStart()
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val host = Uri.parse(url).host.orEmpty()
        if (!NetworkUtils.isLocalLanHost(host)) {
            Toast.makeText(this, getString(R.string.remote_host_not_local), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val view = findViewById<PlayerView>(R.id.player_view)
        val exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        view.player = exoPlayer
        player = exoPlayer
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOnIfEnabled(this)
    }

    override fun onPause() {
        clearKeepScreenOn(this)
        super.onPause()
    }

    override fun onStop() {
        player?.release()
        player = null
        super.onStop()
    }

    companion object {
        const val EXTRA_URL = "video_url"
        const val EXTRA_TITLE = "video_title"
    }
}
