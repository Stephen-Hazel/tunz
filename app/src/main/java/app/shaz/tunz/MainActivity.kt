package app.shaz.tunz

import android.os.Environment
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import app.shaz.tunz.databinding.ActivityMainBinding
import android.util.Log
import java.io.File
import android.media.MediaPlayer
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TableRow
import android.widget.TextView

data class FNList (
   val dir: String,
   var fn:  MutableList<String>
)


class MainActivity: AppCompatActivity ()
{  private lateinit var b: ActivityMainBinding

   private var mplay: MediaPlayer? = null
   private var path = ""
   private var mp3  = mutableListOf<FNList>()

   private var shuf: Boolean = true
   private var pick = mutableListOf<String>()
   private var done = mutableListOf<String>()

   private var play = mutableListOf<String>()
   private var song = ""
   private var ppos = 0

   fun next ()
   {  mplay?.stop ()
      mplay?.reset ()
Log.d ("Files", "song done")
      done.add (song)
      play.removeAt (ppos)
      b.loTbl.removeViews (ppos, 1)

      if (ppos < play.size) {
         song = play [ppos]
Log.d ("Files", "ppos=$ppos fn=$song")
         mplay?.setDataSource ("$path/$song")
         mplay?.prepare ()
         mplay?.start ()
         mplay?.setOnCompletionListener { next () }
      }
   }

   fun rePlay (ac: MainActivity)
   // use shuf, pick, and done to make playlist from mp3
   {
Log.d ("Files", "rePlay start")
      pick.forEach { Log.d("Files", "pick=$it") }

      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.reset ()

      play = mutableListOf ()
      b.loTbl.removeAllViews ()
      if (pick.isEmpty ())  return

      pick.forEach { p -> mp3.forEach { m ->
         if (p == m.dir)  m.fn.forEach { fn -> play.add ("$p/$fn") }
      }}
      if (shuf) {
         play.removeAll (done)
         if (play.isEmpty ()) {
            done = mutableListOf ()

         // one more time sigh
            pick.forEach { p ->
               mp3.forEach { m ->
                  if (p == m.dir)  m.fn.forEach { fn ->
                     play.add ("$p/$fn")
                  }
               }
            }
         }
         play.shuffle ()
      }
      ppos = 0
      play.forEach { fn ->
        val tr = TableRow (ac)
        val tv = TextView (ac)
         tv.text = fn
         tr.addView (tv)
         b.loTbl.addView (tr)
      }

      song = play [ppos]
      Log.d ("Files", "gonna play $song")

      mplay?.setDataSource ("$path/$song")
      mplay?.prepare ()
      mplay?.start ()
      mplay?.setOnCompletionListener { next () }
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
      done.forEach { Log.d("Files", "done=$it") }
      pick.forEach { Log.d("Files", "pick=$it") }

   // list off Music into path, mp3
      path = Environment.getExternalStorageDirectory ().toString () + "/Music"
     val mus = File (path).listFiles ()
     val dir = mutableListOf<String> ()
      for (i in mus.indices) {
         if (! mus [i]!!.isDirectory ())  continue
        val n = mus [i]!!.getName ()
         if (n == ".thumbnails")  continue
         dir.add (n)
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

      rePlay (this)
   }


   override fun onDestroy ()
   {  super.onDestroy ()

   // close mediaplayer
      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.release ()
      mplay = null

   // persist vars
      Log.d("Files", "save pref start")
      pick.forEach { Log.d("Files", "pick=$it") }
     val e = getSharedPreferences ("prf", MODE_PRIVATE).edit ()
      e.putBoolean   ("shuf", shuf).commit ()
      e.putStringSet ("pick", pick.toSet ()).commit ()
      e.putStringSet ("done", done.toSet ()).commit ()
   }
}
