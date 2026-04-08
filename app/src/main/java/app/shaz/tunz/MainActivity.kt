package app.shaz.tunz

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
   private var dirCheckboxesAdded = false

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
      if (! dirCheckboxesAdded) {
         dirCheckboxesAdded = true
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
      }
      b.btnLyr.setOnClickListener { s.lyricsSearch () }
      b.fab.setOnClickListener { view ->
         Snackbar.make (view, "play/pause", Snackbar.LENGTH_LONG)
                 .setAction ("Action", null).setAnchorView (R.id.fab).show ()
         s.togglePlayPause ()
      }
   }


   override fun onCreate (state: Bundle?)
   {  super.onCreate (state)
      b = ActivityMainBinding.inflate (layoutInflater)
      setContentView (b.root)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
         if (ContextCompat.checkSelfPermission (this, Manifest.permission.POST_NOTIFICATIONS)
               != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions (
               this, arrayOf (Manifest.permission.POST_NOTIFICATIONS), 1)
      }

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
