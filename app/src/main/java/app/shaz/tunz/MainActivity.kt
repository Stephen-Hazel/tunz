package app.shaz.tunz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Environment
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import app.shaz.tunz.databinding.ActivityMainBinding
import android.util.Log
import java.io.File
import android.media.MediaPlayer
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.net.toUri


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


class MainActivity: AppCompatActivity ()
{  private lateinit var b: ActivityMainBinding

   private var mplay: MediaPlayer? = null
   private var path = ""
   private var mp3  = mutableListOf<FNList>()

   private var shuf: Boolean = true
   private var pick = mutableListOf<String>()
   private var done = mutableListOf<String>()
   private var skip = mutableListOf<String>()

   private var play = mutableListOf<String>()
   private var song = ""
   private var ppos = 0
   private var selRow: TableRow? = null

   private lateinit var btDisco: BTDisco    // is this word salad necessary?
   private val intentFilter = IntentFilter (
                                       AudioManager.ACTION_AUDIO_BECOMING_NOISY)

   private class BTDisco (private val mp: MediaPlayer): BroadcastReceiver () {
      override fun onReceive (context: Context, intent: Intent)
      {  if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (mp.isPlaying) mp.pause ()
      }
   }


   fun substr (s: String, b: Int, l: Int): String     // good grief
   {  return s.substring (b, b+l);  }


   fun splitfn (fn: String): FNTitle
   { var s = fn
     val d = s.substringBefore ("/")   // dir
      s =    s.substringAfter  ("/")
      s = substr (s, 0, s.length-4)    // toss dir/ and .mp3
      s = s.replace ("_", " ")         // _ => space
     val f = s.indexOf     ("-")       // group - year album song# - title
     val l = s.lastIndexOf ("-")
     var g: String
     var t: String
     var x: String
      if (f != -1) {                   // l must have been set too
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
      if (b != -1)  ss.setSpan (StyleSpan (Typeface.BOLD), b, b + fnt.ttl.length
,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      b      = ss.indexOf (fnt.dir)
      if (b != -1)  ss.setSpan (StyleSpan (Typeface.BOLD), b, b + fnt.dir.length
,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      return ss
   }


   fun lyr ()
   {
   /*
     val fnt = splitfn (song)
     val url = "https://google.com/search?q=lyrics \"${fnt.ttl}\" \"${fnt.grp}\"
"
     val int = Intent (Intent.ACTION_VIEW, url.toUri ())
      startActivity (int)
    */
   }


   fun next (row: Int = -1)
   {  mplay?.stop ()
      mplay?.reset ()
Log.d ("Files", "song done row=$row")
      if (row == -1) {
         done.add (song)
         play.removeAt (ppos)
         b.loTbl.removeViews (ppos, 1)
        val tr = b.loTbl.getChildAt (ppos)
         tr.requestFocus ()
         tr.setBackgroundColor (0xFF3848AF.toInt ())
         selRow = tr as TableRow
         for (i in ppos until play.size) {
           val tr = b.loTbl.getChildAt (i)
            tr.id = i  // old val - 1
         }
      }
      else {
         if (! skip.contains (song))  skip.add (song)
         ppos = row
      }

      if (ppos < play.size) {
         song = play [ppos]
//Log.d ("Files", "ppos=$ppos fn=$song")
         mplay?.setDataSource ("$path/$song")
         mplay?.prepare ()
         mplay?.start ()
         lyr ()
      }
   }


   fun pick2play ()
   {  pick.forEach { p ->
         mp3.forEach { m ->
            if (p == m.dir)  m.fn.forEach { fn ->
               play.add ("$p/$fn")
            }
         }
      }
   }


   fun rePlay (ac: MainActivity)
   // use shuf, pick, and done to make playlist from mp3
   {
//Log.d ("Files", "rePlay start")
//pick.forEach { Log.d("Files", "pick=$it") }
      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.reset ()

      play = mutableListOf ()
      b.loTbl.removeAllViews ()
      if (pick.isEmpty ())  return

      pick2play ()
      if (shuf) {
         play.removeAll (done)
         if (play.isEmpty ()) {
            done = mutableListOf ()
            pick2play ()
         }
         play.shuffle ()
      }
      else
         play.sortBy { fmtfn (it).toString () }
      ppos = 0

     var id = 0
      play.forEach { fn ->
        val tr = TableRow (ac)
        val tv = TextView (ac)
         tv.text = fmtfn (fn)
         tr.addView (tv)
         tr.isClickable = true
         tr.isFocusable = true
         tr.isFocusableInTouchMode = true
         if (id == ppos)  {
            tr.requestFocus ()
            tr.setBackgroundColor (0xFF3848AF.toInt ())
            selRow = tr
         }
         tr.id = id++
         tr.setOnClickListener { ctr ->
            selRow?.setBackgroundColor (Color.TRANSPARENT)
            ctr.setBackgroundColor (0xFF3848AF.toInt ())
            ctr.requestFocus ()
            selRow = ctr as TableRow
            next (ctr.id)
         }
         b.loTbl.addView (tr)
      }

      song = play [ppos]
//Log.d ("Files", "gonna play $song")

      mplay?.setDataSource ("$path/$song")
      mplay?.prepare ()
      mplay?.start ()
      mplay?.setOnCompletionListener { next () }
      lyr ()
   }


   override fun onCreate (state: Bundle?)
   {  super.onCreate (state)
      b = ActivityMainBinding.inflate (layoutInflater)
      setContentView (b.root)

      mplay = MediaPlayer ()

   // load persisted vars
     val p = getSharedPreferences ("prf", MODE_PRIVATE)
      shuf = p.getBoolean   ("shuf", true)
      pick = p.getStringSet ("pick",
             emptySet ())?.toMutableList () ?: mutableListOf ()
      done = p.getStringSet ("done",
             emptySet ())?.toMutableList () ?: mutableListOf ()
      skip = p.getStringSet ("skip",
             emptySet ())?.toMutableList () ?: mutableListOf ()
skip.forEach { Log.d("Files", it) }

   // list off Music into path, mp3
      path = Environment.getExternalStorageDirectory ().toString () +
             "/Music/tunz"
     val mus = File (path).listFiles ()
     val dir = mutableListOf<String> ()
      for (i in mus.indices) {
         val dn = mus [i].getName ()
          Log.d("Files", dn)
          if (dn != ".thumbnails")  dir.add (dn)
      }
      dir.sort ()
      dir.forEach { d ->
        val dl = File ("$path/$d").listFiles ()
Log.d("Files", "mus dir=$d files=${dl!!.size}")
        val ls: Array<String> = dl.map { it.getName () }.toTypedArray ()
         ls.sort ()
         mp3.add (FNList (d, ls.toMutableList ()))
      }

   // init gui
      b.cbShuf.setChecked (shuf)
      b.cbShuf.setOnCheckedChangeListener { _, chk ->
         shuf = chk
         rePlay (this)
      }

      mp3.forEach { m ->
        val cb = CheckBox (this)
         cb.text = m.dir
         cb.id = View.generateViewId ()
         cb.setChecked (pick.contains (m.dir))
         cb.layoutParams = LinearLayout.LayoutParams (
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
         cb.setOnCheckedChangeListener { cb, chk ->
            if (chk)  pick.add    (cb.text as String)
            else      pick.remove (cb.text as String)
            rePlay (this)
         }
         b.loLin.addView (cb)
      }

      b.fab.setOnClickListener { view ->
         Snackbar.make (view, "play/pause", Snackbar.LENGTH_LONG)
                 .setAction ("Action", null).setAnchorView (R.id.fab).show ()
         if (mplay?.isPlaying == true)  mplay?.pause ()
         else                           mplay?.start ()
      }

      btDisco = BTDisco (mplay!!)
      registerReceiver (btDisco, intentFilter)

      rePlay (this)
   }


   override fun onDestroy ()
   {  super.onDestroy ()

   // close mediaplayer
      unregisterReceiver (btDisco)
      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.release ()
      mplay = null

   // persist vars
//Log.d("Files", "save pref start")
//pick.forEach { Log.d("Files", "pick=$it") }
     val e = getSharedPreferences ("prf", MODE_PRIVATE).edit ()
      e.putBoolean   ("shuf", shuf).commit ()
      e.putStringSet ("pick", pick.toSet ()).commit ()
      e.putStringSet ("done", done.toSet ()).commit ()
      e.putStringSet ("skip", skip.toSet ()).commit ()
   }
}
