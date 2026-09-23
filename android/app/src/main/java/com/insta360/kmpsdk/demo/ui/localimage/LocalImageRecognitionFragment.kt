package com.insta360.kmpsdk.demo.ui.localimage

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentLocalImageRecognitionBinding
import com.insta360.kmpsdk.demo.raw.decodeSampledBitmap
import com.insta360.kmpsdk.demo.raw.toYuv420Frame
import com.insta360.kmpsdk.demo.touchscene.AndroidHapticRenderer
import com.insta360.kmpsdk.demo.touchscene.AndroidSpeechOutput
import com.insta360.kmpsdk.demo.touchscene.MlKitSceneDescriber
import com.insta360.kmpsdk.demo.touchscene.OpenCvTactileFrameProcessor
import com.insta360.kmpsdk.demo.touchscene.RemoteAiSceneDescriber
import com.insta360.kmpsdk.demo.touchscene.TactileDebugMode
import com.insta360.kmpsdk.demo.touchscene.TactileSessionState
import com.insta360.kmpsdk.demo.touchscene.TouchSceneCoordinator
import com.insta360.kmpsdk.demo.touchscene.TouchSceneUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** A camera-independent, auto-processing tactile flow for one image selected from the phone. */
class LocalImageRecognitionFragment : Fragment() {
    private var _binding: FragmentLocalImageRecognitionBinding? = null
    private val binding get() = _binding!!
    private var coordinator: TouchSceneCoordinator? = null
    private var haptics: AndroidHapticRenderer? = null
    private var speechOutput: AndroidSpeechOutput? = null
    private var descriptionRequested = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentLocalImageRecognitionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.findViewById<View>(R.id.bottom_nav)?.visibility = View.GONE
        binding.backBtn.setOnClickListener { findNavController().navigateUp() }

        val renderer = AndroidHapticRenderer(requireContext())
        haptics = renderer
        speechOutput = AndroidSpeechOutput(requireContext())
        binding.tactileMapView.hapticRenderer = renderer
        binding.tactileMapView.explorationEnabled = false
        binding.tactileMapView.onDebugModeChanged = { mode ->
            val (label, spoken) = when (mode) {
                TactileDebugMode.EDGE ->
                    R.string.touchscene_local_image_layer_edge to R.string.touchscene_layer_to_edge
                TactileDebugMode.BINARY ->
                    R.string.touchscene_local_image_layer_outline to R.string.touchscene_layer_to_binary
                else ->
                    R.string.touchscene_local_image_layer_original to R.string.touchscene_layer_to_original
            }
            _binding?.layerLabel?.setText(label)
            speechOutput?.speak(getString(spoken))
            haptics?.playSuccess()
        }

        coordinator =
            TouchSceneCoordinator(
                frameProcessor = OpenCvTactileFrameProcessor(),
                describer = RemoteAiSceneDescriber(
                    requireContext(),
                    fallback = MlKitSceneDescriber(requireContext()),
                ),
                descriptionTimeoutMs = DESCRIPTION_TIMEOUT_MS,
                onUpdate = ::renderUpdate,
            )

        val uri = arguments?.getString(ARG_IMAGE_URI)?.let(Uri::parse)
        if (uri == null) {
            showImageFailure(null)
        } else {
            processImage(uri)
        }
    }

    private fun processImage(uri: Uri) {
        binding.statusText.setText(R.string.touchscene_local_image_processing)
        binding.descriptionText.setText(R.string.touchscene_local_image_processing)
        viewLifecycleOwner.lifecycleScope.launch {
            val frameResult = runCatching {
                withContext(Dispatchers.IO) {
                    val bitmap = checkNotNull(
                        decodeSampledBitmap(requireContext().contentResolver, uri, MAX_IMAGE_LONG_EDGE),
                    )
                    try {
                        val now = SystemClock.elapsedRealtime()
                        bitmap.toYuv420Frame(now, now)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            if (_binding == null) return@launch
            frameResult.onSuccess { coordinator?.analyzeAndFreeze(it) }
                .onFailure { showImageFailure(it) }
        }
    }

    private fun renderUpdate(update: TouchSceneUpdate) {
        _binding?.root?.post {
            val b = _binding ?: return@post
            b.tactileMapView.submitMap(update.map)
            b.tactileMapView.submitDebugSnapshot(update.debugSnapshot)
            val ready = update.sessionState == TactileSessionState.EXPLORING && update.map != null
            b.tactileMapView.explorationEnabled = ready
            if (ready && !descriptionRequested) {
                descriptionRequested = true
                b.statusText.setText(R.string.touchscene_local_image_describing)
                requestDescription()
            }
        }
    }

    private fun requestDescription() {
        coordinator?.describeLatest { description ->
            _binding?.root?.post {
                val b = _binding ?: return@post
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return@post
                if (description == null) {
                    b.statusText.setText(R.string.touchscene_local_image_description_failed)
                    b.descriptionText.setText(R.string.touchscene_local_image_description_failed)
                    speechOutput?.speak(getString(R.string.touchscene_local_image_description_failed))
                } else {
                    b.statusText.setText(R.string.touchscene_local_image_ready)
                    b.descriptionText.text = description.text
                    b.descriptionText.contentDescription = description.text
                    speechOutput?.speak(description.text)
                }
            }
        }
    }

    private fun showImageFailure(error: Throwable?) {
        if (error != null) Timber.w(error, "local image decoding failed")
        _binding?.statusText?.setText(R.string.touchscene_local_image_failed)
        _binding?.descriptionText?.setText(R.string.touchscene_local_image_failed)
        _binding?.tactileMapView?.explorationEnabled = false
        speechOutput?.speak(getString(R.string.touchscene_local_image_failed))
    }

    override fun onPause() {
        super.onPause()
        haptics?.cancel()
        speechOutput?.stop()
    }

    override fun onDestroyView() {
        coordinator?.close()
        coordinator = null
        binding.tactileMapView.hapticRenderer = null
        haptics?.cancel()
        haptics = null
        speechOutput?.close()
        speechOutput = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_IMAGE_URI = "localImageUri"
        private const val MAX_IMAGE_LONG_EDGE = 1_600
        private const val DESCRIPTION_TIMEOUT_MS = 20_000L
    }
}
