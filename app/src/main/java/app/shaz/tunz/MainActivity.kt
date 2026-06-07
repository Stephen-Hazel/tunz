// MainActivity.kt - tunz ui (most stuff happens in the service now)

package app.shaz.tunz

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.snackbar.Snackbar
import app.shaz.tunz.databinding.ActivityMainBinding


data class FNList (
   val dir: String,
   var fn:  MutableList<String>
)

data class FNTitle (
   val dir: String,
   val grp: String,
   val x:   String,
   val ttl: String
)


fun substr (s: String, b: Int, l: Int): String
// kotlin n java just don't do substr PROPerly if'n ya ask mee
{  return s.substring (b, b+l)
}


fun splitfn (fn: String): FNTitle
{ var s = fn
  val d = s.substringBefore ("/")
   s =    s.substringAfter  ("/")
   s = substr (s, 0, s.length-4)
   s = s.replace ("_", " ")
  val f = s.indexOf     ("-")
  val l = s.lastIndexOf ("-")
  var g: String
  var t: String
  var x: String
   if (f != -1) {
      g = substr (s, 0, f).trim ()
      t = s.substring (l+1).trim ()
      if (f == l)  x = ""
      else         x = substr (s, f+1, l-f-1).trim ()
   }
   else {
      g = ""
      t = s.trim ()
      x = ""
   }
   return FNTitle (d, g, x, t)
}


fun fmtfn (fn: String): SpannableString
// i just want some bold...
{ val fnt = splitfn (fn)
  val ss  = SpannableString ("${fnt.grp}  ${fnt.ttl}  ${fnt.x}  ${fnt.dir}")
  var b   = ss.indexOf (fnt.ttl)
   if (b != -1)  ss.setSpan (StyleSpan (Typeface.BOLD), b, b + fnt.ttl.length,
                             Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
   b = ss.indexOf (fnt.dir)
   if (b != -1)  ss.setSpan (StyleSpan (Typeface.BOLD), b, b + fnt.dir.length,
                             Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
   return ss
}


class MainActivity: AppCompatActivity (), PlaybackCallback
{  private lateinit var b: ActivityMainBinding
   private var svc: MusicService? = null
   private var selRow: TableRow? = null
   private var sentToPerms = false

   private val conn = object: ServiceConnection
   {  override fun onServiceConnected (name: ComponentName, binder: IBinder)
      {  svc = (binder as MusicService.MusicBinder).getService ()
         svc!!.setCallback (this@MainActivity)
         initUi ()
         if (svc!!.play.isEmpty () && svc!!.pick.isNotEmpty ())
            svc!!.rePlay ()
         else if (svc!!.play.isNotEmpty ())
            onPlaylistReady (svc!!.play)
      }
      override fun onServiceDisconnected (name: ComponentName) { svc = null }
   }


   override fun onPlaylistReady (play: List<String>)
   {  val ppos = svc?.ppos ?: 0
      runOnUiThread {
         selRow = null
         b.tvCount.text = "${play.size}"
         b.loTbl.removeAllViews ()
         var id = 0
         play.forEach { fn ->
           val tr = TableRow (this)
           val tv = TextView (this)
            tv.text = fmtfn (fn)
            tr.addView (tv)
            tr.isClickable = true
            tr.isFocusable = true
            tr.isFocusableInTouchMode = true
            if (id == ppos) {
               tr.requestFocus ()
               tr.setBackgroundColor (0xFF3848AF.toInt ())
               selRow = tr
            }
            tr.id = id++
            tr.setOnClickListener { ctr ->
               svc?.next (ctr.id)
            }
            b.loTbl.addView (tr)
         }
      }
   }


   override fun onAlbumArtChanged (art: Bitmap?)
   {  runOnUiThread {
         if (art != null) {
            b.ivArt.setImageBitmap (art)
            b.ivArt.visibility = View.VISIBLE
         } else {
            b.ivArt.visibility = View.GONE
         }
      }
   }


   override fun onSongChanged (removedPos: Int, newPpos: Int)
   {  runOnUiThread {
         if (removedPos >= 0) {
            selRow = null
            b.loTbl.removeViews (removedPos, 1)
            for (i in removedPos until b.loTbl.childCount)
               b.loTbl.getChildAt (i).id = i
         }
         else {
            selRow?.setBackgroundColor (Color.TRANSPARENT)
         }
        val tr = b.loTbl.getChildAt (newPpos) ?: return@runOnUiThread
         tr.setBackgroundColor (0xFF3848AF.toInt ())
         tr.requestFocus ()
         selRow = tr as? TableRow
      }
   }


   private fun initUi ()
   {  val s = svc ?: return
      b.cbShuf.isChecked = s.shuf
      b.cbShuf.setOnCheckedChangeListener { _, chk ->
         s.setShuf (chk)
         s.rePlay ()
      }
      for (i in b.loLin.childCount - 1 downTo 0)
         if (b.loLin.getChildAt (i).id != b.cbShuf.id)
            b.loLin.removeViewAt (i)
      s.mp3.forEach { m ->
           val cb = CheckBox (this)
            cb.text = m.dir
            cb.id = View.generateViewId ()
            cb.isChecked = s.pick.contains (m.dir)
            cb.layoutParams = LinearLayout.LayoutParams (
               LinearLayout.LayoutParams.WRAP_CONTENT,
               LinearLayout.LayoutParams.WRAP_CONTENT)
            cb.setOnCheckedChangeListener { cb, chk ->
               if (chk)  s.addPick    (cb.text as String)
               else      s.removePick (cb.text as String)
               s.rePlay ()
            }
            b.loLin.addView (cb)
      }
      b.btnLyr.setOnClickListener { s.lyricsSearch () }
      try {
         CastButtonFactory.setUpMediaRouteButton (this, b.btnCast)
      }
      catch (e: Exception) { }
      b.fab.setOnClickListener { view ->
         Snackbar.make (view, "play/pause", Snackbar.LENGTH_LONG)
                 .setAction ("Action", null).setAnchorView (R.id.fab).show ()
         s.togglePlayPause ()
      }
   }


   private fun checkPerms ()
   // holy god android (pretty glad i got ai now)
   { val need = mutableListOf<String> ()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
         if (ContextCompat.checkSelfPermission (this,
                                         Manifest.permission.POST_NOTIFICATIONS)
             != PackageManager.PERMISSION_GRANTED)
            need.add (Manifest.permission.POST_NOTIFICATIONS)
         if (ContextCompat.checkSelfPermission (this,
                                           Manifest.permission.READ_MEDIA_AUDIO)
             != PackageManager.PERMISSION_GRANTED)
            need.add (Manifest.permission.READ_MEDIA_AUDIO)
      }
      else {
         if (ContextCompat.checkSelfPermission (this,
                                      Manifest.permission.READ_EXTERNAL_STORAGE)
             != PackageManager.PERMISSION_GRANTED)
            need.add (Manifest.permission.READ_EXTERNAL_STORAGE)
         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
             ContextCompat.checkSelfPermission (this,
                                     Manifest.permission.WRITE_EXTERNAL_STORAGE)
             != PackageManager.PERMISSION_GRANTED)
            need.add (Manifest.permission.WRITE_EXTERNAL_STORAGE)
      }
      if (need.isNotEmpty ())
         ActivityCompat.requestPermissions (this, need.toTypedArray (), 1)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
          ! Environment.isExternalStorageManager ()) {
        val uri = Uri.parse ("package:$packageName")
         sentToPerms = true
         startActivity (Intent (
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
      }
   }


   private fun restartService ()
   {  sentToPerms = false
      svc?.setCallback (null)
      svc = null
      unbindService (conn)
     val intent = Intent (this, MusicService::class.java)
      stopService   (intent)
      startService  (intent)
      bindService   (intent, conn, BIND_AUTO_CREATE)
   }


   override fun onResume ()
   {  super.onResume ()
      if (sentToPerms &&
          (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
           Environment.isExternalStorageManager ()))
         restartService ()
   }


   override fun onRequestPermissionsResult (
      req: Int, perms: Array<out String>, results: IntArray)
   {  super.onRequestPermissionsResult (req, perms, results)
      if (req == 1 && results.any {
               it == PackageManager.PERMISSION_GRANTED })
         restartService ()
   }


   override fun dispatchKeyEvent (event: KeyEvent): Boolean
   {  val s = svc
      if (s != null && s.isCasting () &&
          event.action == KeyEvent.ACTION_DOWN) {
         when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
               s.setCastVolume ((s.castVolume () + 0.05).coerceAtMost (1.0))
               return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
               s.setCastVolume ((s.castVolume () - 0.05).coerceAtLeast (0.0))
               return true
            }
         }
      }
      return super.dispatchKeyEvent (event)
   }


   override fun onCreate (state: Bundle?)
   {  super.onCreate (state)
      b = ActivityMainBinding.inflate (layoutInflater)
      setContentView (b.root)
      checkPerms ()

      try { CastContext.getSharedInstance (this) } catch (e: Exception) { }

     val intent = Intent (this, MusicService::class.java)
      startService (intent)
      bindService  (intent, conn, BIND_AUTO_CREATE)
   }


   override fun onDestroy ()
   {  super.onDestroy ()
      svc?.setCallback (null)
      unbindService (conn)
   }
}
