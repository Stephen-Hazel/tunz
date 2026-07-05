// MusicService.kt - need a "foreground service" so we can pop lyrics and
//    do, uhh, a bunch of stuff while music =still= plays

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
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata  as CastMeta
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface


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
   var path = ""
       private set
   var shuf:  Boolean = true
       private set
   var pick = mutableListOf<String> ()
       private set
   private var done = mutableListOf<String> ()
   var play = mutableListOf<String> ()
       private set
   var song = ""
       private set
   var ppos = 0
       private set
   private var albumArt: Bitmap? = null
   private var castSession: CastSession? = null
   private var httpServer: LocalHttpServer? = null
   private var castCb: RemoteMediaClient.Callback? = null

   private lateinit var mediaSession: MediaSessionCompat

// if we get disco'd from bluetooth shuuut uuupp
   private lateinit var btDisco: BTDisco
   private val intentFilter = IntentFilter (
      AudioManager.ACTION_AUDIO_BECOMING_NOISY)

   fun setCallback (cb: PlaybackCallback?) { callback = cb }
   fun addPick    (dir: String) { if (! pick.contains (dir))  pick.add (dir) }
   fun removePick (dir: String) { pick.remove (dir) }
   fun setShuf    (v: Boolean)  { shuf = v }

   fun isCasting () = castSession?.isConnected == true

   fun castVolume (): Double = castSession?.volume ?: 1.0

   fun setCastVolume (vol: Double)
   {  try { castSession?.setVolume (vol) }
      catch (e: Exception) { }
   }

   private fun getLocalIp (): String
   {  try {
        val ifaces = NetworkInterface.getNetworkInterfaces ()?.toList ()
                     ?: return "127.0.0.1"
      // prefer wlan so we don't hand the chromecast a cellular rmnet addr
         for (iface in ifaces.filter { it.name.startsWith ("wlan") })
            for (addr in iface.inetAddresses.toList ())
               if (! addr.isLoopbackAddress && addr is Inet4Address)
                  return addr.hostAddress ?: continue
      // fallback
         for (iface in ifaces)
            for (addr in iface.inetAddresses.toList ())
               if (! addr.isLoopbackAddress && addr is Inet4Address)
                  return addr.hostAddress ?: continue
      }
      catch (e: Exception) { }
      return "127.0.0.1"
   }

   private fun loadCastSong ()
   { val cs      = castSession ?: return
     val encoded = song.split ("/").joinToString ("/") { Uri.encode (it) }
     val url     = "http://${getLocalIp ()}:8765/$encoded"
      Log.d ("TunzCast", "loading $url")
     val fnt     = splitfn (song)
     val meta    = CastMeta (CastMeta.MEDIA_TYPE_MUSIC_TRACK)
      meta.putString (CastMeta.KEY_TITLE,  fnt.ttl)
      meta.putString (CastMeta.KEY_ARTIST, fnt.grp)
     val mi  = MediaInfo.Builder (url)
                  .setStreamType  (MediaInfo.STREAM_TYPE_BUFFERED)
                  .setContentType ("audio/mpeg")
                  .setMetadata    (meta)
                  .build ()
      cs.remoteMediaClient?.load (
         MediaLoadRequestData.Builder ().setMediaInfo (mi).build ())
   }

   private fun regCastCb ()
   { val client = castSession?.remoteMediaClient ?: return
      castCb = object : RemoteMediaClient.Callback ()
      {  override fun onStatusUpdated ()
         { val ms = castSession?.remoteMediaClient?.mediaStatus ?: return
            if (ms.playerState == MediaStatus.PLAYER_STATE_IDLE) {
               Log.d ("TunzCast", "idle reason=${ms.idleReason}")
               if (ms.idleReason == MediaStatus.IDLE_REASON_FINISHED)
                  next ()
            }
         }
      }
      client.registerCallback (castCb!!)
   }

   private fun unregCastCb ()
   {  castCb?.let { castSession?.remoteMediaClient?.unregisterCallback (it) }
      castCb = null
   }

   private val castListener = object : SessionManagerListener<CastSession>
   {  override fun onSessionStarted (session: CastSession, id: String)
      {  castSession = session
         httpServer  = LocalHttpServer (path).also { it.start () }
         mplay?.pause ()
         if (song.isNotEmpty ())  loadCastSong ()
         regCastCb ()
         updateMediaSession ()
         postNotification ()
      }

      override fun onSessionResumed (session: CastSession,
                                     wasSuspended: Boolean)
      {  castSession = session
         if (httpServer == null)
            httpServer = LocalHttpServer (path).also { it.start () }
         if (song.isNotEmpty ())  loadCastSong ()
         regCastCb ()
         mplay?.pause ()
      }

      override fun onSessionEnded (session: CastSession, error: Int)
      {  Log.d ("TunzCast", "session ended error=$error")
         unregCastCb ()
         castSession = null
         httpServer?.stop ()
         httpServer = null
         if (song.isNotEmpty ()) {
            mplay?.reset ()
            try {
               mplay?.setDataSource ("$path/$song")
               mplay?.prepare ()
               mplay?.start ()
               mplay?.setOnCompletionListener { next () }
            }
            catch (e: Exception) { }
         }
         updateMediaSession ()
         postNotification ()
      }

      override fun onSessionStarting    (s: CastSession) {}
      override fun onSessionStartFailed (s: CastSession, e: Int) {}
      override fun onSessionEnding      (s: CastSession) {}
      override fun onSessionResuming    (s: CastSession, id: String) {}
      override fun onSessionResumeFailed(s: CastSession, e: Int) {}
      override fun onSessionSuspended   (s: CastSession, r: Int) {}
   }

   fun togglePlayPause ()
   {  if (isCasting ()) {
        val client = castSession!!.remoteMediaClient ?: return
        val state  = client.mediaStatus?.playerState
         if (state == MediaStatus.PLAYER_STATE_PLAYING)  client.pause (null)
         else                                            client.play  (null)
      }
      else {
         if (mplay?.isPlaying == true)  mplay?.pause ()
         else                           mplay?.start ()
      }
      updateMediaSession ()
      postNotification ()
   }


   override fun onCreate ()
   {  super.onCreate ()

   // must call startForeground() fast or Android kills us when started
   // from background (e.g. media button while screen is off)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel (CHANNEL_ID, "Playback",
                                      NotificationManager.IMPORTANCE_LOW)
         getSystemService (NotificationManager::class.java
                                                ).createNotificationChannel (ch)
      }
      startForeground (NOTIF_ID,
         NotificationCompat.Builder (this, CHANNEL_ID)
            .setSmallIcon (R.drawable.outline_music_cast_24)
            .setContentTitle ("Tunz")
            .build ())

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
      done = try {
         File ("${File(path).parent}/done.txt").readLines ().toMutableList ()
      }
      catch (e: Exception) { mutableListOf () }
   // ok, list off each dir in path
     val mus  = File (path).listFiles () ?: emptyArray ()
     val dir  = mutableListOf<String> ()
      for (i in mus.indices) {
        val dn = mus [i].getName ()
         if (dn != ".thumbnails" && mus [i].isDirectory)  dir.add (dn)
      }
      dir.sort ()
      dir.forEach { d ->
        val dl = File ("$path/$d").listFiles ()
        val ls: Array<String> = dl!!.map { it.getName () }.toTypedArray ()
         ls.sort ()
         mp3.add (FNList (d, ls.toMutableList ()))
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

      try {
        val cc = CastContext.getSharedInstance (this)
         cc.sessionManager.addSessionManagerListener (
            castListener, CastSession::class.java)
         castSession = cc.sessionManager.currentCastSession
      }
      catch (e: Exception) { }
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
      try { File ("${File(path).parent}/done.txt").writeText (done.joinToString ("\n")) }
      catch (ex: Exception) { }
      try {
         CastContext.getSharedInstance (this)
            .sessionManager.removeSessionManagerListener (
               castListener, CastSession::class.java)
      }
      catch (ex: Exception) { }
      httpServer?.stop ()
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
                  ?.let { if (it.isNotEmpty ())
                             buckets.add (it.toMutableList ()) }
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
      if (isCasting ())  loadCastSong ()
      else {
         mplay?.setDataSource ("$path/$song")
         mplay?.prepare ()
         mplay?.start ()
         mplay?.setOnCompletionListener { next () }
      }
      updateMediaSession ()
      postNotification ()
      callback?.onPlaylistReady (play.toList ())
   }


   fun next (row: Int = -1)
   // row set if song table got doubleclicked.  else itsa neeext
   {  mplay?.stop ()
      mplay?.reset ()
     val removedPos: Int
      if (row == -1) {
         done.add (song)
         removedPos = ppos
         play.removeAt (ppos)
      }
      else {
         removedPos = -1
         ppos = row
      }
      if (ppos < play.size) {
         song = play [ppos]
         loadAlbumArt ()
         if (isCasting ())  loadCastSong ()
         else {
            mplay?.setDataSource ("$path/$song")
            mplay?.prepare ()
            mplay?.start ()
            mplay?.setOnCompletionListener { next () }
         }
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

   // service is already foreground from onCreate() - just update the
   // existing notification instead of re-requesting promotion, which
   // Android 12+ denies once the app has left the TOP state
      try {
         NotificationManagerCompat.from (this).notify (NOTIF_ID, notif)
      }
      catch (e: Exception) { }
   }
}


class BTDisco (private val mp: MediaPlayer): BroadcastReceiver ()
// if bluetooth disconnects, don't keep playin !!
{  override fun onReceive (context: Context, intent: Intent)
   {  if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
         if (mp.isPlaying)  mp.pause ()
   }
}
