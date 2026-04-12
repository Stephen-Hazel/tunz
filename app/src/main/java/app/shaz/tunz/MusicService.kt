// MusicService.kt - need a "foreground service" so we can pop lyrics and
//    do, uhh, a bunch of stuff while music still plays

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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.net.HttpURLConnection
import java.net.URL


// setup play/pause/skip api
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
{  inner class MusicBinder: Binder () {
      fun getService (): MusicService = this@MusicService
   }

   private val binder = MusicBinder ()
   private var callback: PlaybackCallback? = null

   private var mplay: MediaPlayer? = null
   var mp3 = mutableListOf<FNList> ()
       private set
   var anNst: Boolean = false          // only do website stuff when me n annie
   var path = ""
       private set
   var shuf:  Boolean = true
       private set
   var pick = mutableListOf<String> ()
       private set
   private var done = mutableListOf<String> ()
   var play = mutableListOf<String> ()
       private set
   var dl = mutableListOf<String> ()
       private set
   var song = ""
       private set
   var ppos = 0
       private set
   private var albumArt: Bitmap? = null

   private lateinit var mediaSession: MediaSessionCompat

// if we get disco'd from bluetooth shuuut uuupp
   private lateinit var btDisco: BTDisco
   private val intentFilter = IntentFilter (
      AudioManager.ACTION_AUDIO_BECOMING_NOISY)

   fun setCallback (cb: PlaybackCallback?) { callback = cb }
   fun addPick    (dir: String) { if (! pick.contains (dir))  pick.add (dir) }
   fun removePick (dir: String) { pick.remove (dir) }
   fun setShuf    (v: Boolean)  { shuf = v }

   fun wifiok (): Boolean
   { val cm = getSystemService (CONNECTIVITY_SERVICE) as ConnectivityManager
     val nc = cm.getNetworkCapabilities (cm.activeNetwork)
      return (nc?.hasTransport (NetworkCapabilities.TRANSPORT_WIFI) == true)
   }

   fun cellok (): Boolean
   { val cm = getSystemService (CONNECTIVITY_SERVICE) as ConnectivityManager
     val nc = cm.getNetworkCapabilities (cm.activeNetwork)
      return (nc?.hasTransport (NetworkCapabilities.TRANSPORT_CELLULAR) ==true)
   }

   fun online (): Boolean
   {  if (wifiok () || cellok ())  return true
      return false
   }

   fun togglePlayPause ()
   {  if (mplay?.isPlaying == true)  mplay?.pause ()
      else                           mplay?.start ()
      updateMediaSession ()
      postNotification ()
   }


   override fun onCreate ()
   {  super.onCreate ()

      mplay = MediaPlayer ()
      btDisco = BTDisco (mplay!!)
      registerReceiver (btDisco, intentFilter)

   // all our mp3 files are in single level dirs under /Music/tunz
      path = Environment.getExternalStorageDirectory ().toString () +
                                                                   "/Music/tunz"
   // load shuf,picked dirs from last time
     val p = getSharedPreferences ("prf", MODE_PRIVATE)
      shuf = p.getBoolean   ("shuf", true)
      pick = p.getStringSet ("pick", emptySet ())?.toMutableList () ?:
                                                                mutableListOf ()
   // and our done list so we don't hear ANY repeats
      done = p.getStringSet ("done", emptySet ())?.toMutableList () ?:
                                                                mutableListOf ()
      if (anNst && online ()) {
      // merge shaz.app/song/did.txt into our done list
         try {
           val dc  = URL ("https://shaz.app/song/did.txt")
                        .openConnection () as HttpURLConnection
            dc.connectTimeout = 8000
            dc.readTimeout    = 8000
           val rmt = dc.inputStream.bufferedReader ().readText ()
            rmt.lines ().filter { it.isNotBlank () && ! done.contains (it) }
                        .forEach { done.add (it) }
         }
         catch (ex: Exception) { }
      }
   // ok, list off each dir in path
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

   // setup our foreground service junk
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel (CHANNEL_ID, "Playback",
                                      NotificationManager.IMPORTANCE_LOW)
         getSystemService (NotificationManager::class.java
                                                ).createNotificationChannel (ch)
      }
      mediaSession = MediaSessionCompat (this, "TunzSession").apply {
         setCallback (object: MediaSessionCompat.Callback ()
         {  override fun onPlay ()
            {  mplay?.start ()
               updateMediaSession ()
               postNotification ()
            }

            override fun onPause ()
            {  mplay?.pause ()
               updateMediaSession ()
               postNotification ()
            }

            override fun onSkipToNext ()
            {  next ()
            }
         })
         isActive = true
      }

   // if we're hooked to wifi, get the songs on https://shaz/song and
   //    refresh our local mp3 files (kill missing ones, download new ones)
      if (anNst && wifiok ()) {
         Thread {
            try {
            // download list of what shaz.app/song has
              val con = URL ("https://shaz.app/song/list.php")
                           .openConnection () as HttpURLConnection
               con.connectTimeout = 8000
               con.readTimeout    = 8000
              val txt = con.inputStream.bufferedReader ().readText ()
               dl.addAll (txt.lines ().filter { it.isNotBlank () })

            // any local file not in dl gets killed
              val local = mp3.flatMap { m -> m.fn.map { "${m.dir}/$it" } }
              var changed = false
               local.forEach { rel ->
                  if (! dl.contains (rel)) {
                     File ("$path/$rel").delete ()
                     changed = true
                  }
               }

            // any dl file not here gets downloaded
               dl.forEach { rel ->
                 val f = File ("$path/$rel")
                  if (! f.exists ()) {
                     f.parentFile?.mkdirs ()
                     try {
                       val dc = URL ("https://shaz.app/song/song/$rel")
                                   .openConnection () as HttpURLConnection
                        dc.connectTimeout = 8000
                        dc.readTimeout    = 60000
                       val bytes = dc.inputStream.readBytes ()
                        f.writeBytes (bytes)
                        changed = true
                     }
                     catch (ex: Exception) { }
                  }
               }

               if (changed) {
               // gotta rebuild what we did above - but this'll be signif later
                  mp3.clear ()
                 val mus  = File (path).listFiles () ?: emptyArray ()
                 val dirs = mutableListOf<String> ()
                  mus.forEach { if (it.name != ".thumbnails")
                                   dirs.add (it.name) }
                  dirs.sort ()
                  dirs.forEach { d ->
                    val ls = File ("$path/$d").listFiles ()
                                ?.map { it.name }?.sorted ()
                                ?: emptyList ()
                     mp3.add (FNList (d, ls.toMutableList ()))
                  }
                  rePlay ()
               }
            }
            catch (ex: Exception) { }
         }.start ()
      }
   }


// more foreground service silliness - glad i have AI now cuz this looks duuuumb
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
   // shut it all down
   {  super.onDestroy ()
      unregisterReceiver (btDisco)
      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.release ()
      mplay = null
      mediaSession.release ()

   // store our shuf,dir picks n done songs for next time
     val e = getSharedPreferences ("prf", MODE_PRIVATE).edit ()
      e.putBoolean   ("shuf", shuf).commit ()
      e.putStringSet ("pick", pick.toSet ()).commit ()
      e.putStringSet ("done", done.toSet ()).commit ()
   }


   override fun onTaskRemoved (rootIntent: Intent?)
   {  stopForeground (STOP_FOREGROUND_REMOVE)
      stopSelf ()
   }


   fun lyricsSearch ()
   // boot chrome and pass google our song title,artist in hopes o gettin lyrics
   { val fnt   = splitfn (song)
     val query = Uri.encode ("${fnt.ttl} ${fnt.grp} lyrics")
     val uri   = Uri.parse ("https://www.google.com/search?q=$query")
     val i     = Intent (Intent.ACTION_VIEW, uri).addFlags (
                                                  Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
         startActivity (i.setPackage ("com.android.chrome"))
      }
      catch (e: Exception) {
         startActivity (i.setPackage (null))
      }
   }


   private fun loadAlbumArt ()
   // if our mp3 has an embedded bitmap show it
   { val path = Environment.getExternalStorageDirectory ().toString () +
                                                             "/Music/tunz/$song"
     val mmr  = MediaMetadataRetriever ()
      albumArt = try {
         mmr.setDataSource (path)
        val bytes = mmr.embeddedPicture
         if (bytes != null)
               BitmapFactory.decodeByteArray (bytes, 0, bytes.size)
         else  null
      }
      catch (e: Exception) { null }
      finally              { mmr.release () }
      callback?.onAlbumArtChanged (albumArt)
   }


   private fun pick2play ()
   // clicked a dir checkbox sooo redo play from mp3
   {  pick.forEach { p ->
         mp3.forEach { m ->
            if (p == m.dir)  m.fn.forEach { fn ->
               play.add ("$p/$fn")
            }
         }
      }
   }


   fun rePlay ()
   {  if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.reset ()
      play.clear ()
      if (pick.isEmpty ()) {
         callback?.onPlaylistReady (play)
         return
      }
      if (shuf) {
      // build a shuffled interleaved list per picked dir, minus done songs
        val buckets = pick.mapNotNull { p ->
               mp3.find { it.dir == p }?.fn
                  ?.map { "$p/$it" }
                  ?.filter { !done.contains (it) }
                  ?.shuffled ()
                  ?.toMutableList ()
                  ?.takeIf { it.isNotEmpty () }
            }.toMutableList ()
         if (buckets.isEmpty ()) {
            done.clear ()
            pick.forEach { p ->
               mp3.find { it.dir == p }?.fn
                  ?.map { "$p/$it" }
                  ?.shuffled ()
                  ?.let { buckets.add (it.toMutableList ()) }
            }
         }
      // interleave round-robin across buckets
         while (buckets.isNotEmpty ()) {
           val it = buckets.iterator ()
            while (it.hasNext ()) {
              val bucket = it.next ()
               play.add (bucket.removeFirst ())
               if (bucket.isEmpty ())  it.remove ()
            }
         }
      }
      else {
         pick2play ()
         play.sortBy { fmtfn (it).toString () }
      }
      if (play.isEmpty ()) {
         callback?.onPlaylistReady (play)
         return
      }
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
   // row set if song table got doubleclicked.  else itsa neeext
   {  mplay?.stop ()
      mplay?.reset ()
     val removedPos: Int
     val songSnap = song
      if (row == -1) {
         done.add (song)

         if (anNst && online ()) {
         // send did song to shaz.app/song
            Thread {
               try {URL ("https://shaz.app/song/did.php?did=$songSnap")
                       .openConnection ().getInputStream ().close ()}
               catch (ex: Exception) { }
            }.start ()
         }

         removedPos = ppos
         play.removeAt (ppos)
      }
      else {
         if (anNst && online ()) {
         // send skip song to shaz.app/song
            Thread {
               try {URL ("https://shaz.app/song/skip.php?it=$songSnap")
                       .openConnection ().getInputStream ().close ()}
               catch (ex: Exception) { }
            }.start ()
         }
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
     val state = if (mplay?.isPlaying == true)
                       PlaybackStateCompat.STATE_PLAYING
                 else  PlaybackStateCompat.STATE_PAUSED
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
            Intent (this, MusicService::class.java).setAction (
                                                             ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE)
     val piNext = PendingIntent.getService (
            this, 2,
            Intent (this, MusicService::class.java).setAction (ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE)

     val ppIcon = if (isPlaying)  android.R.drawable.ic_media_pause
                  else            android.R.drawable.ic_media_play
     val ppLabel = if (isPlaying)  "Pause"  else  "Play"

     val notif = NotificationCompat.Builder (this, CHANNEL_ID)
        .setSmallIcon     (R.drawable.outline_music_cast_24)
        .setLargeIcon     (albumArt)
        .setContentTitle  (fnt.ttl)
        .setContentText   (fnt.grp)
        .setContentIntent (piMain)
        .setVisibility    (NotificationCompat.VISIBILITY_PUBLIC)
        .addAction        (ppIcon, ppLabel, piPlayPause)
        .addAction        (android.R.drawable.ic_media_next, "Next", piNext)
        .setStyle         (MediaStyle ()
                              .setMediaSession (mediaSession.sessionToken)
                              .setShowActionsInCompactView (0, 1))
        .build ()

      startForeground (NOTIF_ID, notif)
   }
}


class BTDisco (private val mp: MediaPlayer): BroadcastReceiver ()
// if bluetooth disconnects, don't keep playin !!
{  override fun onReceive (context: Context, intent: Intent)
   {  if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
         if (mp.isPlaying)  mp.pause ()
   }
}
