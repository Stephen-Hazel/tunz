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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.VolumeProviderCompat
import androidx.media.session.MediaButtonReceiver
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata  as CastMeta
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit


// setup play/pause/skip api
const val ACTION_PLAY_PAUSE = "app.shaz.tunz.PLAY_PAUSE"
const val ACTION_NEXT       = "app.shaz.tunz.NEXT"
const val CHANNEL_ID        = "tunz_playback"
const val NOTIF_ID          = 1
const val BT_WATCHDOG_POLL_MS    = 500L
const val BT_WATCHDOG_TIMEOUT_MS = 5000L
const val CAST_QUEUE_LOAD_DEBOUNCE_MS = 400L


object Dbg
// permanent on-device log for chasing intermittent bugs (bt/assistant
// skip weirdness etc) - a plain file survives days between adb
// sessions, unlike the logcat ring buffer
{  private var file: File? = null
   private val fmt = SimpleDateFormat ("MM-dd HH:mm:ss", Locale.US)
   private const val MAX_BYTES  = 1_000_000L
   private const val KEEP_BYTES = 500_000

   fun init (dir: String)
   {  val f = File ("$dir/tunz_debug.log")
      try {
         if (f.exists () && f.length () > MAX_BYTES)
            f.writeText (f.readText ().takeLast (KEEP_BYTES))
      }
      catch (e: Exception) { }
      file = f
   }

   fun log (tag: String, msg: String)
   {  Log.d (tag, msg)
     val f = file ?: return
      synchronized (this) {
         try {
            f.appendText ("${fmt.format (Date ())} $tag: $msg\n")
         }
         catch (e: Exception) { }
      }
   }

   fun installCrashLogger ()
   // catch crashes we didn't think to log for ourselves - a dead
   // service mid-drive should always leave a trace
   {  val prev = Thread.getDefaultUncaughtExceptionHandler ()
      Thread.setDefaultUncaughtExceptionHandler { thread, e ->
         log ("TunzCrash", "uncaught in ${thread.name}: " +
                            Log.getStackTraceString (e))
         prev?.uncaughtException (thread, e)
      }
   }
}


interface PlaybackCallback {
   fun onPlaylistReady    (play: List<String>)
   fun onSongChanged      (removedPos: Int, newPpos: Int)
   fun onAlbumArtChanged  (art: Bitmap?)
   fun onCastVolumeChanged (vol: Double)
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
   private var wifiLock: WifiManager.WifiLock? = null
   private var castCb: RemoteMediaClient.Callback? = null
   private var volumeProvider: VolumeProviderCompat? = null
   private var castErrorStreak = 0
   private var localErrorStreak = 0
   private var lastCastDeviceId: String? = null
   private var lastCastQueueSize = -1
   private var lastAdvancedItemId = -1
   private var castSkipInFlight = false
   private var castQueueLoadPending = false
   private var reconnectAttempts = 0
   private var noisyPauseGuard = false
   private var btReconnectedSinceNoisy = false
   private val reconnectHandler = Handler (Looper.getMainLooper ())
   private val btWatchdogHandler = Handler (Looper.getMainLooper ())
   private val castQueueLoadHandler = Handler (Looper.getMainLooper ())
   private val focusGainCheckHandler = Handler (Looper.getMainLooper ())

   private lateinit var mediaSession: MediaSessionCompat

// if we get disco'd from bluetooth shuuut uuupp
   private lateinit var btDisco: BTDisco
   private val intentFilter = IntentFilter (
      AudioManager.ACTION_AUDIO_BECOMING_NOISY)

// audioFocusListener logs so we can see what's happening when "Hey
// Google, skip" (heard by the phone itself, not a cast device) goes
// weird, and on GAIN gives mplay a restart kick - see the comment at
// its GAIN branch for why. audioDeviceCallback also logs, but its
// onAudioDevicesAdded additionally arms btReconnectedSinceNoisy - see the
// NOTE above startBtResumeWatchdog() for why a real re-add event (not a
// getDevices() snapshot) is what the watchdog needs to trust
   private var audioFocusListener: AudioManager.OnAudioFocusChangeListener?
      = null
   private val audioDeviceCallback = object: AudioDeviceCallback ()
   {  override fun onAudioDevicesAdded (devices: Array<out AudioDeviceInfo>)
      {  devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .forEach {
               Dbg.log ("TunzBT", "device added: ${it.productName}")
               btReconnectedSinceNoisy = true
            }
      }
      override fun onAudioDevicesRemoved (
         devices: Array<out AudioDeviceInfo>)
      {  devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .forEach {
               Dbg.log ("TunzBT", "device removed: ${it.productName}")
            }
      }
   }

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

   private fun buildVolumeProvider (): VolumeProviderCompat
   { val cur = (castVolume () * 50).toInt ()
      return object: VolumeProviderCompat (
         VOLUME_CONTROL_ABSOLUTE, 50, cur)
      {  override fun onSetVolumeTo (vol: Int)
         { val step = vol.coerceIn (0, 50)
           val v    = step / 50.0
            setCastVolume (v)
            currentVolume = step
            callback?.onCastVolumeChanged (v)
         }
         override fun onAdjustVolume (direction: Int)
         { val step = (currentVolume + direction).coerceIn (0, 50)
           val v    = step / 50.0
            setCastVolume (v)
            currentVolume = step
            callback?.onCastVolumeChanged (v)
         }
      }
   }

   private fun getLocalIp (): String
   {  try {
        val ifaces = NetworkInterface.getNetworkInterfaces ()?.toList ()
         if (ifaces == null) {
            Log.w ("TunzCast", "no network interfaces, using loopback")
            Dbg.log ("TunzCast", "no network interfaces, using loopback")
            return "127.0.0.1"
         }
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
      catch (e: Exception) {
         Log.w ("TunzCast", "getLocalIp failed", e)
         Dbg.log ("TunzCast", "getLocalIp failed: $e")
      }
      Log.w ("TunzCast", "no usable ip found, using loopback")
      Dbg.log ("TunzCast", "no usable ip found, using loopback")
      return "127.0.0.1"
   }

   private fun buildCastQueueItem (songFile: String): MediaQueueItem
   { val encoded = songFile.split ("/").joinToString ("/") { Uri.encode (it) }
     val url     = "http://${getLocalIp ()}:8765/$encoded"
      Log.d ("TunzCast", "queueing $url")
      Dbg.log ("TunzCast", "queueing $url")
     val fnt     = splitfn (songFile)
     val meta    = CastMeta (CastMeta.MEDIA_TYPE_MUSIC_TRACK)
      meta.putString (CastMeta.KEY_TITLE,  fnt.ttl)
      meta.putString (CastMeta.KEY_ARTIST, fnt.grp)
     val mi  = MediaInfo.Builder (url)
                  .setStreamType  (MediaInfo.STREAM_TYPE_BUFFERED)
                  .setContentType ("audio/mpeg")
                  .setMetadata    (meta)
                  .build ()
      return MediaQueueItem.Builder (mi).build ()
   }

   private fun logCastResult (
      op: String,
      pending: PendingResult<RemoteMediaClient.MediaChannelResult>?)
   // every queue* call is fire-and-forget by default - without this, a
   // failed queueNext()/queueLoad()/etc (dropped session, invalid item id
   // race, receiver said no) leaves zero trace, same blind spot that made
   // the original bt-skip bug hard to pin down
   {  pending?.setResultCallback { r ->
         if (! r.status.isSuccess)
            Dbg.log ("TunzCast",
               "$op failed: ${r.status.statusCode} ${r.status.statusMessage}")
      }
   }

   private fun loadCastQueue ()
   // load current+2-ahead as a real cast queue (not a one-off load()) so
   // the receiver itself knows what "next" means - lets "Hey Google, skip"
   // spoken straight at the cast device (never reaches this app at all)
   // actually have something to skip to. 2 ahead rather than 1 gives a
   // couple of rapid-fire skips some slack instead of running the queue
   // dry the instant a queueAppendItem() round-trip lags behind
   { val cs    = castSession ?: return
     val items = mutableListOf (buildCastQueueItem (song))
      play.getOrNull (ppos + 1)?.let { items.add (buildCastQueueItem (it)) }
      play.getOrNull (ppos + 2)?.let { items.add (buildCastQueueItem (it)) }
   // rePlay() calls this on every dir-checkbox toggle - rapidly hitting a
   // few checkboxes fired one queueLoad() per click, which overlap and
   // race on the receiver (one loses with a 2103 REPLACED, and the
   // receiver can end up on a stale queue that no longer matches local
   // play/ppos/song). debounce so only the last click in a burst actually
   // reaches the receiver
      castQueueLoadHandler.removeCallbacksAndMessages (null)
      castQueueLoadHandler.postDelayed ({
      // the OLD queue can keep emitting status updates for a moment after
      // we issue queueLoad() - the receiver hasn't actually replaced it
      // yet. block handleCastQueueAdvance() from looking at ANY status
      // update until this load is confirmed, so a straggling update from
      // the queue we're about to replace can't get misread as an advance
      // (or wrongly absorbed as this load's baseline) and corrupt local
      // ppos/song against a queue that has nothing to do with it
         castQueueLoadPending = true
         Dbg.log ("TunzCast", "loadCastQueue: ${items.size} item(s) song=$song")
        val pending = cs.remoteMediaClient?.queueLoad (
            items.toTypedArray (), 0, MediaStatus.REPEAT_MODE_REPEAT_OFF, null)
         if (pending == null)  castQueueLoadPending = false
         pending?.setResultCallback ({ r ->
            castQueueLoadPending = false
            if (r.status.isSuccess) {
               lastAdvancedItemId = -1
               castSkipInFlight = false
            }
            else  Dbg.log ("TunzCast", "queueLoad failed: " +
               "${r.status.statusCode} ${r.status.statusMessage}")
         }, 10, TimeUnit.SECONDS)
      }, CAST_QUEUE_LOAD_DEBOUNCE_MS)
   }

   private fun handleCastQueueAdvance (ms: MediaStatus)
   // the receiver moved its currentItem on to something other than the
   // last one we knew about - happens whether we asked it to (queueNext()
   // below, from a button/phone-heard "skip") or the cast device did it on
   // its own ("Hey Google, skip" spoken to the speaker itself, or the
   // current track just finishing). either way this is the one place that
   // keeps play/ppos/song/done in sync with what's actually on the
   // receiver, and keeps a "next" item queued for the round after this one.
   // keyed off itemId rather than queue position: the queue is never
   // pruned (see queueAppendItem below, no matching remove) so "current"
   // drifts to a higher index every skip - position was never a reliable
   // signal and comparing ids side-steps it entirely
   { if (castQueueLoadPending)  return
     val client = castSession?.remoteMediaClient ?: return
     val items  = ms.queueItems ?: return
      if (items.size != lastCastQueueSize) {
         Dbg.log ("TunzCast", "cast queue size now ${items.size}")
         lastCastQueueSize = items.size
      }
     val curId = ms.currentItemId
      if (curId == MediaQueueItem.INVALID_ITEM_ID)  return
      if (lastAdvancedItemId == -1) {
      // first status update since a fresh loadCastQueue() - this is just
      // that queue's starting item, not an advance
         lastAdvancedItemId = curId
         return
      }
      if (curId == lastAdvancedItemId)  return
      lastAdvancedItemId = curId
      castSkipInFlight = false
      Dbg.log ("TunzCast", "receiver advanced to queue item $curId")
      advanceLocal ()
      if (ppos >= play.size)  return
      song = play [ppos]
      loadAlbumArt ()
      updateMediaSession ()
      postNotification ()
   // loadCastQueue() preloads current+2-ahead, so after this one-step
   // advance the receiver already has ppos+1 queued (it was "ahead2"
   // before this advance) - only ppos+2 is genuinely new. appending
   // ppos+1 here would re-queue a song already sitting in the queue as a
   // second, distinct item, which the receiver would eventually play as
   // an extra, silent-to-the-user advance - exactly the kind of gap that
   // desyncs the phone's idea of "current song" from what's really on air
      play.getOrNull (ppos + 2)?.let {
         logCastResult ("queueAppendItem",
            client.queueAppendItem (buildCastQueueItem (it), null))
      }
      callback?.onSongChanged (ppos, ppos)
   }

   private fun regCastCb ()
   { val client = castSession?.remoteMediaClient ?: return
      castCb = object : RemoteMediaClient.Callback ()
      {  override fun onStatusUpdated ()
         { val ms = castSession?.remoteMediaClient?.mediaStatus ?: return
            updateMediaSession ()
            handleCastQueueAdvance (ms)
            if (ms.playerState == MediaStatus.PLAYER_STATE_IDLE) {
               Log.d ("TunzCast", "idle reason=${ms.idleReason}")
               Dbg.log ("TunzCast", "idle reason=${ms.idleReason}")
               if (ms.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                  castErrorStreak = 0
                  next ()
               }
            // load/playback failed - skip the dead track n keep going,
            // but give up after a few in a row so a real outage doesn't
            // burn through the whole playlist
               else if (ms.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                  castErrorStreak++
                  Log.d ("TunzCast", "error streak=$castErrorStreak")
                  Dbg.log ("TunzCast", "error streak=$castErrorStreak")
                  if (castErrorStreak <= 3)  next ()
                  else  Dbg.log ("TunzCast",
                     "giving up after $castErrorStreak consecutive errors")
               }
            }
         }
      }
      client.registerCallback (castCb!!)
   }

   private fun handleHttpStreamError (uri: String)
   // the http server has its own failures (dropped connection, etc)
   // that never reach us via a cast status update - runs on the
   // server's handler thread, so hop back to main before touching
   // any playback state
   {  reconnectHandler.post {
        if (castSkipInFlight) {
            Dbg.log ("TunzCast",
               "ignoring stream reset for $uri - skip in flight")
            return@post
         }
        if (isCasting () && uri == song) {
            castErrorStreak++
            Log.d ("TunzCast", "http stream error streak=$castErrorStreak")
            Dbg.log ("TunzCast", "http stream error streak=$castErrorStreak")
            if (castErrorStreak <= 3)  next ()
            else  Dbg.log ("TunzCast",
               "giving up after $castErrorStreak consecutive errors")
         }
      }
   }

   private fun unregCastCb ()
   {  castCb?.let { castSession?.remoteMediaClient?.unregisterCallback (it) }
      castCb = null
   }

   @Suppress("DEPRECATION")
   private fun acquireWifiLock ()
   // wifi radio power-saves when the screen is off, which can stall
   // our http server for tens of seconds between beacon wakeups -
   // keep it awake for as long as we're serving to the cast device.
   // WIFI_MODE_FULL_HIGH_PERF is deprecated (the OS manages this
   // automatically since API 34) but minSdk is 24 and there's no
   // non-deprecated equivalent, so we still need it on older devices
   {  if (wifiLock?.isHeld == true)  return
     val wm = applicationContext
                 .getSystemService (Context.WIFI_SERVICE) as WifiManager
      wifiLock = wm.createWifiLock (WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                                    "tunz:cast")
      wifiLock?.acquire ()
   }

   private fun releaseWifiLock ()
   {  if (wifiLock?.isHeld == true)  wifiLock?.release ()
      wifiLock = null
   }

   private fun tryReconnectCast ()
   // an active cast session died unexpectedly - reselect the same
   // route to get the framework to reconnect us, rather than ever
   // falling back to the phone speaker while we think we're casting
   { val deviceId = lastCastDeviceId ?: return
     val router   = MediaRouter.getInstance (this)
     val route    = router.routes.firstOrNull {
        CastDevice.getFromBundle (it.extras)?.deviceId == deviceId
     }
      if (route != null) {
         Log.d ("TunzCast", "reconnecting to $deviceId")
         Dbg.log ("TunzCast", "reconnecting to $deviceId")
         router.selectRoute (route)
         return
      }
      reconnectAttempts++
      if (reconnectAttempts <= 5) {
         Log.d ("TunzCast",
                "device $deviceId not found, retry $reconnectAttempts/5")
         Dbg.log ("TunzCast",
                  "device $deviceId not found, retry $reconnectAttempts/5")
         reconnectHandler.postDelayed ({ tryReconnectCast () }, 3000)
      }
      else {
         Log.w ("TunzCast", "giving up reconnecting to $deviceId")
         Dbg.log ("TunzCast", "giving up reconnecting to $deviceId")
      }
   }

   private val castListener = object : SessionManagerListener<CastSession>
   {  override fun onSessionStarted (session: CastSession, id: String)
      {  Dbg.log ("TunzCast", "session started device=${session.castDevice?.deviceId}")
         castSession = session
         castErrorStreak = 0
         reconnectAttempts = 0
         reconnectHandler.removeCallbacksAndMessages (null)
         lastCastDeviceId = session.castDevice?.deviceId
         httpServer  = LocalHttpServer (path).also {
            it.start ()
            it.onStreamError = { uri -> handleHttpStreamError (uri) }
         }
         acquireWifiLock ()
         mplay?.pause ()
         if (song.isNotEmpty ())  loadCastQueue ()
         regCastCb ()
         volumeProvider = buildVolumeProvider ()
         mediaSession.setPlaybackToRemote (volumeProvider!!)
         updateMediaSession ()
         postNotification ()
      }

      override fun onSessionResumed (session: CastSession,
                                     wasSuspended: Boolean)
      {  Dbg.log ("TunzCast",
                  "session resumed wasSuspended=$wasSuspended " +
                  "device=${session.castDevice?.deviceId}")
         castSession = session
         reconnectAttempts = 0
         reconnectHandler.removeCallbacksAndMessages (null)
         lastCastDeviceId = session.castDevice?.deviceId
         if (httpServer == null)
            httpServer = LocalHttpServer (path).also {
               it.start ()
               it.onStreamError = { uri -> handleHttpStreamError (uri) }
            }
         acquireWifiLock ()
         if (song.isNotEmpty ())  loadCastQueue ()
         regCastCb ()
         volumeProvider = buildVolumeProvider ()
         mediaSession.setPlaybackToRemote (volumeProvider!!)
         mplay?.pause ()
      }

      override fun onSessionEnded (session: CastSession, error: Int)
      {  Log.d ("TunzCast", "session ended error=$error")
         Dbg.log ("TunzCast", "session ended error=$error")
         unregCastCb ()
         castSession = null
         volumeProvider = null
         mediaSession.setPlaybackToLocal (AudioManager.STREAM_MUSIC)
         httpServer?.stop ()
         httpServer = null
         releaseWifiLock ()
      // a clean, user-requested disconnect falls back to the phone
      // speaker same as always. anything else (dropped wifi, etc) tries
      // to get back onto the same cast device instead of ever playing
      // out the phone speaker while we still think we're casting
         if (error == CastStatusCodes.SUCCESS) {
            if (song.isNotEmpty ()) {
               mplay?.reset ()
               try {
                  mplay?.setDataSource ("$path/$song")
                  mplay?.prepare ()
                  mplay?.start ()
                  mplay?.setOnCompletionListener { localErrorStreak = 0; next () }
               }
               catch (e: Exception) {
                  Log.e ("TunzLocal", "fallback playback failed for $song", e)
                  Dbg.log ("TunzLocal",
                           "fallback playback failed for $song: $e")
               }
            }
         }
         else {
            reconnectAttempts = 0
            tryReconnectCast ()
         }
         updateMediaSession ()
         postNotification ()
      }

      override fun onSessionStarting    (s: CastSession)
      {  Dbg.log ("TunzCast", "session starting") }
      override fun onSessionStartFailed (s: CastSession, e: Int)
      {  Log.e ("TunzCast", "session start failed error=$e")
         Dbg.log ("TunzCast", "session start failed error=$e") }
      override fun onSessionEnding      (s: CastSession)
      {  Dbg.log ("TunzCast", "session ending") }
      override fun onSessionResuming    (s: CastSession, id: String)
      {  Dbg.log ("TunzCast", "session resuming id=$id") }
      override fun onSessionResumeFailed(s: CastSession, e: Int)
      {  Log.e ("TunzCast", "session resume failed error=$e")
         Dbg.log ("TunzCast", "session resume failed error=$e") }
      override fun onSessionSuspended   (s: CastSession, r: Int)
      {  Log.d ("TunzCast", "session suspended reason=$r")
         Dbg.log ("TunzCast", "session suspended reason=$r") }
   }

   fun togglePlayPause (): Boolean
   {  val wasPlaying: Boolean
      if (isCasting ()) {
        val client = castSession!!.remoteMediaClient ?: return false
         wasPlaying = client.mediaStatus?.playerState ==
                                            MediaStatus.PLAYER_STATE_PLAYING
         if (wasPlaying)  client.pause (null)
         else             client.play  (null)
      }
      else {
         wasPlaying = mplay?.isPlaying == true
         if (wasPlaying)  mplay?.pause ()
         else             mplay?.start ()
      }
      updateMediaSession ()
      postNotification ()
      return ! wasPlaying
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
      mplay!!.setOnErrorListener { _, what, extra ->
         Log.e ("TunzLocal", "player error what=$what extra=$extra")
         Dbg.log ("TunzLocal",
                  "player error what=$what extra=$extra song=$song")
         localErrorStreak++
         if (localErrorStreak <= 3)  next ()
         true
      }
   // all our mp3 files sit flat in /Music/tunz, rating suffix in filename
      path = Environment.getExternalStorageDirectory ().toString () +
                                                                   "/Music/tunz"
      Dbg.init (File (path).parent !!)
      Dbg.installCrashLogger ()
      Dbg.log ("TunzSkip", "onCreate: service created")
   // load shuf,picked dirs from last time
     val p = getSharedPreferences ("prf", MODE_PRIVATE)
      shuf = p.getBoolean   ("shuf", true)
      pick = p.getStringSet ("pick", emptySet ())?.toMutableList () ?:
                                                                mutableListOf ()
   // one-time rating scheme migration: _a/_b/An/St -> a/b/aAn/aSt
     val ratingMigration = mapOf (
        "_a" to "a", "_b" to "b", "An" to "aAn", "St" to "aSt")
      pick = pick.map { ratingMigration [it] ?: it }.distinct ()
                                                            .toMutableList ()
   // and our done list so we don't hear ANY repeats
      done = try {
         File ("${File(path).parent}/done.txt").readLines ().toMutableList ()
      }
      catch (e: Exception) { mutableListOf () }
   // ok, list off every mp3, migrate old ratings, n bucket by suffix
     val mus = File (path).listFiles () ?: emptyArray ()
     val fns = mus.filter { it.isFile && it.getName ().endsWith (".mp3") }
                  .map { f ->
                    val old  = f.getName ()
                    val newR = ratingMigration [splitfn (old).dir]
                     if (newR == null)  old
                     else {
                       val newName = renameRating (old, newR)
                        if (File (path, newName).exists ()) {
                           Log.w ("TunzRate",
                                  "migration skip, exists: $newName")
                           old
                        }
                        else if (f.renameTo (File (path, newName)))
                              newName
                        else {
                           Log.w ("TunzRate", "migration rename failed: $old")
                           old
                        }
                     }
                  }.sorted ()
      fns.groupBy { splitfn (it).dir }.toSortedMap ().forEach { (d, ls) ->
         mp3.add (FNList (d, ls.toMutableList ()))
      }
      mediaSession = MediaSessionCompat (this, "TunzSession").apply {
         setCallback (object: MediaSessionCompat.Callback ()
         {  override fun onPlay ()
            {  Dbg.log ("TunzSkip", "onPlay")
               btWatchdogHandler.removeCallbacksAndMessages (null)
               mplay?.start ()
               updateMediaSession ()
               postNotification ()
            }

            override fun onPause ()
            {  Dbg.log ("TunzSkip", "onPause")
               if (noisyPauseGuard) {
                  noisyPauseGuard = false
                  Dbg.log ("TunzSkip",
                           "onPause: noisy-pause guard held, " +
                           "watchdog kept armed")
               }
               else {
                  btWatchdogHandler.removeCallbacksAndMessages (null)
                  Dbg.log ("TunzSkip",
                           "onPause: watchdog cancelled (manual pause)")
               }
               mplay?.pause ()
               updateMediaSession ()
               postNotification ()
            }

            override fun onSkipToNext ()
            {  Dbg.log ("TunzSkip", "onSkipToNext")
               next ()
            }

         // not implemented - logged so an assistant call landing here
         // instead of onSkipToNext shows up rather than vanishing
            override fun onStop ()
            {  Dbg.log ("TunzSkip", "onStop") }

            override fun onSkipToPrevious ()
            {  Dbg.log ("TunzSkip", "onSkipToPrevious") }

            override fun onSeekTo (pos: Long)
            {  Dbg.log ("TunzSkip", "onSeekTo pos=$pos") }

            override fun onFastForward ()
            {  Dbg.log ("TunzSkip", "onFastForward") }

            override fun onRewind ()
            {  Dbg.log ("TunzSkip", "onRewind") }

            override fun onSetRating (rating: RatingCompat)
            {  Dbg.log ("TunzSkip", "onSetRating rating=$rating") }

            override fun onCustomAction (action: String, extras: Bundle?)
            {  Dbg.log ("TunzSkip", "onCustomAction action=$action " +
                        "extras=$extras") }

            override fun onPlayFromSearch (query: String?, extras: Bundle?)
            {  Dbg.log ("TunzSkip", "onPlayFromSearch query=$query " +
                        "extras=$extras") }

            override fun onPlayFromMediaId (mediaId: String?,
                                             extras: Bundle?)
            {  Dbg.log ("TunzSkip", "onPlayFromMediaId mediaId=$mediaId " +
                        "extras=$extras") }

            override fun onPlayFromUri (uri: Uri?, extras: Bundle?)
            {  Dbg.log ("TunzSkip", "onPlayFromUri uri=$uri " +
                        "extras=$extras") }

            override fun onPrepare ()
            {  Dbg.log ("TunzSkip", "onPrepare") }

            override fun onCommand (command: String, args: Bundle?,
                                     cb: ResultReceiver?)
            {  Dbg.log ("TunzSkip", "onCommand command=$command " +
                        "args=$args") }
         })
         isActive = true
      }

      btDisco = BTDisco (mplay!!) { handleNoisyPause () }
      registerReceiver (btDisco, intentFilter)

    val am = getSystemService (Context.AUDIO_SERVICE) as AudioManager
      am.registerAudioDeviceCallback (audioDeviceCallback, null)
      audioFocusListener = AudioManager.OnAudioFocusChangeListener {
         focusChange ->
        val name = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN                     -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS                     -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT            ->
               "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK   ->
               "LOSS_TRANSIENT_CAN_DUCK"
            else                                              ->
               "UNKNOWN($focusChange)"
         }
         Dbg.log ("TunzFocus",
                  "onAudioFocusChange: $name isPlaying=${mplay?.isPlaying} " +
                  "btA2dp=${isBtA2dpConnected ()} mode=${am.mode} " +
                  "pos=${mplay?.currentPosition}")
      // "Hey Google, skip" can call next()'s mplay.start() while
      // Assistant still holds focus for its own SCO listen/response - if
      // the BT stack doesn't cleanly hand A2DP back afterwards, mplay
      // keeps reporting isPlaying=true but produces no audio, and
      // nothing else ever kicks it again. a plain start() here is a
      // no-op on an already-Started player - it doesn't touch the
      // underlying AudioTrack, so it can't rebind a dead route. cycle
      // pause/seekTo/start instead to force a real flush and re-attach
      // to whatever output route is actually live now
         if (focusChange == AudioManager.AUDIOFOCUS_GAIN &&
             mplay?.isPlaying == true) {
           val posBefore = mplay?.currentPosition ?: -1
            mplay?.pause ()
            mplay?.seekTo (posBefore)
            mplay?.start ()
            Dbg.log ("TunzFocus", "GAIN: restart kick, pos=$posBefore")
            focusGainCheckHandler.postDelayed ({
               Dbg.log ("TunzFocus",
                        "GAIN: pos check +1500ms pos=" +
                        "${mplay?.currentPosition} " +
                        "isPlaying=${mplay?.isPlaying}")
            }, 1500L)
         }
      }
    @Suppress ("DEPRECATION")
    val focusRes = am.requestAudioFocus (audioFocusListener,
         AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
      Dbg.log ("TunzFocus", "requestAudioFocus result=$focusRes")

      try {
        val cc = CastContext.getSharedInstance (this)
         cc.sessionManager.addSessionManagerListener (
            castListener, CastSession::class.java)
         castSession = cc.sessionManager.currentCastSession
         if (isCasting ()) {
            volumeProvider = buildVolumeProvider ()
            mediaSession.setPlaybackToRemote (volumeProvider!!)
         }
      }
      catch (e: Exception) { }
   }


// more foreground service silliness - glad i have AI now cuz this looks duuuumb
   override fun onBind (intent: Intent): IBinder = binder

   override fun onStartCommand (intent: Intent?, flags: Int, startId: Int): Int
   {  val key = intent?.let { IntentCompat.getParcelableExtra (
                  it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }
      Dbg.log ("TunzSkip",
               "onStartCommand action=${intent?.action} key=${key?.keyCode}")
      MediaButtonReceiver.handleIntent (mediaSession, intent)
      when (intent?.action) {
         ACTION_PLAY_PAUSE -> togglePlayPause ()
         ACTION_NEXT       -> next ()
      }
      return START_STICKY
   }


   override fun onDestroy ()
   // shut it all down
   {  super.onDestroy ()
      Dbg.log ("TunzSkip", "onDestroy: service destroyed")
      unregisterReceiver (btDisco)
    val am = getSystemService (Context.AUDIO_SERVICE) as AudioManager
      am.unregisterAudioDeviceCallback (audioDeviceCallback)
      audioFocusListener?.let {
         @Suppress ("DEPRECATION")
         am.abandonAudioFocus (it)
      }
      reconnectHandler.removeCallbacksAndMessages (null)
      btWatchdogHandler.removeCallbacksAndMessages (null)
      castQueueLoadHandler.removeCallbacksAndMessages (null)
   // clear cast state before tearing anything else down - httpServer
   // .stop() below deliberately kills in-flight sockets, which fires
   // handleHttpStreamError()/onStatusUpdated() asynchronously; without
   // this, isCasting() still reads true and those callbacks keep
   // calling next() against a service that's already destroyed
      unregCastCb ()
      castSession = null
      volumeProvider = null
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
      releaseWifiLock ()
   }


   override fun onTaskRemoved (rootIntent: Intent?)
   {  Dbg.log ("TunzSkip", "onTaskRemoved: app swiped away, stopping")
      stopForeground (STOP_FOREGROUND_REMOVE)
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
               play.add (fn)
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
                  ?.filter { !done.contains (it) }
                  ?.shuffled ()
                  ?.toMutableList ()
                  ?.takeIf { it.isNotEmpty () }
            }.toMutableList ()
         if (buckets.isEmpty ()) {
            done.clear ()
            pick.forEach { p ->
               mp3.find { it.dir == p }?.fn
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
      if (isCasting ())  loadCastQueue ()
      else {
         try {
            mplay?.setDataSource ("$path/$song")
            mplay?.prepare ()
            mplay?.start ()
            mplay?.setOnCompletionListener { localErrorStreak = 0; next () }
         }
         catch (e: Exception) {
            Log.e ("TunzLocal", "prepare failed for $song", e)
            Dbg.log ("TunzLocal", "prepare failed for $song: $e")
            localErrorStreak++
            if (localErrorStreak <= 3)  next ()
         }
      }
      updateMediaSession ()
      postNotification ()
      callback?.onPlaylistReady (play.toList ())
   }


   private fun advanceLocal (): Int
   // shared bookkeeping for a sequential (row==-1) advance: mark the
   // current song done and drop it from play. doesn't touch mplay/cast -
   // callers decide what (if anything) to load next
   { val removedPos = ppos
      done.add (song)
      play.removeAt (ppos)
      return removedPos
   }


   fun next (row: Int = -1)
   // row set if song table got doubleclicked.  else itsa neeext
   {  Dbg.log ("TunzSkip",
               "next enter row=$row ppos=$ppos playSize=${play.size}")
   // a NEXT action (skip button, media key) can reach a just-created
   // service before the playlist is rebuilt - nothing to skip to yet
      if (row == -1 && ppos !in play.indices) {
         Dbg.log ("TunzSkip",
                  "next: ppos=$ppos out of range for playSize=" +
                  "${play.size}, ignoring")
         return
      }
      btWatchdogHandler.removeCallbacksAndMessages (null)
   // while casting, a sequential skip just tells the receiver to move to
   // whatever it already has queued as "next" (see loadCastQueue()) -
   // handleCastQueueAdvance() picks up the resulting currentItemId change
   // and does the play/ppos/song/done bookkeeping there, so this same path
   // and a "Hey Google, skip" heard by the cast device itself (which never
   // reaches this app directly) end up updating state in exactly one place
      if (row == -1 && isCasting ()) {
         Dbg.log ("TunzCast", "next: casting, sending queueNext")
      // the receiver drops its in-flight HTTP connection to the current
      // song the instant it acts on this - that's an expected side effect
      // of skipping, not a playback failure, so tell handleHttpStreamError
      // to ignore it til handleCastQueueAdvance() confirms the skip landed
         castSkipInFlight = true
         castSession?.remoteMediaClient?.queueNext (null)?.setResultCallback {
            r ->
            if (! r.status.isSuccess) {
               Dbg.log ("TunzCast", "queueNext failed: " +
                  "${r.status.statusCode} ${r.status.statusMessage}")
               castSkipInFlight = false
            }
         }
         return
      }
      mplay?.stop ()
      mplay?.reset ()
     val removedPos: Int
      if (row == -1)  removedPos = advanceLocal ()
      else {
         removedPos = -1
         ppos = row
      }
      if (ppos < play.size) {
         song = play [ppos]
         loadAlbumArt ()
         if (isCasting ())  loadCastQueue ()
         else {
            try {
               mplay?.setDataSource ("$path/$song")
               mplay?.prepare ()
               mplay?.start ()
               mplay?.setOnCompletionListener { localErrorStreak = 0; next () }
            }
            catch (e: Exception) {
               Log.e ("TunzLocal", "prepare failed for $song", e)
               Dbg.log ("TunzLocal", "prepare failed for $song: $e")
               localErrorStreak++
               if (localErrorStreak <= 3)  next ()
            }
         }
         updateMediaSession ()
         postNotification ()
      }
      Dbg.log ("TunzSkip",
               "next exit ppos=$ppos playSize=${play.size} song=$song")
      callback?.onSongChanged (removedPos, ppos)
   }


   fun rateSong (rating: String): String?
   // rename the playing song's rating suffix, keep it playing til next ()
   {  if (song.isEmpty ())  return null
     val oldFn     = song
     val oldRating = splitfn (oldFn).dir
     val newFn     = renameRating (oldFn, rating)
      if (newFn == oldFn)  return oldFn
      if (File (path, newFn).exists ()) {
         Log.w ("TunzRate", "rate skip, exists: $newFn")
         return null
      }
      if (! File (path, oldFn).renameTo (File (path, newFn))) {
         Log.w ("TunzRate", "rate rename failed: $oldFn -> $newFn")
         return null
      }
      play [ppos] = newFn
      song = newFn
      mp3.find { it.dir == oldRating }?.fn?.remove (oldFn)
     var bucket = mp3.find { it.dir == rating }
      if (bucket == null) {
         bucket = FNList (rating, mutableListOf ())
         mp3.add (bucket)
      }
      bucket.fn.add (newFn)
      return newFn
   }


   fun deleteSong (): Boolean
   // permanently remove the current mp3 - unlike rateSong () this does
   // NOT keep it playing, moves on immediately like next ()
   {  if (song.isEmpty ())  return false
     val oldFn = song
      try { File (path, oldFn).delete () }
      catch (e: Exception) { }
     val d = splitfn (oldFn).dir
      mp3.find { it.dir == d }?.fn?.remove (oldFn)
      Dbg.log ("TunzRate", "deleted $oldFn")
      next ()
      return true
   }


// pause on a noisy-audio blip (bt route hiccup, headphones out, etc), then
// watch for the bt route coming right back so we don't just sit silent
// waiting on an external play/skip command that may take a while (or never
// arrive) - see docs/do.txt notes on the "Hey Google, next song" gap
// NOTE: transportControls.pause() dispatches to onPause() above, which
// (for a manual pause) cancels any watchdog left running from an
// earlier noisy event. Without noisyPauseGuard, that same cancel would
// immediately kill the watchdog we're about to arm right here - onPause()
// fires (sync or posted, doesn't matter) before the first poll ever gets
// a chance to run, so it looked like the watchdog never existed. The
// guard tells onPause() "this pause is mine, don't cancel."
   private fun handleNoisyPause ()
   {  noisyPauseGuard = true
      btReconnectedSinceNoisy = false
      Dbg.log ("TunzSkip", "handleNoisyPause: pausing, arming watchdog guard")
      mediaSession.controller.transportControls.pause ()
      startBtResumeWatchdog ()
   }

   private fun isBtA2dpConnected (): Boolean =
      (getSystemService (Context.AUDIO_SERVICE) as AudioManager)
         .getDevices (AudioManager.GET_DEVICES_OUTPUTS)
         .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

   private fun outputDevicesDump (): String =
      (getSystemService (Context.AUDIO_SERVICE) as AudioManager)
         .getDevices (AudioManager.GET_DEVICES_OUTPUTS)
         .joinToString { "${it.type}:${it.productName}" }

// getDevices() lags the real disconnect: on every real car-BT-off event
// seen in tunz_debug.log, the noisy broadcast fired, getDevices() still
// showed the A2DP device connected for another 500ms-2s, and only then
// did onAudioDevicesRemoved() actually fire. Polling isBtA2dpConnected()
// on a fixed timer reads that stale snapshot and "resumes" playback right
// as the car is disconnecting - which is what made the app start blaring
// audio at the exact moment the car turned off. Waiting on
// btReconnectedSinceNoisy instead means "a real add event happened after
// we armed," which can't be stale the same way.
   private fun startBtResumeWatchdog ()
   {  btWatchdogHandler.removeCallbacksAndMessages (null)
     val deadline = SystemClock.elapsedRealtime () + BT_WATCHDOG_TIMEOUT_MS
      Dbg.log ("TunzSkip",
               "bt watchdog: armed, timeout=${BT_WATCHDOG_TIMEOUT_MS}ms " +
               "poll=${BT_WATCHDOG_POLL_MS}ms")
      fun poll ()
      {  val remaining = deadline - SystemClock.elapsedRealtime ()
         if (btReconnectedSinceNoisy) {
            Dbg.log ("TunzSkip",
                     "bt watchdog: route back, resuming " +
                     "(remaining=${remaining}ms)")
            mediaSession.controller.transportControls.play ()
         }
         else if (remaining > 0) {
            Dbg.log ("TunzSkip",
                     "bt watchdog: poll, still no route, " +
                     "remaining=${remaining}ms")
            btWatchdogHandler.postDelayed ({ poll () }, BT_WATCHDOG_POLL_MS)
         }
         else
            Dbg.log ("TunzSkip",
                     "bt watchdog: gave up, staying paused, " +
                     "outputs=[${outputDevicesDump ()}]")
      }
      btWatchdogHandler.postDelayed ({ poll () }, BT_WATCHDOG_POLL_MS)
   }


   private fun updateMediaSession ()
   {  if (song.isEmpty ())  return
   // while casting, mplay is intentionally paused - report the remote
   // player's actual state instead, or the session always looks
   // "paused" and the OS won't route volume keys to it
     val playing = if (isCasting ())
                      castSession?.remoteMediaClient?.mediaStatus
                         ?.playerState == MediaStatus.PLAYER_STATE_PLAYING
                   else  mplay?.isPlaying == true
     val state = if (playing)  PlaybackStateCompat.STATE_PLAYING
                 else           PlaybackStateCompat.STATE_PAUSED
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


class BTDisco (private val mp: MediaPlayer,
               private val onNoisy: () -> Unit): BroadcastReceiver ()
// if bluetooth disconnects, don't keep playin !!
{  override fun onReceive (context: Context, intent: Intent)
   {  if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
         Dbg.log ("TunzSkip",
                  "audio becoming noisy, isPlaying=${mp.isPlaying}")
         if (mp.isPlaying)  onNoisy ()
      }
   }
}
