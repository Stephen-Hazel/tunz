package app.shaz.tunz

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import app.shaz.tunz.databinding.FragmentFirstBinding

// simple [Fragment] subclass as the default dest in the nav
class FirstFragment: Fragment ()
{  private var _binding: FragmentFirstBinding? = null

// only valid between onCreateView n onDestroyView.
   private val binding get () = _binding!!

   override fun onCreateView (inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View
   {  _binding = FragmentFirstBinding.inflate (inflater, container, false)
      return binding.root
   }

   override fun onViewCreated (view: View, savedInstanceState: Bundle?)
   {  super.onViewCreated (view, savedInstanceState)
      binding.buttonFirst.setOnClickListener {
         findNavController ()
            .navigate (R.id.action_FirstFragment_to_SecondFragment)
      }
   }

   override fun onDestroyView ()
   {  super.onDestroyView ()
      _binding = null
   }
}
