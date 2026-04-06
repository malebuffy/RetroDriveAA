package com.codeodyssey.retrodriveaa.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.codeodyssey.retrodriveaa.R

class RetroDriveMediaRoutingService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var currentTitle = ""
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (mediaSession.isActive) {
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                pausePlaybackSession()
                mediaSession.isActive = false
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                pausePlaybackSession()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
        currentTitle = getString(R.string.media_session_root_title)

        mediaSession = MediaSessionCompat(this, TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    startPlaybackSession(currentTitle)
                }

                override fun onPause() {
                    pausePlaybackSession()
                }

                override fun onStop() {
                    stopPlaybackSession()
                }
            })
        }
        sessionToken = mediaSession.sessionToken
        updateMetadata(currentTitle)
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START,
            ACTION_RESUME -> {
                currentTitle = intent.getStringExtra(EXTRA_SESSION_TITLE).orEmpty().ifBlank { getString(R.string.media_session_root_title) }
                startPlaybackSession(currentTitle)
            }

            ACTION_PAUSE -> pausePlaybackSession()
            ACTION_STOP -> stopPlaybackSession()
        }
        return START_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }

        val description = MediaDescriptionCompat.Builder()
            .setMediaId(ITEM_ID)
            .setTitle(getString(R.string.media_session_root_title))
            .setSubtitle(currentTitle)
            .build()
        result.sendResult(
            mutableListOf(
                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
            )
        )
    }

    override fun onDestroy() {
        releaseAudioFocus()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun startPlaybackSession(title: String) {
        currentTitle = title
        updateMetadata(title)
        requestAudioFocus()
        mediaSession.isActive = true
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        startForegroundNotification(buildNotification(title))
    }

    private fun pausePlaybackSession() {
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        releaseAudioFocus()
        mediaSession.isActive = true
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
    }

    private fun stopPlaybackSession() {
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        releaseAudioFocus()
        mediaSession.isActive = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun updateMetadata(title: String) {
        val mediaIcon = createGrayscaleAppIcon()
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(ITEM_ID)
            .setTitle(title)
            .setSubtitle(getString(R.string.media_session_root_title))
            .setIconBitmap(mediaIcon)
            .build()
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ITEM_ID)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                    getString(R.string.media_session_root_title)
                )
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, mediaIcon)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, mediaIcon)
                .build()
        )
        mediaSession.setQueue(
            listOf(MediaSessionCompat.QueueItem(description, 1L))
        )
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) {
            return
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setWillPauseWhenDucked(true)
                    .build()
            }
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseAudioFocus() {
        if (!hasAudioFocus) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun buildNotification(title: String): Notification {
        val mediaIcon = createGrayscaleAppIcon()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(getString(R.string.media_session_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken))
        mediaIcon?.let(builder::setLargeIcon)
        return builder.build()
    }

    private fun createGrayscaleAppIcon(): Bitmap? {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_rd_media_icon) ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 108
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 108
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(source)
        drawable.setBounds(0, 0, sourceCanvas.width, sourceCanvas.height)
        drawable.draw(sourceCanvas)

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun startForegroundNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.media_session_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.codeodyssey.retrodriveaa.media.action.START"
        const val ACTION_RESUME = "com.codeodyssey.retrodriveaa.media.action.RESUME"
        const val ACTION_PAUSE = "com.codeodyssey.retrodriveaa.media.action.PAUSE"
        const val ACTION_STOP = "com.codeodyssey.retrodriveaa.media.action.STOP"
        const val EXTRA_SESSION_TITLE = "com.codeodyssey.retrodriveaa.media.extra.SESSION_TITLE"

        private const val TAG = "RetroDriveMedia"
        private const val ROOT_ID = "retrodrive_media_root"
        private const val ITEM_ID = "retrodrive_media_item"
        private const val CHANNEL_ID = "retrodrive_media_audio"
        private const val NOTIFICATION_ID = 4021
    }
}