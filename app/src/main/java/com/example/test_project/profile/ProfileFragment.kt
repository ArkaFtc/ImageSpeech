package com.example.test_project.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import com.example.test_project.MainActivity
import com.example.test_project.R
import kotlin.math.max

/**
 * The profile page: shown once on first launch, and from the profile button on the camera
 * screen. Everything typed here stays on this phone (see [Profile]).
 */
class ProfileFragment : DialogFragment() {
    private val firstRun get() = requireArguments().getBoolean(ARG_FIRST_RUN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_Test_project_Page)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val field = view.findViewById<EditText>(R.id.editDietary)
        if (savedInstanceState == null) field.setText(Profile.dietary(requireContext()))
        // Keeps the page clear of the status bar, navigation bar and keyboard.
        val padding = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.updatePadding(top = bars.top, bottom = padding + max(bars.bottom, keyboard.bottom))
            insets
        }
        val host = requireActivity() as MainActivity
        view.findViewById<View>(R.id.txtProfileGemmaNote).visibility = if (host.hasGemma) View.GONE else View.VISIBLE
        view.findViewById<Button>(R.id.btnProfileSkip).setText(if (firstRun) R.string.profile_skip else R.string.cancel)
        view.findViewById<Button>(R.id.btnProfileSave).setOnClickListener {
            Profile.save(requireContext(), field.text.toString())
            Toast.makeText(requireContext(), R.string.profile_saved, Toast.LENGTH_SHORT).show()
            dismiss()
        }
        view.findViewById<Button>(R.id.btnProfileSkip).setOnClickListener {
            Profile.markSetUp(requireContext())
            dismiss()
        }
    }

    companion object {
        const val TAG = "profile"
        private const val ARG_FIRST_RUN = "first_run"

        fun newInstance(firstRun: Boolean) = ProfileFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_FIRST_RUN, firstRun) }
        }
    }
}
