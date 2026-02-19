package app.shaz.tunz

import android.os.Environment
import android.os.Bundle
import android.util.Log
import java.io.File
import android.media.MediaPlayer
import android.view.Menu
import android.view.MenuItem
/*
import androidx.constraintlayout.widget.ConstraintLayout
import android.view.ViewGroup
import android.widget.CheckBox
 */
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.snackbar.Snackbar
import app.shaz.tunz.databinding.ActivityMainBinding

data class FNList (
   val dir: String,
   var fn:  MutableList<String>
)

class MainActivity: AppCompatActivity ()
{  private lateinit var appBarCfg: AppBarConfiguration
   private lateinit var binding:   ActivityMainBinding

   private var mplay: MediaPlayer? = null
   private var path = ""
   private var mp3  = mutableListOf<FNList>()

   private var rand: Boolean = true
   private var pick = mutableListOf<String>()
   private var done = mutableListOf<String>()

   private var play = mutableListOf<String>()
   private var song = ""
   private var ppos = 0

   fun next ()
   {
      Log.d ("Files", "next start")
      mplay?.stop ()
      mplay?.reset ()
      Log.d ("Files", "song done")
      done.add (song)

      Log.d ("Files", "bump ppos")
      ppos++
      if (ppos < play.size) {
         song = play [ppos]
         Log.d ("Files", "ppos=$ppos fn=$song")
         mplay?.setDataSource ("$path/$song")
         mplay?.prepare ()
         mplay?.start ()
         mplay?.setOnCompletionListener { next () }
      }
   }

   fun rePlay ()
   // use rand and pick to make playlist from mp3
   {
      Log.d ("Files", "rePlay start")
      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.reset ()

      play = mutableListOf ()
      pick.forEach { p ->
         mp3.forEach { m ->
            if (p == m.dir)  m.fn.forEach { fn ->
               play.add ("$p/$fn")
            }
         }
      }
      if (rand) {
         play.removeAll (done)
         play.shuffle ()
      }
      ppos = 0
//      Log.d ("Files", "gonna set textview")
//     val tv: TextView = findViewById (R.id.textview_second)
//      Log.d ("Files", "a")
//      tv.text = play.joinToString (separator = "\n")
//      Log.d ("Files", "b")

       if (play.isEmpty ())  return

      song = play [ppos]
      Log.d ("Files", "gonna play $song")
      mplay?.setDataSource ("$path/$song")
      mplay?.prepare ()
      mplay?.start ()
      mplay?.setOnCompletionListener { next () }
   }

   override fun onCreate (state: Bundle?)
   {  super.onCreate (state)
      binding = ActivityMainBinding.inflate (layoutInflater)
      setContentView      (binding.root)
      setSupportActionBar (binding.toolbar)
     val nav = findNavController (R.id.nav_host_fragment_content_main)
      appBarCfg = AppBarConfiguration (nav.graph)
      setupActionBarWithNavController (nav, appBarCfg)
      binding.fab.setOnClickListener { view ->
         Snackbar.make (view, "...your action", Snackbar.LENGTH_LONG)
            .setAction ("Action", null).setAnchorView (R.id.fab).show ()
      }

   // open mediaplayer
      mplay = MediaPlayer ()

   // load persisted vars
     val p = getSharedPreferences ("prf", MODE_PRIVATE)
      rand = p.getBoolean   ("rand", true)
      pick = p.getStringSet ("pick",
                             emptySet ())?.toMutableList () ?: mutableListOf ()
      done = p.getStringSet ("done",
                             emptySet ())?.toMutableList () ?: mutableListOf ()
//    done.forEach { Log.d("Files", "done=$it") }

   // list off Music into path, mp3
      path = Environment.getExternalStorageDirectory ().toString () + "/Music"
     val mus = File (path).listFiles ()
      Log.d("Files", path + ' ' + mus!!.size)
      for (i in mus.indices) {
         if (! mus [i]!!.isDirectory ())  continue
        val dr = mus [i]!!.getName ()
         if (dr == ".thumbnails")         continue

        val d2 = File ("$path/$dr").listFiles ()
         Log.d("Files", "mus dir=$dr files=${d2!!.size}")
        val ls: Array<String> = d2.map { it.getName () }.toTypedArray ()
         ls.sort ()
         mp3.add (FNList (dr, ls.toMutableList ()))


         if (dr != "An")  pick.add (dr)
      }

      rePlay ()

   // init gui
/*
     val lo = findViewById<ConstraintLayout>(R.id.pickLayout)
     val cb = CheckBox (this)
      cb.text = "rand"
      cb.layoutParams = ConstraintLayout.LayoutParams (
         ViewGroup.LayoutParams.WRAP_CONTENT,
         ViewGroup.LayoutParams.WRAP_CONTENT
      )
      cb.setOnCheckedChangeListener { cb, chk ->
         rand = chk
         rePlay ()
      }
      lo.addView (cb)

      mp3.forEach { d ->
        val cb = CheckBox (this)
         cb.text = d.dir
         cb.layoutParams = ConstraintLayout.LayoutParams (
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
         )
         cb.setOnCheckedChangeListener { cb, chk ->
            if (chk) pick.add    (cb.text.toString ())
            else     pick.remove (cb.text.toString ())
            rePlay ()
         }
         lo.addView (cb)
      }
*/
   }

   override fun onDestroy ()
   {  super.onDestroy ()

   // close mediaplayer
      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.release ()
      mplay = null

   // persist vars
     val e = getSharedPreferences ("prf", MODE_PRIVATE).edit ()
      e.putBoolean   ("rand", rand).apply ()
      e.putStringSet ("pick", pick.toSet ()).apply ()
      e.putStringSet ("done", done.toSet ()).apply ()
   }


   override fun onCreateOptionsMenu (menu: Menu): Boolean
   // Inflate the menu; this adds items to the action bar if it is present.
   {  menuInflater.inflate (R.menu.menu_main, menu)
      return true
   }

   override fun onOptionsItemSelected (item: MenuItem): Boolean
   // Handle action bar item clicks here. The action bar will
   // automatically handle clicks on the Home/Up button, so long
   // as you specify a parent activity in AndroidManifest.xml.
   {  return when (item.itemId) {
         R.id.action_settings -> true
         else -> super.onOptionsItemSelected (item)
      }
   }

   override fun onSupportNavigateUp (): Boolean
   {  return findNavController (R.id.nav_host_fragment_content_main)
                .navigateUp (appBarCfg) || super.onSupportNavigateUp ()
   }
}
