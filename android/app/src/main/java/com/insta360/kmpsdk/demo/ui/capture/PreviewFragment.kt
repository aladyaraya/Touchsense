package com.insta360.kmpsdk.demo.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.preview.CameraStreamListener
import com.arashivision.sdk.camera.api.preview.PreviewStreamFrame
import com.arashivision.sdk.camera.api.preview.PreviewStreamParamsUpdate
import com.arashivision.sdk.camera.api.param.listener.CameraPostureUpdate
import com.arashivision.sdk.camera.core.model.CameraType
import com.arashivision.sdk.camera.core.model.FunctionMode
import com.arashivision.sdk.camera.core.model.FunctionType
import com.arashivision.sdk.camera.core.model.notify.CameraPosture
import com.arashivision.sdk.camera.core.model.option.SensorMode
import com.arashivision.sdk.camera.core.model.option.VideoEncode
import com.arashivision.sdk.common.exception.NativeException
import com.arashivision.sdk.media.api.common.OffsetType
import com.arashivision.sdk.media.api.common.RenderModel
import com.arashivision.sdk.media.api.common.StabType
import com.arashivision.sdk.media.api.listener.PlayerViewListener
import com.arashivision.sdk.media.api.params.PreviewParams
import com.arashivision.sdk.media.core.model.OffsetData
import com.arashivision.sdk.media.player.preview.InstaCapturePlayerView
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentPreviewBinding
import com.insta360.kmpsdk.demo.raw.LatestCameraFrameStore
import com.insta360.kmpsdk.demo.raw.RawPreviewFramePipeline
import com.insta360.kmpsdk.demo.touchscene.AndroidHapticRenderer
import com.insta360.kmpsdk.demo.touchscene.runPreviewCleanup
import com.insta360.kmpsdk.demo.touchscene.runPreviewSdkSwitch
import com.insta360.kmpsdk.demo.touchscene.AndroidSpeechOutput
import com.insta360.kmpsdk.demo.touchscene.AutomaticSceneAnnouncementPolicy
import com.insta360.kmpsdk.demo.touchscene.SceneDescription
import com.insta360.kmpsdk.demo.touchscene.StabilityState
import com.insta360.kmpsdk.demo.touchscene.MlKitSceneDescriber
import com.insta360.kmpsdk.demo.touchscene.TactileDebugMode
import com.insta360.kmpsdk.demo.touchscene.TactileSessionState
import com.insta360.kmpsdk.demo.touchscene.TouchSceneCoordinator
import com.insta360.kmpsdk.demo.touchscene.TouchSceneTrace
import com.insta360.kmpsdk.demo.touchscene.TouchScenePerformanceMetrics
import com.insta360.kmpsdk.demo.touchscene.FakeCameraGateway
import com.insta360.kmpsdk.demo.touchscene.FakeContourScene
import com.insta360.kmpsdk.demo.touchscene.TouchSceneUpdate
import com.insta360.kmpsdk.demo.touchscene.VoiceCommand
import com.insta360.kmpsdk.demo.touchscene.VoiceCommandParser
import com.insta360.kmpsdk.demo.touchscene.VoiceCaptureDispatchResult
import com.insta360.kmpsdk.demo.touchscene.dispatchVoiceCaptureAfterModeChange
import com.insta360.kmpsdk.demo.touchscene.VoiceTurnGate
import com.insta360.kmpsdk.demo.ui.common.CameraDemoDisplayLabels
import com.insta360.kmpsdk.demo.ui.common.observeCameraDisconnectedNavigateToConnection
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import com.insta360.kmpsdk.demo.ui.player.adapter.BOOLEAN_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.COLOR_PLUS_INTENSITY_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.OFFSET_TYPE_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.PROJECTION_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.STAB_TYPE_OPTIONS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.arashivision.sdk.media.core.model.WindowCropInfo as MediaWindowCropInfo

/**
 * 带实时预览的拍摄页。SDK 预览开流流程：
 *
 * 1. device.preview.init() → registerCameraStreamListener → startStream()
 *
 * 2. CameraStreamListener.onOpened（流就绪）
 *    - 调用 playerView.prepare(PreviewParams) → playerView.play()
 *
 * 3. PlayerViewListener.onLoadingFinish
 *    - playerView.getPipeline() 获取渲染管线
 *    - device.preview.setPipeline(pipeline) 绑定管线，开始送帧
 *
 * 4. CameraStreamListener.onParamsChanged
 *    - SDK 推送分辨率/fps/offset/windowCrop 变更，调用 playerView 对应 set 方法
 *
 * 切镜头/切模式统一入口：[executePreviewSwitch]，由 [PreviewRefreshStrategyDecider] 决定策略：
 * - RESTART_STREAM：完整重启 SDK 流（unregister → stopStream → 切 SDK → register → startStream），prepare 由 onOpened 触发
 * - REBUILD_PLAYER：解绑 pipeline → 切 SDK → 重新 prepare（不停 stream）
 * - NO_OP：仅执行 SDK 切换，预览自然延续
 *
 * 其他重启路径：
 * - 投影模式参数变更：直接重新 prepare（[restartPreviewPlayer]）
 *
 * 释放：
 * - device.preview.unregisterCameraStreamListener → setPipeline(null) → stopStream()
 */
class PreviewFragment : Fragment() {
    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    private val captureViewModel: CameraCaptureViewModel by viewModels {
        CameraCaptureViewModel.Factory(requireActivity().application) {
            connectionViewModel.getCameraDevice()
        }
    }

    private var lastModeOptions: List<FunctionMode> = emptyList()
    private var previewPlayerView: InstaCapturePlayerView? = null
    private var previewWidth: Int = 1280
    private var previewHeight: Int = 960
    private var streamDefaultPreviewWidth: Int = 1280
    private var streamDefaultPreviewHeight: Int = 960
    private var previewFps: Int = 30
    private var previewStarted = false
    private var rawFramePipeline: RawPreviewFramePipeline? = null
    private var rawStreamWidth: Int = 0
    private var rawStreamHeight: Int = 0
    private var rawVideoEncode: VideoEncode? = null
    private var lastRawStatusUpdateMs: Long = 0L
    private var selectedPreviewResolutionPreset: PreviewResolutionPreset =
        PreviewResolutionPreset.ORIGINAL
    private var selectedPreviewRenderModel: RenderModel = RenderModel.AUTO
    private var selectedStabType: StabType = StabType.AUTO
    private var selectedOffsetType: OffsetType = OffsetType.ORIGINAL
    private var selectedLensMode: SensorMode? = null
    private var currentUiState: CameraCaptureUiState? = null
    private var previewPlayerRestarting = false
    private var touchSceneCoordinator: TouchSceneCoordinator? = null
    private var hapticRenderer: AndroidHapticRenderer? = null
    private var speechOutput: AndroidSpeechOutput? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val voiceTurnGate = VoiceTurnGate()
    private val automaticScenePolicy = AutomaticSceneAnnouncementPolicy()
    private var manualDescriptionPending = false
    private var trace = TouchSceneTrace()
    private var performanceMetrics = TouchScenePerformanceMetrics()
    private var traceIo: ExecutorService? = null
    private val firstRawFrameLogged = AtomicBoolean(false)
    private var lastTracedSessionState: TactileSessionState? = null
    private val localDemoMode: Boolean get() = arguments?.getBoolean("touchsceneLocalDemo") == true

    private fun traceEvent(
        stage: String,
        event: String,
        code: String? = null,
        fallback: String? = null,
        mapVersion: Long? = null,
    ) {
        val entry = trace.record(stage, event, code, fallback, mapVersion)
        Timber.i("TouchSceneTrace %s", entry.toJsonLine())
    }

    private fun flushTrace() {
        val appContext = context?.applicationContext ?: return
        val output = trace.jsonLines()
        val sessionId = trace.sessionId
        traceIo?.execute {
            runCatching {
                val directory = appContext.getExternalFilesDir("touchscene-traces")
                    ?: File(appContext.filesDir, "touchscene-traces")
                check(directory.isDirectory || directory.mkdirs())
                File(directory, "touchscene-$sessionId.jsonl").writeText(output)
            }.onFailure { Timber.w(it, "save TouchScene trace") }
        }
    }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceRecognition() else speechOutput?.speak("需要麦克风权限才能使用语音命令")
        }

    private var selectedDynamicStitch: Boolean = false
    private var selectedDePurpleFilter: Boolean = false
    private var selectedColorPlus: Boolean = false
    private var selectedColorPlusIntensityIndex: Int = 0
    private var selectedImageFusion: Boolean = false

    /** 递增 nonce，过滤旧 prepare 轮次的回调。 */
    private var activePrepareNonce = 0L

    /** 收起遮罩条件：pipeline 绑定 + 首帧渲染均完成（顺序任意）。 */
    private var restartStableExpect = 0L
    private var restartStablePipelineBound = false
    private var restartStableFirstFrame = false

    private val previewLayoutChangeListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewAspect()
        }

    private val streamListener =
        object : CameraStreamListener {
            override fun onOpening() {
            }

            override fun onOpened() {
                val playerView = previewPlayerView ?: return
                Timber.d("streamListener.onOpened")
                traceEvent("preview", "stream_opened")

                // 请求一次关键帧
                val device = connectionViewModel.getCameraDevice() ?: return
                device.preview.requestStreamIframe()
                viewLifecycleOwner.lifecycleScope.launch {
                    rawVideoEncode =
                        device.system
                            .fetchVideoEncodeType()
                            .onFailure { Timber.w(it, "fetch raw preview encode type") }
                            .getOrNull()
                            ?: VideoEncode.ENCODE_H264
                    configureRawFramePipelineIfReady()
                }

                playerView.post {
                    if (!isAdded) return@post
                    viewLifecycleOwner.lifecycleScope.launch {
                        captureViewModel.ui.first { !it.busy }
                        if (!isAdded || _binding == null || !previewStarted) return@launch
                        val pv = previewPlayerView ?: return@launch
                        pv.destroyRender()
                        prepareAndPlayPreview(pv)
                    }
                }
            }

            override fun onIdle() {
                Timber.w("streamListener.onIdle")
                traceEvent("preview", "stream_idle")
            }

            override fun onStreamDataNotify(streamData: PreviewStreamFrame) {
                rawFramePipeline?.offer(streamData)
            }

            override fun onParamsChanged(paramsUpdate: PreviewStreamParamsUpdate) {
                Timber.d(
                    "onParamsChanged wxh=%dx%d fps=%d",
                    paramsUpdate.previewWidth,
                    paramsUpdate.previewHeight,
                    paramsUpdate.previewFps,
                )
                if (paramsUpdate.previewWidth > 0 && paramsUpdate.previewHeight > 0) {
                    rawStreamWidth = paramsUpdate.previewWidth
                    rawStreamHeight = paramsUpdate.previewHeight
                    configureRawFramePipelineIfReady()
                }
                val playerView = previewPlayerView ?: return
                paramsUpdate.offsetData?.let { offsetData ->
                    playerView.setOffset(
                        OffsetData(
                            offsetV1 = offsetData.offsetV1,
                            offsetV2 = offsetData.offsetV2,
                            offsetV3 = offsetData.offsetV3,
                            offsetV6 = offsetData.offsetV6
                        ),
                        paramsUpdate.stabOffset.orEmpty(),
                    )
                }
                if (paramsUpdate.previewWidth > 0 && paramsUpdate.previewHeight > 0 && paramsUpdate.previewFps > 0) {
                    val resolutionChanged =
                        previewWidth != paramsUpdate.previewWidth || previewHeight != paramsUpdate.previewHeight
                    val fpsChanged = previewFps != paramsUpdate.previewFps
                    streamDefaultPreviewWidth = paramsUpdate.previewWidth
                    streamDefaultPreviewHeight = paramsUpdate.previewHeight
                    previewWidth = paramsUpdate.previewWidth
                    previewHeight = paramsUpdate.previewHeight
                    previewFps = paramsUpdate.previewFps
                    if (resolutionChanged) {
                        playerView.setPreviewResolution(previewWidth, previewHeight)
                        applyPreviewAspect()
                    }
                    if (fpsChanged) {
                        playerView.setFps(previewFps)
                    }
                }
                paramsUpdate.windowCropInfo?.let {
                    playerView.setWindowCropInfo(
                        MediaWindowCropInfo(
                            srcWidth = it.src_width,
                            srcHeight = it.src_height,
                            dstWidth = it.dst_width,
                            dstHeight = it.dst_height,
                            offsetX = it.crop_offset_x,
                            offsetY = it.crop_offset_y,
                        ),
                    )
                }
            }
        }

    private val touchScenePostureListener =
        object : CameraPostureUpdate {
            override fun updatePosture(cameraPosture: CameraPosture) {
                val degrees =
                    when (cameraPosture) {
                        CameraPosture.CAMERA_POSTURE_ROTATE_90 -> 90
                        CameraPosture.CAMERA_POSTURE_ROTATE_180 -> 180
                        CameraPosture.CAMERA_POSTURE_ROTATE_270 -> 270
                        else -> 0
                    }
                touchSceneCoordinator?.setRotationDegrees(degrees)
            }
        }

    private fun tryDismissPreviewRestartOverlay(expect: Long) {
        if (_binding == null || !isAdded) return
        if (expect != activePrepareNonce || expect != restartStableExpect) return
        if (!restartStablePipelineBound || !restartStableFirstFrame) return
        Timber.d("dismissPreviewRestartOverlay nonce=%d", expect)
        binding.previewPlaceholderText.isVisible = false
        setPreviewPlayerRestarting(false)
    }

    private fun buildPlayerViewListener(expect: Long): PlayerViewListener =
        object : PlayerViewListener {
            override fun onLoadingStatusChanged(isLoading: Boolean) {
            }

            override fun onLoadingFinish() {
                if (_binding == null || !isAdded || expect != activePrepareNonce) {
                    return
                }
                val device = connectionViewModel.getCameraDevice() ?: return
                val playerView = previewPlayerView ?: return
                val pipeline = playerView.getPipeline()
                if (pipeline == null) {
                    Timber.w("skip bind preview pipeline: pipeline is null")
                    return
                }
                Timber.d("onLoadingFinish bind pipeline")
                device.preview.setPipeline(pipeline)
                // 请求一次关键帧
                device.preview.requestStreamIframe()
                restartStablePipelineBound = true
                tryDismissPreviewRestartOverlay(expect)
                // PlayerView 加载完成后以其实际状态为准，读取并刷新参数面板。
                // 分辨率无 getter 且 PlayerView 不会自持，须按当前 preset 重新下发；stab/offset 仅重置本地值不写回。
                syncRenderParamsFromPlayer(playerView)
                applyPreviewResolution(playerView)
                refreshPreviewParamsBottomSheet()
            }

            override fun onFail(exception: com.arashivision.sdk.common.exception.InstaException) {
                if (expect != activePrepareNonce) return
                Timber.e(exception, "preview player failed nonce=%d", expect)
                if(exception is NativeException && exception.nativeErrorCode == -4042) {
                    val pv = previewPlayerView ?: return
                    if (_binding == null || !previewStarted) return
                    Timber.d("onFail -4042: restart player nonce=%d", expect)
                    pv.destroyRender()
                    pv.post { prepareAndPlayPreview(pv) }
                }
            }

            override fun onFirstFrameRendered() {
                if (_binding == null || expect != activePrepareNonce) {
                    return
                }
                Timber.d("onFirstFrameRendered nonce=%d", expect)
                restartStableFirstFrame = true
                tryDismissPreviewRestartOverlay(expect)
            }

            override fun onReleaseCameraPipeline() {
                if (expect != activePrepareNonce) return
                Timber.d("onReleaseCameraPipeline nonce=%d", expect)
                connectionViewModel.getCameraDevice()?.preview?.setPipeline(null)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        trace = TouchSceneTrace()
        performanceMetrics = TouchScenePerformanceMetrics()
        traceIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "touchscene-trace-io") }
        firstRawFrameLogged.set(false)
        lastTracedSessionState = null
        traceEvent(
            "connection",
            "preview_page_entered",
            code = if (connectionViewModel.getCameraDevice() == null) "NO_CAMERA" else "CAMERA_AVAILABLE",
            fallback = if (localDemoMode) "local_demo" else null,
        )
        if (!localDemoMode) observeCameraDisconnectedNavigateToConnection(connectionViewModel)
        binding.previewPlaceholder.addOnLayoutChangeListener(previewLayoutChangeListener)
        setupTouchScene()

        binding.backLink.setOnClickListener { findNavController().popBackStack() }
        binding.openModeParamsSheet.setOnClickListener {
            CaptureParamsBottomSheetFragment.show(childFragmentManager)
        }
        binding.openPreviewParamsSheet.setOnClickListener {
            PreviewParamsBottomSheetFragment.show(childFragmentManager)
        }
        binding.captureBtn.setOnClickListener {
            traceEvent("capture", "requested", code = "button")
            captureViewModel.onPrimaryCaptureButtonClicked()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureViewModel.ui.collect { s ->
                    currentUiState = s
                    binding.lensSection.isVisible = s.lensRowVisible
                    selectedLensMode = s.selectedLens
                    applyPreviewGestureAvailability(previewPlayerView)
                    rebuildLensChips(s.lensOptions, s.selectedLens)
                    rebuildModeChips(s.modeOptions, s.selectedMode)
                    binding.captureBtn.text = s.captureButtonLabel
                    binding.captureProgressLabel.text = s.progressLabel.orEmpty()
                    binding.captureProgressLabel.isVisible = !s.progressLabel.isNullOrEmpty()
                    binding.captureSubStatusLabel.text = s.subStatusLabel.orEmpty()
                    binding.captureSubStatusLabel.isVisible = !s.subStatusLabel.isNullOrEmpty()
                    binding.resultPanel.isVisible = s.resultVisible
                    binding.resultSummary.text = s.resultSummary
                    applyPreviewCaptureMask(s)
                    applyOperationLockState(s)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectionViewModel.connectionUi
                    .map { it.device }
                    .distinctUntilChanged()
                    .collect { device ->
                        val visible = device != null
                        binding.sdStatusLabel.isVisible = visible
                        if (device != null) {
                            binding.sdStatusLabel.text =
                                getString(
                                    R.string.capture_sd_status_format,
                                    device.sdStatus,
                                    device.sdRemaining,
                                )
                        }
                    }
            }
        }

        captureViewModel.markCapturePreviewConfigLoadingEarly()
        captureViewModel.onAppear()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureViewModel.effect.collect { effect ->
                    if (effect is CaptureUiEffect.RecordingStarted) {
                        traceEvent("capture", "recording_started", code = effect.functionMode.toString())
                        hapticRenderer?.playSuccess()
                        val message = getString(R.string.touchscene_recording_started)
                        binding.touchsceneStatus.text = message
                        speechOutput?.speak(message)
                    }
                    if (effect is CaptureUiEffect.CaptureFinished) {
                        traceEvent("capture", "finished", code = effect.functionMode.toString())
                        hapticRenderer?.playSuccess()
                        val message = getString(
                            if (effect.functionMode.functionType == FunctionType.VIDEO)
                                R.string.touchscene_recording_stopped
                            else R.string.touchscene_photo_finished,
                        )
                        binding.touchsceneStatus.text = message
                        speechOutput?.speak(message)
                    }
                    if (effect is CaptureUiEffect.CaptureFailed) {
                        traceEvent("capture", "failed", code = effect.nativeErrorCode?.toString() ?: effect.errorType)
                        hapticRenderer?.playError()
                        speechOutput?.speak(getString(R.string.touchscene_capture_failed))
                    }
                    if (effect is CaptureUiEffect.CaptureFinished
                        && effect.functionMode.functionType == FunctionType.VIDEO
                        && previewStarted
                    ) {
                        val needsRestart = connectionViewModel.getCameraDevice()
                            ?.system?.getSupportConfig()?.getOrNull()
                            ?.supportNewCaptureControlFlow() != true
                        if (needsRestart && !previewPlayerRestarting) {
                            captureViewModel.ui.first { !it.busy }
                            val device = connectionViewModel.getCameraDevice() ?: return@collect
                            Timber.d("CaptureFinished video: restart stream")
                            doRestartStream(device) { true }
                        }
                    }
                    if (effect is CaptureUiEffect.RecordResolutionChanged
                        && previewStarted
                        && needsResolutionChangeStreamRestartQuirk()
                    ) {
                        // TODO: 临时兜底，等相机固件修复后移除。
                        // X5 延时录像/移动延时改分辨率后相机侧会自行重启预览流但不回调，延迟兜底重启整条预览流重新同步
                        Timber.d("RecordResolutionChanged: X5 timelapse quirk, schedule fallback stream restart in 1000ms")
                        delay(1000)
                        if (!isAdded || _binding == null || !previewStarted || previewPlayerRestarting) {
                            Timber.d("RecordResolutionChanged: skip fallback restart, state changed")
                            return@collect
                        }
                        val device = connectionViewModel.getCameraDevice() ?: return@collect
                        Timber.d("RecordResolutionChanged: fallback restart stream")
                        doRestartStream(device) { true }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.d("onStart previewStarted=%s", previewStarted)
        lifecycleScope.launch {
            captureViewModel.lockCameraScreen()
            startPreviewIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        traceEvent(
            "performance",
            "map_window",
            code = performanceMetrics.snapshot().traceCode(),
            fallback = if (localDemoMode) "local_demo" else null,
        )
        traceEvent("session", "paused")
        flushTrace()
        hapticRenderer?.cancel()
        touchSceneCoordinator?.cancelDescription()
        manualDescriptionPending = false
        _binding?.describeSceneBtn?.isEnabled = true
        voiceTurnGate.reset()
        speechRecognizer?.cancel()
        speechOutput?.stop()
        super.onPause()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        // Activity 仍 RESUMED 时（如 BottomSheet 覆盖）不释放预览，避免 onStart 重拉流闪烁。
        val activityStillInteractive =
            runCatching { requireActivity().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                .getOrDefault(false)
        Timber.d(
            "onStop activityResumed=%s previewStarted=%s",
            activityStillInteractive,
            previewStarted,
        )
        if (!activityStillInteractive) {
            activePrepareNonce++
            setPreviewPlayerRestarting(false)
            stopPreview(releasePlayer = true)
        }
        super.onStop()
    }

    private fun initPreviewPlayer() {
        if (previewPlayerView != null || _binding == null) return
        previewPlayerView =
            InstaCapturePlayerView(requireContext()).also { playerView ->
                binding.previewPlaceholder.addView(
                    playerView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
            }
        previewPlayerView?.setLifecycle(this.lifecycle)
        applyPreviewAspect()
    }

    private fun startRawFramePipeline() {
        stopRawFramePipeline()
        firstRawFrameLogged.set(false)
        rawStreamWidth = 0
        rawStreamHeight = 0
        rawVideoEncode = null
        lastRawStatusUpdateMs = 0L
        LatestCameraFrameStore.clear()
        if (_binding != null) {
            binding.rawFrameStatus.setText(R.string.raw_frame_waiting)
            binding.rawFrameStatus.isVisible = true
        }
        rawFramePipeline =
            RawPreviewFramePipeline(
                analysisIntervalMs = 100L,
                onFrame = { frame ->
                    if (firstRawFrameLogged.compareAndSet(false, true)) {
                        traceEvent("decoder", "first_frame", code = "${frame.width}x${frame.height}")
                    }
                    LatestCameraFrameStore.publish(frame)
                    touchSceneCoordinator?.offer(frame)
                    val now = frame.receivedAtElapsedRealtimeMs
                    if (now - lastRawStatusUpdateMs >= 1_000L) {
                        lastRawStatusUpdateMs = now
                        val count = LatestCameraFrameStore.count()
                        _binding?.rawFrameStatus?.post {
                            if (_binding == null) return@post
                            binding.rawFrameStatus.text =
                                getString(
                                    R.string.raw_frame_ready_format,
                                    frame.width,
                                    frame.height,
                                    count,
                                )
                        }
                    }
                },
                onNeedsKeyFrame = {
                    _binding?.root?.post {
                        if (previewStarted) {
                            connectionViewModel.getCameraDevice()?.preview?.requestStreamIframe()
                        }
                    }
                },
                onError = { error ->
                    Timber.w(error, "raw preview frame pipeline")
                    traceEvent(
                        "decoder",
                        "failed",
                        code = (error as? NativeException)?.nativeErrorCode?.toString() ?: error.javaClass.simpleName,
                    )
                    _binding?.rawFrameStatus?.post {
                        if (_binding == null) return@post
                        binding.rawFrameStatus.text =
                            getString(
                                R.string.raw_frame_error_format,
                                error.message ?: error.javaClass.simpleName,
                            )
                    }
                },
            )
    }

    private fun configureRawFramePipelineIfReady() {
        val encode = rawVideoEncode ?: return
        if (rawStreamWidth <= 0 || rawStreamHeight <= 0) return
        runCatching {
            rawFramePipeline?.configure(rawStreamWidth, rawStreamHeight, encode)
        }.onFailure { Timber.w(it, "configure raw preview frame pipeline") }
    }

    private fun stopRawFramePipeline() {
        rawFramePipeline?.close()
        rawFramePipeline = null
        LatestCameraFrameStore.clear()
        hapticRenderer?.cancel()
    }

    private fun setupTouchScene() {
        val haptics = AndroidHapticRenderer(requireContext())
        hapticRenderer = haptics
        speechOutput = AndroidSpeechOutput(requireContext(), ::onSpeechOutputChanged)
        binding.tactileMapView.hapticRenderer = haptics
        binding.tactileMapView.onExplorationStarted = { version ->
            traceEvent("exploration", "touch_started", mapVersion = version)
        }
        binding.mapMoveUpBtn.setOnClickListener { binding.tactileMapView.moveAccessibilityCursor(0, -1) }
        binding.mapMoveDownBtn.setOnClickListener { binding.tactileMapView.moveAccessibilityCursor(0, 1) }
        binding.mapMoveLeftBtn.setOnClickListener { binding.tactileMapView.moveAccessibilityCursor(-1, 0) }
        binding.mapMoveRightBtn.setOnClickListener { binding.tactileMapView.moveAccessibilityCursor(1, 0) }
        binding.debugLayerBtn.setOnClickListener {
            val label = when (binding.tactileMapView.cycleDebugMode()) {
                TactileDebugMode.ORIGINAL -> R.string.touchscene_debug_original
                TactileDebugMode.GRAYSCALE -> R.string.touchscene_debug_grayscale
                TactileDebugMode.BINARY -> R.string.touchscene_debug_binary
                TactileDebugMode.EDGE -> R.string.touchscene_debug_edge
                TactileDebugMode.TOUCH_MAP -> R.string.touchscene_debug_touch_map
            }
            binding.debugLayerBtn.setText(label)
        }
        touchSceneCoordinator =
            TouchSceneCoordinator(
                describer = MlKitSceneDescriber(requireContext()),
                onAutomaticDescription = ::onAutomaticSceneDescription,
            ) { update ->
                val processedAtMs = SystemClock.elapsedRealtime()
                _binding?.root?.post {
                    if (_binding == null) return@post
                    if (update.sessionState == TactileSessionState.LIVE) {
                        update.debugSnapshot?.source?.let { source ->
                            performanceMetrics.record(
                                source.receivedAtElapsedRealtimeMs,
                                processedAtMs,
                                SystemClock.elapsedRealtime(),
                            )
                        }
                    }
                    renderTouchSceneUpdate(update)
                }
            }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).also {
            it.setRecognitionListener(buildRecognitionListener())
        }

        binding.freezeMapBtn.setOnClickListener {
            if (binding.tactileMapView.isFingerExploring) {
                speechOutput?.speak(getString(R.string.touchscene_lift_finger_before_map_change))
                return@setOnClickListener
            }
            val map = touchSceneCoordinator?.freeze()
            if (map == null) {
                traceEvent("map", "freeze_failed", code = "NO_FRAME")
                speechOutput?.speak(getString(R.string.touchscene_no_frame))
            } else {
                traceEvent("map", "frozen", mapVersion = map.version)
                binding.tactileMapView.submitMap(map)
                binding.tactileMapView.explorationEnabled = true
                binding.freezeMapBtn.isEnabled = false
                binding.refreshMapBtn.isEnabled = true
                binding.returnLiveBtn.isEnabled = true
                binding.tactileMapView.requestFocus()
                speechOutput?.speak("已冻结触觉地图，可以用手指扫描")
            }
        }
        binding.refreshMapBtn.setOnClickListener {
            if (binding.tactileMapView.isFingerExploring) {
                speechOutput?.speak(getString(R.string.touchscene_lift_finger_before_map_change))
                return@setOnClickListener
            }
            val map = touchSceneCoordinator?.refresh()
            if (map == null) {
                traceEvent("map", "refresh_unavailable", code = "NO_NEW_FRAME", mapVersion = touchSceneCoordinator?.activeMapVersion())
                speechOutput?.speak(getString(R.string.touchscene_no_new_frame))
            } else {
                traceEvent("map", "refreshed", mapVersion = map.version)
                binding.tactileMapView.submitMap(map)
                binding.tactileMapView.explorationEnabled = true
                speechOutput?.speak("触觉地图已刷新")
            }
        }
        binding.returnLiveBtn.setOnClickListener {
            if (binding.tactileMapView.isFingerExploring) {
                speechOutput?.speak(getString(R.string.touchscene_lift_finger_before_map_change))
                return@setOnClickListener
            }
            hapticRenderer?.cancel()
            val map = touchSceneCoordinator?.leaveExploration()
            traceEvent("map", "returned_live", mapVersion = map?.version)
            binding.tactileMapView.explorationEnabled = false
            speechOutput?.speak(getString(R.string.touchscene_returned_live))
        }
        binding.describeSceneBtn.setOnClickListener { describeLatestScene() }
        binding.hapticEnabled.setOnCheckedChangeListener { _, enabled ->
            haptics.setEnabled(enabled)
            binding.hapticSelfTestBtn.isEnabled = enabled
        }
        binding.hapticSelfTestBtn.setOnClickListener { haptics.selfTest() }
        binding.voiceCommandBtn.setOnClickListener { requestVoiceRecognition() }
        if (localDemoMode) {
            traceEvent("fallback", "local_demo_frame_requested", fallback = "local_demo")
            binding.rawFrameStatus.setText(R.string.touchscene_local_demo_active)
            binding.captureBtn.isEnabled = false
            binding.root.post { offerLocalDemoFrame() }
        }
    }

    private fun offerLocalDemoFrame() {
        val now = SystemClock.elapsedRealtime()
        touchSceneCoordinator?.offer(FakeCameraGateway().frame(FakeContourScene.IRREGULAR, now))
    }

    private fun renderTouchSceneUpdate(update: TouchSceneUpdate) {
        val map = update.map
        if (lastTracedSessionState != update.sessionState) {
            lastTracedSessionState = update.sessionState
            traceEvent("map", "state_${update.sessionState.name.lowercase()}", mapVersion = map?.version)
        }
        // In LIVE this is a visual debug preview only. EXPLORING/STALE always retains its frozen version.
        binding.tactileMapView.submitMap(map)
        binding.tactileMapView.submitDebugSnapshot(update.debugSnapshot)
        binding.tactileMapView.explorationEnabled = update.sessionState != TactileSessionState.LIVE
        val canExplore = update.sessionState != TactileSessionState.LIVE && map != null
        binding.freezeMapBtn.isEnabled = update.sessionState == TactileSessionState.LIVE
        binding.returnLiveBtn.isEnabled = update.sessionState != TactileSessionState.LIVE
        binding.mapMoveUpBtn.isEnabled = canExplore
        binding.mapMoveDownBtn.isEnabled = canExplore
        binding.mapMoveLeftBtn.isEnabled = canExplore
        binding.mapMoveRightBtn.isEnabled = canExplore
        binding.refreshMapBtn.isEnabled = update.sessionState != TactileSessionState.LIVE
        binding.touchsceneStatus.text =
            when (update.sessionState) {
                TactileSessionState.LIVE ->
                    getString(
                        R.string.touchscene_live_map,
                        when (update.stabilityState) {
                            StabilityState.MOVING -> "相机移动中"
                            StabilityState.STABILIZING -> "正在稳定"
                            StabilityState.STABLE -> "相机稳定"
                        },
                    )
                TactileSessionState.EXPLORING ->
                    getString(R.string.touchscene_exploring_map, map?.version ?: 0L)
                TactileSessionState.STALE -> getString(R.string.touchscene_stale_map)
            }
        if (update.sessionState == TactileSessionState.STALE) {
            binding.touchsceneStatus.announceForAccessibility(getString(R.string.touchscene_stale_map))
        }
        // A stability cue must not interrupt a finger exploring a frozen map.
        if (update.ready && update.sessionState == TactileSessionState.LIVE) {
            hapticRenderer?.playReady()
            speechOutput?.speak(getString(R.string.touchscene_ready))
        }
    }

    private fun describeLatestScene() {
        traceEvent("description", "requested", mapVersion = touchSceneCoordinator?.activeMapVersion())
        manualDescriptionPending = true
        binding.describeSceneBtn.isEnabled = false
        touchSceneCoordinator?.describeLatest { description ->
            _binding?.root?.post {
                if (_binding == null) return@post
                manualDescriptionPending = false
                binding.describeSceneBtn.isEnabled = true
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@post
                if (description == null) {
                    traceEvent("description", "failed", code = "NO_RESULT")
                    speechOutput?.speak(getString(R.string.touchscene_no_frame))
                } else {
                    traceEvent("description", "completed", fallback = description.source)
                    binding.touchsceneStatus.text = description.text
                    speechOutput?.speak(description.text)
                    automaticScenePolicy.markAnnounced(description, SystemClock.elapsedRealtime())
                }
            }
        }
    }

    private fun onAutomaticSceneDescription(description: SceneDescription) {
        _binding?.root?.post {
            if (_binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@post
            if (touchSceneCoordinator?.isCurrentAutomaticDescription(description) != true) return@post
            if (lastTracedSessionState != TactileSessionState.LIVE || manualDescriptionPending ||
                voiceTurnGate.isListening() || speechOutput?.canSpeak() != true ||
                speechOutput?.isSpeaking() == true) return@post
            val nowMs = SystemClock.elapsedRealtime()
            if (!automaticScenePolicy.shouldAnnounce(description, nowMs)) return@post
            speechOutput?.speak(description.text)
            traceEvent("description", "auto_announced", fallback = description.source)
        }
    }

    private fun requestVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onSpeechOutputChanged(speaking: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            _binding?.root?.post { onSpeechOutputChanged(speaking) }
            return
        }
        if (_binding == null) return
        if (speaking) {
            if (voiceTurnGate.speechStarted()) speechRecognizer?.cancel()
        } else if (voiceTurnGate.speechFinished()) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                startVoiceRecognition(resuming = true)
            } else {
                voiceTurnGate.reset()
            }
        }
    }

    private fun startVoiceRecognition(resuming: Boolean = false) {
        if (!resuming) {
            voiceTurnGate.beginListening()
            speechOutput?.stop()
        }
        binding.touchsceneStatus.setText(R.string.touchscene_listening)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { checkNotNull(speechRecognizer).startListening(intent) }
            .onFailure {
                voiceTurnGate.reset()
                speechOutput?.speak(getString(R.string.touchscene_speech_failed))
            }
    }

    private fun buildRecognitionListener(): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                if (voiceTurnGate.recognitionFinished() && _binding != null) {
                    speechOutput?.speak(getString(R.string.touchscene_speech_failed))
                }
            }
            override fun onResults(results: Bundle?) {
                if (!voiceTurnGate.recognitionFinished() || _binding == null) return
                val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                dispatchVoiceCommand(VoiceCommandParser.parse(candidates.firstOrNull().orEmpty()))
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun dispatchVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.TakePhoto -> switchModeAndCapture(FunctionMode.PHOTO_NORMAL, stop = false)
            VoiceCommand.StartRecording -> switchModeAndCapture(FunctionMode.VIDEO_NORMAL, stop = false)
            VoiceCommand.StopRecording -> switchModeAndCapture(FunctionMode.VIDEO_NORMAL, stop = true)
            VoiceCommand.DescribeScene -> describeLatestScene()
            VoiceCommand.Repeat -> if (speechOutput?.repeatLast() != true) speechOutput?.speak(getString(R.string.touchscene_no_frame))
            VoiceCommand.Help -> speechOutput?.speak(getString(R.string.touchscene_help))
            is VoiceCommand.Unknown -> speechOutput?.speak(getString(R.string.touchscene_unknown_command))
        }
    }

    private fun switchModeAndCapture(mode: FunctionMode, stop: Boolean) {
        if (localDemoMode || connectionViewModel.getCameraDevice() == null) {
            traceEvent("capture", "rejected", code = "NO_CAMERA", fallback = if (localDemoMode) "local_demo" else null)
            val message = getString(R.string.touchscene_capture_requires_camera)
            binding.touchsceneStatus.text = message
            speechOutput?.speak(message)
            return
        }
        traceEvent("capture", "requested", code = if (stop) "voice_stop" else "voice_start")
        viewLifecycleOwner.lifecycleScope.launch {
            val current = captureViewModel.ui.value
            if (current.busy || current.captureCommand != null || previewPlayerRestarting) {
                speechOutput?.speak(getString(R.string.touchscene_capture_unavailable))
                return@launch
            }
            if (stop && !current.captureFlowActive) {
                speechOutput?.speak("当前没有正在进行的录像")
                return@launch
            }
            if (stop) {
                if (current.selectedMode != mode || !current.captureButtonEnabled) {
                    speechOutput?.speak(getString(R.string.touchscene_capture_unavailable))
                    return@launch
                }
                captureViewModel.onPrimaryCaptureButtonClicked()
                return@launch
            }
            if (current.captureFlowActive) {
                speechOutput?.speak(getString(R.string.touchscene_capture_unavailable))
                return@launch
            }
            val result = dispatchVoiceCaptureAfterModeChange(
                switchModeIfNeeded = {
                    current.selectedMode == mode || executePreviewSwitch(
                        PreviewSwitchAction.Mode(mode) { captureViewModel.switchModeFromPreview(mode) },
                    )
                },
                isReady = {
                    val ready = captureViewModel.ui.value
                    connectionViewModel.getCameraDevice() != null &&
                        ready.selectedMode == mode && !ready.busy && ready.captureCommand == null &&
                        !ready.captureFlowActive && ready.captureButtonEnabled
                },
                capture = { captureViewModel.onPrimaryCaptureButtonClicked() },
            )
            if (result == VoiceCaptureDispatchResult.NOT_READY) {
                speechOutput?.speak(getString(R.string.touchscene_capture_unavailable))
            }
        }
    }

    private fun startPreviewIfNeeded() {
        if (_binding == null || previewStarted) return
        val device = connectionViewModel.getCameraDevice()
        if (device == null) {
            Timber.d("startPreviewIfNeeded skip: no device")
            traceEvent("preview", "not_started", code = "NO_CAMERA", fallback = if (localDemoMode) "local_demo" else null)
            return
        }
        Timber.d("startPreviewIfNeeded -> init + startStream")
        traceEvent("preview", "start_requested")
        binding.previewPlaceholderText.setText(R.string.realtime_preview_window)
        binding.previewPlaceholderText.isVisible = true
        initPreviewPlayer()
        startRawFramePipeline()
        runCatching {
            device.preview.init(requireActivity().application)
            device.preview.registerCameraStreamListener(streamListener)
            device.preview.registerPostureListener(touchScenePostureListener)
            previewStarted = true
            device.preview.startStream()
        }.onFailure {
            traceEvent("preview", "start_failed", code = (it as? NativeException)?.nativeErrorCode?.toString() ?: it.javaClass.simpleName)
            previewStarted = false
            stopCameraStreamBestEffort(device)
            stopRawFramePipeline()
            releasePreviewPlayer()
            Timber.w(it, "start preview stream")
        }
    }

    /** An SDK cleanup failure must not skip the remaining release steps. */
    private fun stopCameraStreamBestEffort(device: CameraDevice) {
        runPreviewCleanup(
            { step, error -> Timber.w(error, "preview cleanup failed: %s", step) },
            "stream listener" to { device.preview.unregisterCameraStreamListener(streamListener) },
            "posture listener" to { device.preview.unregisterPostureListener(touchScenePostureListener) },
            "pipeline" to { device.preview.setPipeline(null) },
            "stream" to { device.preview.stopStream() },
        )
    }

    private fun stopPreview(releasePlayer: Boolean) {
        Timber.d("stopPreview releasePlayer=%s previewStarted=%s", releasePlayer, previewStarted)
        if (previewStarted) traceEvent("preview", "stop_requested")
        val device = connectionViewModel.getCameraDevice()
        if (device != null && previewStarted) {
            stopCameraStreamBestEffort(device)
        } else {
            runCatching { device?.preview?.setPipeline(null) }
                .onFailure { Timber.w(it, "unbind inactive preview pipeline") }
        }
        stopRawFramePipeline()
        previewStarted = false
        traceEvent("preview", "stopped")
        if (_binding != null) {
            binding.previewPlaceholderText.isVisible = true
        }
        if (releasePlayer) {
            releasePreviewPlayer()
        }
    }

    private fun releasePreviewPlayer() {
        val playerView = previewPlayerView ?: return
        Timber.d("releasePreviewPlayer")
        activePrepareNonce++
        playerView.setListener(null)
        playerView.destroy()
        if (_binding != null) {
            binding.previewPlaceholder.removeView(playerView)
        }
        previewPlayerView = null
    }

    private fun restartPreviewPlayer() {
        val playerView = previewPlayerView ?: return
        if (_binding == null || !previewStarted) return
        Timber.d("restartPreviewPlayer -> prepare")
        setPreviewPlayerRestarting(true)
        playerView.destroyRender()
        playerView.post { prepareAndPlayPreview(playerView) }
    }

    private fun onLensChipSelected(mode: SensorMode) {
        Timber.d("onLensChipSelected lens=%s", mode)
        viewLifecycleOwner.lifecycleScope.launch {
            if (_binding == null) return@launch
            executePreviewSwitch(
                PreviewSwitchAction.Lens(mode) {
                    captureViewModel.switchLensFromPreview(mode)
                },
            )
        }
    }

    private fun onModeChipSelected(mode: FunctionMode) {
        Timber.d("onModeChipSelected mode=%s", mode)
        viewLifecycleOwner.lifecycleScope.launch {
            if (_binding == null) return@launch
            executePreviewSwitch(
                PreviewSwitchAction.Mode(mode) {
                    captureViewModel.switchModeFromPreview(mode)
                },
            )
        }
    }

    /**
     * 切镜头/切模式统一入口：由 [PreviewRefreshStrategyDecider] 根据 action + 当前模式 + 设备型号选定策略。
     */
    private suspend fun executePreviewSwitch(action: PreviewSwitchAction): Boolean {
        val device = connectionViewModel.getCameraDevice() ?: return false
        // 不在预览中也得让 SDK 切换发生，保持 ViewModel 状态一致
        if (_binding == null || !previewStarted) {
            return runPreviewSdkSwitch(action.sdkSwitch, ::reportPreviewSwitchFailure)
        }
        val supportNewCaptureControlFlow: Boolean =
            connectionViewModel
                .getCameraDevice()
                ?.system
                ?.getSupportConfig()
                ?.getOrNull()
                ?.supportNewCaptureControlFlow() == true
        val strategy =
            PreviewRefreshStrategyDecider.decide(
                action = action,
                supportNewCaptureControlFlow = supportNewCaptureControlFlow,
            )
        Timber.d("executePreviewSwitch action=%s strategy=%s", action::class.simpleName, strategy)
        return when (strategy) {
            PreviewRefreshStrategy.RESTART_STREAM -> {
                doRestartStream(device, action.sdkSwitch)
            }

            PreviewRefreshStrategy.REBUILD_PLAYER -> {
                doRebuildPlayer(device, action.sdkSwitch)
            }

            PreviewRefreshStrategy.NO_OP -> {
                runPreviewSdkSwitch(action.sdkSwitch, ::reportPreviewSwitchFailure)
            }
        }
    }

    private fun reportPreviewSwitchFailure(error: Throwable?) {
        Timber.w(error, "preview lens/mode switch failed")
        traceEvent(
            "preview",
            "switch_failed",
            code = (error as? NativeException)?.nativeErrorCode?.toString()
                ?: error?.javaClass?.simpleName ?: "REJECTED",
        )
        if (_binding != null) {
            val message = getString(R.string.touchscene_preview_switch_failed)
            binding.rawFrameStatus.text = message
            speechOutput?.speak(message)
        }
    }

    /** 解绑 pipeline → 切 SDK → 重新 prepare，不停 stream。 */
    private suspend fun doRebuildPlayer(
        device: CameraDevice,
        switchSdk: suspend () -> Boolean,
    ): Boolean {
        val playerView = previewPlayerView ?: return false
        if (_binding == null || !previewStarted) return false

        activePrepareNonce++
        setPreviewPlayerRestarting(true)
        Timber.d("doRebuildPlayer: unbind pipeline nonce=%d", activePrepareNonce)
        runCatching { device.preview.setPipeline(null) }.onFailure {
            Timber.w(it, "preview setPipeline null before lens/mode switch")
        }

        val switched = runPreviewSdkSwitch(switchSdk, ::reportPreviewSwitchFailure)
        Timber.d("doRebuildPlayer: sdkSwitch result=%s", switched)
        if (!isAdded || _binding == null || !previewStarted) {
            Timber.d("doRebuildPlayer: abort after sdkSwitch")
            setPreviewPlayerRestarting(false)
            return false
        }
        playerView.destroyRender()
        playerView.post { prepareAndPlayPreview(playerView) }
        return switched
    }

    /**
     * 完整重启 SDK 预览流，让底层重协商分辨率/帧率/解码器。
     * 顺序：失效旧 nonce → 解绑 pipeline → unregister → stopStream → 切 SDK → register → startStream，
     * 后续 prepare 由 [streamListener]onOpened 自动触发。
     */
    private suspend fun doRestartStream(
        device: CameraDevice,
        switchSdk: suspend () -> Boolean,
    ): Boolean {
        if (_binding == null || !previewStarted) return false

        activePrepareNonce++
        setPreviewPlayerRestarting(true)
        binding.previewPlaceholderText.setText(R.string.realtime_preview_window)
        binding.previewPlaceholderText.isVisible = true

        stopCameraStreamBestEffort(device)
        stopRawFramePipeline()
        previewStarted = false
        Timber.d("doRestartStream: stream stopped")

        val switched = runPreviewSdkSwitch(switchSdk, ::reportPreviewSwitchFailure)
        Timber.d("doRestartStream: sdkSwitch result=%s", switched)

        if (!isAdded || _binding == null) {
            setPreviewPlayerRestarting(false)
            Timber.d("doRestartStream: abort after sdkSwitch")
            return false
        }
        startRawFramePipeline()
        var streamStarted = false
        runCatching {
            device.preview.init(requireActivity().application)
            device.preview.registerCameraStreamListener(streamListener)
            device.preview.registerPostureListener(touchScenePostureListener)
            previewStarted = true
            device.preview.startStream()
            streamStarted = true
            Timber.d("doRestartStream: stream restarted")
        }.onFailure {
            previewStarted = false
            stopCameraStreamBestEffort(device)
            stopRawFramePipeline()
            setPreviewPlayerRestarting(false)
            Timber.w(it, "restart preview stream after lens switch")
        }
        return switched && streamStarted
    }

    private fun prepareAndPlayPreview(playerView: InstaCapturePlayerView) {
        if (!isAdded || _binding == null) {
            setPreviewPlayerRestarting(false)
            Timber.d("prepareAndPlayPreview abort: not added or no binding")
            return
        }
        activePrepareNonce++
        val expect = activePrepareNonce
        restartStableExpect = expect
        restartStablePipelineBound = false
        restartStableFirstFrame = false
        Timber.d(
            "prepareAndPlayPreview wxh=%sx%s fps=%s lens=%s",
            previewWidth,
            previewHeight,
            previewFps,
            captureViewModel.ui.value.selectedLens,
        )
        playerView.setListener(buildPlayerViewListener(expect))
        selectedLensMode = captureViewModel.ui.value.selectedLens
        playerView.prepare(
            PreviewParams(
                width = previewWidth,
                height = previewHeight,
                fps = previewFps,
                isGestureEnabled = isPanoramaDualLensPreview(),
                // renderModel 只能通过 prepare 传入，重启后必须重新下发缓存值
                renderModel = if (isPanoramaDualLensPreview()) selectedPreviewRenderModel else null,
            ),
        )
        playerView.play()
    }

    private fun applyPreviewAspect() {
        val playerView = previewPlayerView ?: return
        if (_binding == null) return
        val container = binding.previewPlaceholder
        val containerWidth = container.width
        if (containerWidth <= 0) return

        val videoWidth = previewWidth.coerceAtLeast(1)
        val videoHeight = previewHeight.coerceAtLeast(1)
        val targetHeight =
            (containerWidth / (videoWidth.toFloat() / videoHeight.toFloat()))
                .toInt()
                .coerceAtLeast(1)
        val layoutParams =
            (playerView.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(containerWidth, targetHeight, Gravity.CENTER)
        if (layoutParams.width != containerWidth || layoutParams.height != targetHeight || layoutParams.gravity != Gravity.CENTER) {
            layoutParams.width = containerWidth
            layoutParams.height = targetHeight
            layoutParams.gravity = Gravity.CENTER
            playerView.layoutParams = layoutParams
        }
        if (binding.previewCaptureMask.isVisible) {
            sizePreviewCaptureMaskToPlayer()
        }
    }

    private fun rebuildLensChips(
        options: List<SensorMode>,
        selected: SensorMode?,
    ) {
        CaptureChips.render(
            container = binding.lensChipContainer,
            options = options,
            selected = selected,
            textSizeSp = 14f,
            label = { CameraDemoDisplayLabels.sensorMode(requireContext(), it) },
            onClick = ::onLensChipSelected,
        )
    }

    private fun rebuildModeChips(
        options: List<FunctionMode>,
        selected: FunctionMode?,
    ) {
        lastModeOptions =
            CaptureChips.renderStableModeStrip(
                scroll = binding.modeScroll,
                container = binding.modeChipContainer,
                previousOptions = lastModeOptions,
                options = options,
                selected = selected,
                label = { CameraDemoDisplayLabels.functionMode(requireContext(), it) },
                onClick = ::onModeChipSelected,
            )
    }

    /** 全局 busy 或播放器重启时禁用全部次要入口；拍摄流程中也禁止切镜头/模式。 */
    private fun applySecondaryInteractionLock(locked: Boolean) {
        val alpha = if (locked) 0.45f else 1f
        listOf(
            binding.backLink,
            binding.openModeParamsSheet,
            binding.openPreviewParamsSheet,
        ).forEach {
            it.isEnabled = !locked
            it.alpha = alpha
        }
        CaptureChips.setInteractionLocked(binding.lensChipContainer, locked)
        CaptureChips.setInteractionLocked(binding.modeChipContainer, locked)
    }

    private fun setPreviewPlayerRestarting(restarting: Boolean) {
        if (previewPlayerRestarting == restarting) return
        previewPlayerRestarting = restarting
        if (_binding == null) return
        currentUiState?.let(::applyOperationLockState)
    }

    /** 拍摄中且当前模式不支持实时预览时，用不透明遮罩盖住预览画面。 */
    private fun applyPreviewCaptureMask(s: CameraCaptureUiState) {
        val maskWhileCapturing =
            s.captureFlowActive && !PreviewCapability.supportsPreviewWhileCapturing(s.selectedMode)
        if (maskWhileCapturing) {
            // wrap_content 容器内 match_parent 子 View 不会被撑满，显式对齐到 player 尺寸
            sizePreviewCaptureMaskToPlayer()
            if (!binding.previewCaptureMask.isVisible) {
                // player 由 addView 动态追加，z 序在 XML 子节点之上，需提前置顶才能盖住
                binding.previewCaptureMask.bringToFront()
            }
        }
        binding.previewCaptureMask.isVisible = maskWhileCapturing
    }

    /** 遮罩尺寸跟随 player（与 [applyPreviewAspect] 计算出的预览区一致）。 */
    private fun sizePreviewCaptureMaskToPlayer() {
        if (_binding == null) return
        val mask = binding.previewCaptureMask
        val player = previewPlayerView
        val w = player?.width?.takeIf { it > 0 } ?: binding.previewPlaceholder.width
        val h = player?.height?.takeIf { it > 0 } ?: binding.previewPlaceholder.height
        if (w <= 0 || h <= 0) return
        val lp =
            (mask.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        if (lp.width != w || lp.height != h || lp.gravity != Gravity.CENTER) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            mask.layoutParams = lp
        }
    }

    private fun applyOperationLockState(s: CameraCaptureUiState) {
        // 拍摄命令在途（开始/停止过渡期）同样触发全屏遮罩与交互锁
        val operationLocked = s.busy || previewPlayerRestarting || s.captureCommand != null
        binding.blockingOverlay.isVisible = operationLocked
        binding.blockingOverlay.setText(s.loadingText?:"")
        binding.captureBtn.isEnabled = s.captureButtonEnabled && !operationLocked
        applySecondaryInteractionLock(operationLocked || s.captureFlowActive)
    }

    fun previewParamRowsForSheet(): List<CaptureParamRow> {
        val appContext = requireContext()
        val resolutionOptions = PreviewResolutionPreset.entries
        val resolutionSelected =
            resolutionOptions.indexOf(selectedPreviewResolutionPreset).coerceAtLeast(0)
        val stabSelected =
            STAB_TYPE_OPTIONS.indexOfFirst { it.value == selectedStabType }.coerceAtLeast(0)
        return buildList<CaptureParamRow> {
            add(
                previewParamRow(
                    key = PREVIEW_PARAM_KEY_RESOLUTION,
                    labelResId = R.string.preview_param_resolution,
                    optionLabels = resolutionOptions.map { appContext.getString(it.labelResId) },
                    optionValues = resolutionOptions,
                    selectedIndex = resolutionSelected,
                ),
            )
            if (isPanoramaDualLensPreview()) {
                val projectionSelected =
                    PROJECTION_OPTIONS
                        .indexOfFirst { it.value == selectedPreviewRenderModel }
                        .coerceAtLeast(0)
                add(
                    previewParamRow(
                        key = PREVIEW_PARAM_KEY_PROJECTION,
                        labelResId = R.string.preview_param_projection_mode,
                        optionLabels = PROJECTION_OPTIONS.map { it.text },
                        optionValues = PROJECTION_OPTIONS.map { it.value },
                        selectedIndex = projectionSelected,
                    ),
                )
            }
            add(
                previewParamRow(
                    key = PREVIEW_PARAM_KEY_STAB,
                    labelResId = R.string.preview_param_stab_type,
                    optionLabels = STAB_TYPE_OPTIONS.map { appContext.getString(it.textId) },
                    optionValues = STAB_TYPE_OPTIONS.map { it.value },
                    selectedIndex = stabSelected,
                ),
            )
            if (isPanoramaDualLensPreview() && isOffsetTypeSupported()) {
                val offsetSelected =
                    OFFSET_TYPE_OPTIONS
                        .indexOfFirst { it.value == selectedOffsetType }
                        .coerceAtLeast(0)
                add(
                    previewParamRow(
                        key = PREVIEW_PARAM_KEY_OFFSET,
                        labelResId = R.string.preview_param_offset_type,
                        optionLabels = OFFSET_TYPE_OPTIONS.map { appContext.getString(it.textId) },
                        optionValues = OFFSET_TYPE_OPTIONS.map { it.value },
                        selectedIndex = offsetSelected,
                    ),
                )
            }
            if (isPanoramaDualLensPreview()) {
                add(
                    previewParamRow(
                        key = PREVIEW_PARAM_KEY_DYNAMIC_STITCH,
                        labelResId = R.string.player_setting_dynamic_stitch,
                        optionLabels = BOOLEAN_OPTIONS.map { appContext.getString(it.textId) },
                        optionValues = BOOLEAN_OPTIONS.map { it.value },
                        selectedIndex = if (selectedDynamicStitch) 1 else 0,
                    ),
                )
            }
            add(
                previewParamRow(
                    key = PREVIEW_PARAM_KEY_DE_PURPLE_FILTER,
                    labelResId = R.string.player_setting_de_purple_filter,
                    optionLabels = BOOLEAN_OPTIONS.map { appContext.getString(it.textId) },
                    optionValues = BOOLEAN_OPTIONS.map { it.value },
                    selectedIndex = if (selectedDePurpleFilter) 1 else 0,
                ),
            )
            add(
                previewParamRow(
                    key = PREVIEW_PARAM_KEY_COLOR_PLUS,
                    labelResId = R.string.player_setting_color_plus,
                    optionLabels = BOOLEAN_OPTIONS.map { appContext.getString(it.textId) },
                    optionValues = BOOLEAN_OPTIONS.map { it.value },
                    selectedIndex = if (selectedColorPlus) 1 else 0,
                ),
            )
            if (selectedColorPlus) {
                add(
                    previewParamRow(
                        key = PREVIEW_PARAM_KEY_COLOR_PLUS_INTENSITY,
                        labelResId = R.string.player_setting_color_plus_intensity,
                        optionLabels = COLOR_PLUS_INTENSITY_OPTIONS.map { it.text },
                        optionValues = COLOR_PLUS_INTENSITY_OPTIONS.map { it.value },
                        selectedIndex =
                            selectedColorPlusIntensityIndex.coerceIn(
                                0,
                                COLOR_PLUS_INTENSITY_OPTIONS.size - 1,
                            ),
                    ),
                )
            }
            if (isPanoramaDualLensPreview()) {
                add(
                    previewParamRow(
                        key = PREVIEW_PARAM_KEY_IMAGE_FUSION,
                        labelResId = R.string.player_setting_color_fusion,
                        optionLabels = BOOLEAN_OPTIONS.map { appContext.getString(it.textId) },
                        optionValues = BOOLEAN_OPTIONS.map { it.value },
                        selectedIndex = if (selectedImageFusion) 1 else 0,
                    ),
                )
            }
        }
    }

    private fun previewParamRow(
        key: String,
        labelResId: Int,
        optionLabels: List<String>,
        optionValues: List<Any>,
        selectedIndex: Int,
    ): CaptureParamRow =
        CaptureParamRow(
            key = key,
            displayLabel = getString(labelResId),
            optionLabels = optionLabels,
            optionValues = optionValues,
            selectedIndex = selectedIndex,
        )

    fun onPreviewParamSelectionChanged(
        rowKey: String,
        index: Int,
    ) {
        when (rowKey) {
            PREVIEW_PARAM_KEY_RESOLUTION -> {
                val options = PreviewResolutionPreset.entries
                if (index !in options.indices) return
                selectedPreviewResolutionPreset = options[index]
                Timber.d("resolution preset -> %s previewStarted=%s", selectedPreviewResolutionPreset, previewStarted)
                applyPreviewResolution(previewPlayerView ?: return)
                refreshPreviewParamsBottomSheet()
                return
            }

            PREVIEW_PARAM_KEY_PROJECTION -> {
                if (!isPanoramaDualLensPreview()) return
                if (index !in PROJECTION_OPTIONS.indices) return
                val newRenderModel = PROJECTION_OPTIONS[index].value
                if (newRenderModel == selectedPreviewRenderModel) return
                selectedPreviewRenderModel = newRenderModel as RenderModel
                Timber.d("projection changed -> %s", newRenderModel)
                restartPreviewPlayer()
                return
            }

            PREVIEW_PARAM_KEY_STAB -> {
                val options = STAB_TYPE_OPTIONS
                if (index !in options.indices) return
                selectedStabType = options[index].value as StabType
                Timber.d("stabType -> %s", selectedStabType)
                previewPlayerView?.setStabType(selectedStabType)
                refreshPreviewParamsBottomSheet()
                return
            }

            PREVIEW_PARAM_KEY_OFFSET -> {
                // 与 BottomSheet 行显示条件及原写回逻辑保持一致：NANO_S 等不支持 offset 的机型不下发
                if (!isPanoramaDualLensPreview() || !isOffsetTypeSupported()) return
                val options = OFFSET_TYPE_OPTIONS
                if (index !in options.indices) return
                selectedOffsetType = options[index].value as OffsetType
                Timber.d("offsetType -> %s", selectedOffsetType)
                previewPlayerView?.setOffsetType(selectedOffsetType)
                refreshPreviewParamsBottomSheet()
                return
            }

            PREVIEW_PARAM_KEY_DYNAMIC_STITCH -> {
                if (!isPanoramaDualLensPreview()) return
                val value = BOOLEAN_OPTIONS[index.coerceIn(0, 1)].value as Boolean
                if (value == selectedDynamicStitch) return
                selectedDynamicStitch = value
                Timber.d("dynamicStitch -> %s", value)
                previewPlayerView?.setDynamicStitchEnabled(value)
                return
            }

            PREVIEW_PARAM_KEY_DE_PURPLE_FILTER -> {
                val value = BOOLEAN_OPTIONS[index.coerceIn(0, 1)].value as Boolean
                if (value == selectedDePurpleFilter) return
                selectedDePurpleFilter = value
                Timber.d("dePurpleFilter -> %s", value)
                previewPlayerView?.setDePurpleFilterEnable(value)
                return
            }

            PREVIEW_PARAM_KEY_COLOR_PLUS -> {
                val value = BOOLEAN_OPTIONS[index.coerceIn(0, 1)].value as Boolean
                if (value == selectedColorPlus) return
                selectedColorPlus = value
                Timber.d("colorPlus -> %s", value)
                previewPlayerView?.setColorPlusEnabled(value)
                if (value) {
                    val intensity =
                        COLOR_PLUS_INTENSITY_OPTIONS[
                            selectedColorPlusIntensityIndex.coerceIn(
                                0,
                                COLOR_PLUS_INTENSITY_OPTIONS.size - 1,
                            ),
                        ].value as Float
                    previewPlayerView?.setColorPlusFilterIntensity(intensity)
                }
                refreshPreviewParamsBottomSheet()
                return
            }

            PREVIEW_PARAM_KEY_COLOR_PLUS_INTENSITY -> {
                if (index !in COLOR_PLUS_INTENSITY_OPTIONS.indices) return
                selectedColorPlusIntensityIndex = index
                if (selectedColorPlus) {
                    val intensity = COLOR_PLUS_INTENSITY_OPTIONS[index].value as Float
                    Timber.d("colorPlusIntensity -> %s", intensity)
                    previewPlayerView?.setColorPlusFilterIntensity(intensity)
                }
                return
            }

            PREVIEW_PARAM_KEY_IMAGE_FUSION -> {
                if (!isPanoramaDualLensPreview()) return
                val value = BOOLEAN_OPTIONS[index.coerceIn(0, 1)].value as Boolean
                if (value == selectedImageFusion) return
                selectedImageFusion = value
                Timber.d("imageFusion -> %s", value)
                previewPlayerView?.setColorFusionEnabled(value)
                return
            }

            else -> {
                return
            }
        }
    }

    private fun refreshPreviewParamsBottomSheet() {
        (childFragmentManager.findFragmentByTag(PreviewParamsBottomSheetFragment.TAG) as? PreviewParamsBottomSheetFragment)
            ?.refreshRows()
    }

    private fun syncRenderParamsFromPlayer(playerView: InstaCapturePlayerView) {
        runCatching {
            selectedDynamicStitch = playerView.isDynamicStitchEnabled()
            selectedDePurpleFilter = playerView.getDePurpleFilterEnable()
            selectedColorPlus = playerView.isColorPlusEnabled()
            val currentIntensity = playerView.getColorPlusFilterIntensity()
            selectedColorPlusIntensityIndex =
                COLOR_PLUS_INTENSITY_OPTIONS
                    .indexOfFirst { (it.value as Float) == currentIntensity }
                    .coerceAtLeast(0)
            selectedImageFusion = playerView.isColorFusionEnabled()
            // PlayerView 无对应 getter，重置为 SDK 默认值与 PlayerView 实际状态保持一致
            selectedStabType = StabType.AUTO
            selectedOffsetType = OffsetType.ORIGINAL
            selectedPreviewResolutionPreset = PreviewResolutionPreset.ORIGINAL
            Timber.d(
                "syncRenderParams: dynStitch=%s dePurple=%s colorPlus=%s intensity=%s fusion=%s stab=%s offset=%s",
                selectedDynamicStitch,
                selectedDePurpleFilter,
                selectedColorPlus,
                selectedColorPlusIntensityIndex,
                selectedImageFusion,
                selectedStabType,
                selectedOffsetType,
            )
        }.onFailure { Timber.w(it, "syncRenderParamsFromPlayer failed") }
    }

    private fun applyPreviewResolution(playerView: InstaCapturePlayerView) {
        val w = selectedPreviewResolutionPreset.width ?: streamDefaultPreviewWidth
        val h = selectedPreviewResolutionPreset.height ?: streamDefaultPreviewHeight
        if (w <= 0 || h <= 0) return
        Timber.d("applyPreviewResolution preset=%s wxh=%sx%s", selectedPreviewResolutionPreset, w, h)
        playerView.setPreviewResolution(w, h)
        if (previewWidth != w || previewHeight != h) {
            previewWidth = w
            previewHeight = h
            applyPreviewAspect()
        }
    }

    private fun applyPreviewGestureAvailability(playerView: InstaCapturePlayerView?) {
        playerView?.let {
            val enabled = isPanoramaDualLensPreview()
            it.setGestureEnabled(enabled)
            it.setGestureHorizontalEnabled(enabled)
            it.setGestureVerticalEnabled(enabled)
            it.setGestureZoomEnabled(enabled)
        }
        binding.scrollView.gestureInterceptTarget =
            if (isPanoramaDualLensPreview()) binding.previewPlaceholder else null
    }

    private fun isPanoramaDualLensPreview(): Boolean = selectedLensMode == SensorMode.ALL

    private fun isOffsetTypeSupported(): Boolean {
        val cameraType = connectionViewModel.getCameraDevice()
            ?.system?.getCameraType()?.getOrNull() ?: return false
        return cameraType != CameraType.NANO_S
    }

    /**
     * TODO: 临时兜底，等相机固件修复后移除。
     * X5 延时录像/移动延时模式下改视频分辨率，相机侧会自行重启预览流但不回调任何事件，
     * 目前仅针对该机型 + 这两种模式做兜底重启预览流；其他机型/模式暂未复现，先不处理。
     */
    private fun needsResolutionChangeStreamRestartQuirk(): Boolean {
        val cameraType = connectionViewModel.getCameraDevice()
            ?.system?.getCameraType()?.getOrNull() ?: return false
        val mode = captureViewModel.ui.value.selectedMode
        return cameraType == CameraType.X5 &&
            (mode == FunctionMode.VIDEO_TIMELAPSE || mode == FunctionMode.VIDEO_TIMESHIFT)
    }

    override fun onDestroyView() {
        Timber.d("onDestroyView")
        voiceTurnGate.reset()
        activePrepareNonce++
        stopPreview(releasePlayer = true)
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechOutput?.close()
        speechOutput = null
        touchSceneCoordinator?.close()
        touchSceneCoordinator = null
        hapticRenderer?.cancel()
        hapticRenderer = null
        binding.previewPlaceholder.removeOnLayoutChangeListener(previewLayoutChangeListener)
        binding.previewPlaceholder.removeAllViews()
        traceEvent("session", "view_destroyed")
        flushTrace()
        traceIo?.shutdown()
        traceIo = null
        super.onDestroyView()
        _binding = null
    }

    private enum class PreviewResolutionPreset(
        val labelResId: Int,
        val width: Int?,
        val height: Int?,
    ) {
        ORIGINAL(R.string.preview_resolution_original, null, null),
        RESOLUTION_1080P(R.string.preview_resolution_1080p, 1920, 1080),
        RESOLUTION_720P(R.string.preview_resolution_720p, 1280, 720),
    }

    companion object {
        private const val PREVIEW_PARAM_KEY_RESOLUTION = "preview_resolution"
        private const val PREVIEW_PARAM_KEY_PROJECTION = "preview_projection_mode"
        private const val PREVIEW_PARAM_KEY_STAB = "preview_stab_type"
        private const val PREVIEW_PARAM_KEY_OFFSET = "preview_offset_type"
        private const val PREVIEW_PARAM_KEY_DYNAMIC_STITCH = "preview_dynamic_stitch"
        private const val PREVIEW_PARAM_KEY_DE_PURPLE_FILTER = "preview_de_purple_filter"
        private const val PREVIEW_PARAM_KEY_COLOR_PLUS = "preview_color_plus"
        private const val PREVIEW_PARAM_KEY_COLOR_PLUS_INTENSITY = "preview_color_plus_intensity"
        private const val PREVIEW_PARAM_KEY_IMAGE_FUSION = "preview_image_fusion"
    }
}
