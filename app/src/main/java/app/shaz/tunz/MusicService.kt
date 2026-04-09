package app.shaz.tunz

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import java.io.File
import java.net.URL


const val ACTION_PLAY_PAUSE = "app.shaz.tunz.PLAY_PAUSE"
const val ACTION_NEXT       = "app.shaz.tunz.NEXT"
const val CHANNEL_ID        = "tunz_playback"
const val NOTIF_ID          = 1


interface PlaybackCallback {
   fun onPlaylistReady   (play: List<String>)
   fun onSongChanged     (removedPos: Int, newPpos: Int)
   fun onAlbumArtChanged (art: Bitmap?)
}


class MusicService: Service ()
{  inner class MusicBinder: Binder ()
   {  fun getService (): MusicService = this@MusicService
   }

   private val binder = MusicBinder ()
   private var callback: PlaybackCallback? = null

   private var mplay: MediaPlayer? = null
   var mp3 = mutableListOf<FNList> ()
      private set

   var shuf: Boolean = true
      private set
   var pick = mutableListOf<String> ()
      private set
   private var done = mutableListOf<String> ()
   private var skip = mutableListOf<String> ()
   var play = mutableListOf<String> ()
      private set
   var song = ""
      private set
   var ppos = 0
      private set
   private var albumArt: Bitmap? = null

   private lateinit var mediaSession: MediaSessionCompat
   private lateinit var btDisco: BTDisco
   private val intentFilter = IntentFilter (AudioManager.ACTION_AUDIO_BECOMING_NOISY)

   fun setCallback (cb: PlaybackCallback?) { callback = cb }
   fun addPick    (dir: String) { if (!pick.contains (dir)) pick.add (dir) }
   fun removePick (dir: String) { pick.remove (dir) }
   fun setShuf    (v: Boolean) { shuf = v }

   fun togglePlayPause ()
   {  if (mplay?.isPlaying == true)  mplay?.pause ()
      else                           mplay?.start ()
      updateMediaSession ()
      postNotification ()
   }


   override fun onCreate ()
   {  super.onCreate ()
      mplay  = MediaPlayer ()
      btDisco = BTDisco (mplay!!)
      registerReceiver (btDisco, intentFilter)

      val p = getSharedPreferences ("prf", MODE_PRIVATE)
      shuf = p.getBoolean   ("shuf", true)
      pick = p.getStringSet ("pick", emptySet ())?.toMutableList () ?: mutableListOf ()
      done = p.getStringSet ("done", emptySet ())?.toMutableList () ?: mutableListOf ()
      skip = p.getStringSet ("skip", emptySet ())?.toMutableList () ?: mutableListOf ()

     val path = Environment.getExternalStorageDirectory ().toString () + "/Music/tunz"
     val mus  = File (path).listFiles () ?: emptyArray ()
     val dir  = mutableListOf<String> ()
      for (i in mus.indices) {
        val dn = mus [i].getName ()
         if (dn != ".thumbnails")  dir.add (dn)
      }
      dir.sort ()
      dir.forEach { d ->
        val dl = File ("$path/$d").listFiles ()
        val ls: Array<String> = dl!!.map { it.getName () }.toTypedArray ()
         ls.sort ()
         mp3.add (FNList (d, ls.toMutableList ()))
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel (CHANNEL_ID, "Playback",
                                      NotificationManager.IMPORTANCE_LOW)
         getSystemService (NotificationManager::class.java).createNotificationChannel (ch)
      }

      mediaSession = MediaSessionCompat (this, "TunzSession").apply {
         setCallback (object: MediaSessionCompat.Callback ()
         {  override fun onPlay ()         { mplay?.start (); updateMediaSession (); postNotification () }
            override fun onPause ()        { mplay?.pause (); updateMediaSession (); postNotification () }
            override fun onSkipToNext ()   { next () }
         })
         isActive = true
      }
   }


   override fun onBind (intent: Intent): IBinder = binder


   override fun onStartCommand (intent: Intent?, flags: Int, startId: Int): Int
   {  MediaButtonReceiver.handleIntent (mediaSession, intent)
      when (intent?.action) {
         ACTION_PLAY_PAUSE -> togglePlayPause ()
         ACTION_NEXT       -> next ()
      }
      return START_STICKY
   }


   override fun onDestroy ()
   {  super.onDestroy ()
      unregisterReceiver (btDisco)
      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.release ()
      mplay = null
      mediaSession.release ()

     val e = getSharedPreferences ("prf", MODE_PRIVATE).edit ()
      e.putBoolean   ("shuf", shuf).commit ()
      e.putStringSet ("pick", pick.toSet ()).commit ()
      e.putStringSet ("done", done.toSet ()).commit ()
      e.putStringSet ("skip", skip.toSet ()).commit ()

     val doneSnap = done.toList ()
     val skipSnap = skip.toList ()
      Thread {
         doneSnap.forEach { d ->
            try {
               URL ("https://shaz.app/song/did.php?did=$d").openConnection ().getInputStream ().close ()
            } catch (ex: Exception) { }
         }
         skipSnap.forEach { s ->
            try {
               URL ("https://shaz.app/song/skip.php?it=$s").openConnection ().getInputStream ().close ()
            } catch (ex: Exception) { }
         }
      }.start ()
   }


   override fun onTaskRemoved (rootIntent: Intent?)
   {  stopForeground (STOP_FOREGROUND_REMOVE)
      stopSelf ()
   }


   fun lyricsSearch ()
   {  val fnt   = splitfn (song)
      val query = Uri.encode ("${fnt.ttl} ${fnt.grp} lyrics")
      val uri   = Uri.parse ("https://www.google.com/search?q=$query")
      val i     = Intent (Intent.ACTION_VIEW, uri).addFlags (Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
         startActivity (i.setPackage ("com.android.chrome"))
      } catch (e: Exception) {
         startActivity (i.setPackage (null))
      }
   }


   private fun loadAlbumArt ()
   {  val path = Environment.getExternalStorageDirectory ().toString () + "/Music/tunz/$song"
      val mmr  = MediaMetadataRetriever ()
      albumArt = try {
         mmr.setDataSource (path)
         val bytes = mmr.embeddedPicture
         if (bytes != null)  BitmapFactory.decodeByteArray (bytes, 0, bytes.size)
         else                null
      } catch (e: Exception) { null }
      finally { mmr.release () }
      callback?.onAlbumArtChanged (albumArt)
   }


   private fun pick2play ()
   {  pick.forEach { p ->
         mp3.forEach { m ->
            if (p == m.dir)  m.fn.forEach { fn ->
               play.add ("$p/$fn")
            }
         }
      }
   }


   fun rePlay ()
   {  val path = Environment.getExternalStorageDirectory ().toString () + "/Music/tunz"
      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.reset ()
      play.clear ()
      if (pick.isEmpty ()) {
         callback?.onPlaylistReady (play)
         return
      }
      pick2play ()
      if (shuf) {
         play.removeAll (done)
         if (play.isEmpty ()) {
            done.clear ()
            pick2play ()
         }
         play.shuffle ()
      }
      else
         play.sortBy { fmtfn (it).toString () }
      ppos = 0
      song = play [ppos]
      loadAlbumArt ()
      mplay?.setDataSource ("$path/$song")
      mplay?.prepare ()
      mplay?.start ()
      mplay?.setOnCompletionListener { next () }
      updateMediaSession ()
      postNotification ()
      callback?.onPlaylistReady (play.toList ())
   }


   fun next (row: Int = -1)
   {  val path = Environment.getExternalStorageDirectory ().toString () + "/Music/tunz"
      mplay?.stop ()
      mplay?.reset ()
      Log.d ("Files", "song done row=$row")
     val removedPos: Int
      if (row == -1) {
         done.add (song)
         removedPos = ppos
         play.removeAt (ppos)
      }
      else {
         if (! skip.contains (song))  skip.add (song)
         removedPos = -1
         ppos = row
      }
      if (ppos < play.size) {
         song = play [ppos]
         loadAlbumArt ()
         mplay?.setDataSource ("$path/$song")
         mplay?.prepare ()
         mplay?.start ()
         mplay?.setOnCompletionListener { next () }
         updateMediaSession ()
         postNotification ()
      }
      callback?.onSongChanged (removedPos, ppos)
   }


   private fun updateMediaSession ()
   {  if (song.isEmpty ())  return
     val state = if (mplay?.isPlaying == true)  PlaybackStateCompat.STATE_PLAYING
                 else                           PlaybackStateCompat.STATE_PAUSED
      mediaSession.setPlaybackState (
         PlaybackStateCompat.Builder ()
            .setState (state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions (PlaybackStateCompat.ACTION_PLAY          or
                         PlaybackStateCompat.ACTION_PAUSE         or
                         PlaybackStateCompat.ACTION_PLAY_PAUSE    or
                         PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            .build ()
      )
     val fnt = splitfn (song)
      val meta = MediaMetadataCompat.Builder ()
         .putString (MediaMetadataCompat.METADATA_KEY_TITLE,  fnt.ttl)
         .putString (MediaMetadataCompat.METADATA_KEY_ARTIST, fnt.grp)
         .putString (MediaMetadataCompat.METADATA_KEY_ALBUM,  fnt.x)
      if (albumArt != null)
         meta.putBitmap (MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
      mediaSession.setMetadata (meta.build ())
   }


   private fun postNotification ()
   {  if (song.isEmpty ())  return
     val isPlaying = mplay?.isPlaying == true
     val fnt       = splitfn (song)

     val piMain = PendingIntent.getActivity (
                     this, 0,
                     Intent (this, MainActivity::class.java),
                     PendingIntent.FLAG_IMMUTABLE)
     val piPlayPause = PendingIntent.getService (
                          this, 1,
                          Intent (this, MusicService::class.java).setAction (ACTION_PLAY_PAUSE),
                          PendingIntent.FLAG_IMMUTABLE)
     val piNext = PendingIntent.getService (
                     this, 2,
                     Intent (this, MusicService::class.java).setAction (ACTION_NEXT),
                     PendingIntent.FLAG_IMMUTABLE)

     val ppIcon = if (isPlaying)  android.R.drawable.ic_media_pause
                  else            android.R.drawable.ic_media_play
     val ppLabel = if (isPlaying)  "Pause"  else  "Play"

     val notif = NotificationCompat.Builder (this, CHANNEL_ID)
        .setSmallIcon        (R.drawable.outline_music_cast_24)
        .setLargeIcon        (albumArt)
        .setContentTitle     (fnt.ttl)
        .setContentText      (fnt.grp)
        .setContentIntent    (piMain)
        .setVisibility       (NotificationCompat.VISIBILITY_PUBLIC)
        .addAction           (ppIcon, ppLabel, piPlayPause)
        .addAction           (android.R.drawable.ic_media_next, "Next", piNext)
        .setStyle            (MediaStyle ()
                                 .setMediaSession (mediaSession.sessionToken)
                                 .setShowActionsInCompactView (0, 1))
        .build ()

      startForeground (NOTIF_ID, notif)
   }
}


class BTDisco (private val mp: MediaPlayer): BroadcastReceiver ()
{  override fun onReceive (context: Context, intent: Intent)
   {  if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
         if (mp.isPlaying)  mp.pause ()
   }
}
