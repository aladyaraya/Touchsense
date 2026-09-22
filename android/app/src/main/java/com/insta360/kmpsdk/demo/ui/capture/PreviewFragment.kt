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
import android.view.WindowManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.arashivision.sdk.common.exception.InstaException
import com.arashivision.sdk.common.exception.NativeException
import com.arashivision.sdk.media.api.listener.PlayerViewListener
import com.arashivision.sdk.media.api.params.PreviewParams
import com.arashivision.sdk.media.core.model.OffsetData
import com.arashivision.sdk.media.player.preview.InstaCapturePlayerView
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentPreviewBinding
import com.insta360.kmpsdk.demo.raw.LatestCameraFrameStore
import com.insta360.kmpsdk.demo.raw.RawPreviewFramePipeline
import com.insta360.kmpsdk.demo.touchscene.AndroidHapticRenderer
import com.insta360.kmpsdk.demo.touchscene.TactileCell
import com.insta360.kmpsdk.demo.touchscene.TactileMap
import com.insta360.kmpsdk.demo.touchscene.runPreviewCleanup
import com.insta360.kmpsdk.demo.touchscene.runPreviewSdkSwitch
import com.insta360.kmpsdk.demo.touchscene.AndroidSpeechOutput
import com.insta360.kmpsdk.demo.touchscene.MlKitSceneDescriber
import com.insta360.kmpsdk.demo.touchscene.RemoteAiSceneDescriber
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
 * TouchScene 拍摄页。
 *
 * 流程：语音/按钮“开始录像”启动 SDK 实时视频流（init → registerCameraStreamListener → startStream）。
 * 实时画面由 SDK 的 [InstaCapturePlayerView] 经 GL 渲染上屏，合并进“调试图层：原图”
 * （LIVE 且当前图层为原图时显示；其余图层/冻结态显示 [com.insta360.kmpsdk.demo.touchscene.TactileMapView]）。
 * YUV 解码仍只在冻结/刷新时短暂跑一帧，经 [TouchSceneCoordinator] 生成触觉图。
 *
 * 语音“冻结当前画面”固定当前帧（center-fit 占满屏宽、高度留白、不改比例）：
 * - 分支1：自动调用远程 AI 描述一次并语音播报（[RemoteAiSceneDescriber]，请求绑定 USB 以太网、蜂窝兜底）；
 * - 分支2：手指扫描冻结帧，按背景静默 / 主体连续 / 边界点振三档振动。
 *
 * 释放：device.preview.unregisterCameraStreamListener → setPipeline(null) → stopStream()
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
    private var previewWidth: Int = 1280
    private var previewHeight: Int = 960
    private var previewStarted = false
    private var rawFramePipeline: RawPreviewFramePipeline? = null
    private var rawStreamWidth: Int = 0
    private var rawStreamHeight: Int = 0
    private var rawVideoEncode: VideoEncode? = null
    private var stillRequestInFlight = false
    private var stillRequestIsRefresh = false
    private var stillRequestGeneration = 0L
    private var selectedLensMode: SensorMode? = null
    private var currentUiState: CameraCaptureUiState? = null
    private var touchSceneCoordinator: TouchSceneCoordinator? = null
    private var hapticRenderer: AndroidHapticRenderer? = null
    private var speechOutput: AndroidSpeechOutput? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val voiceTurnGate = VoiceTurnGate()
    private var manualDescriptionPending = false
    private var trace = TouchSceneTrace()
    private var performanceMetrics = TouchScenePerformanceMetrics()
    private var traceIo: ExecutorService? = null
    private val firstRawFrameLogged = AtomicBoolean(false)
    private val firstEncodedFrameLogged = AtomicBoolean(false)
    private var lastTracedSessionState: TactileSessionState? = null
    private var touchDebugCounts = TactileCellCounts(0, 0, 0)
    private var touchDebugCountsMapVersion: Long? = null
    private var livePreviewView: InstaCapturePlayerView? = null
    private var latestCameraPosture: CameraPosture? = null
    private val localDemoMode: Boolean get() = arguments?.getBoolean("touchsceneLocalDemo") == true

    private data class TactileCellCounts(val background: Int, val subject: Int, val boundary: Int)

    private fun computeCounts(map: TactileMap): TactileCellCounts {
        var bg = 0; var sub = 0; var bnd = 0
        for (y in 0 until map.height) for (x in 0 until map.width) {
            when (map.cellAt(x, y)) {
                TactileCell.BACKGROUND -> bg++
                TactileCell.SUBJECT -> sub++
                TactileCell.BOUNDARY -> bnd++
            }
        }
        return TactileCellCounts(bg, sub, bnd)
    }

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

    private val streamListener =
        object : CameraStreamListener {
            override fun onOpening() {
            }

            override fun onOpened() {
                Timber.d("streamListener.onOpened")
                traceEvent("preview", "stream_opened")

                val device = connectionViewModel.getCameraDevice() ?: return
                // 某些机型的参数回调晚于视频数据；先缓存预览默认参数。
                // 用户冻结时才创建解码器并请求关键帧。
                if (rawStreamWidth <= 0 || rawStreamHeight <= 0) {
                    rawStreamWidth = previewWidth
                    rawStreamHeight = previewHeight
                }
                rawVideoEncode = VideoEncode.ENCODE_H264
                configureRawFramePipelineIfReady()
                lifecycleScope.launch {
                    val actualEncode =
                        device.system
                            .fetchVideoEncodeType()
                            .onFailure { Timber.w(it, "fetch raw preview encode type") }
                            .getOrNull()
                            ?: VideoEncode.ENCODE_H264
                    if (rawVideoEncode != actualEncode) {
                        rawVideoEncode = actualEncode
                        configureRawFramePipelineIfReady()
                    }
                }
                prepareLivePreviewPlayer()
                _binding?.root?.post {
                    if (_binding == null) return@post
                    updateStreamToggleLabel()
                    speechOutput?.speak(getString(R.string.touchscene_stream_started))
                }
            }

            override fun onIdle() {
                Timber.w("streamListener.onIdle")
                traceEvent("preview", "stream_idle")
            }

            override fun onStreamDataNotify(streamData: PreviewStreamFrame) {
                if (streamData.type.isVideo && firstEncodedFrameLogged.compareAndSet(false, true)) {
                    traceEvent("preview", "first_encoded_frame", code = streamData.type.toString())
                }
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
                    previewWidth = paramsUpdate.previewWidth
                    previewHeight = paramsUpdate.previewHeight
                    configureRawFramePipelineIfReady()
                }
                livePreviewView?.let { view ->
                    paramsUpdate.offsetData?.let { offset ->
                        view.setOffset(
                            OffsetData(
                                offsetV1 = offset.offsetV1,
                                offsetV2 = offset.offsetV2,
                                offsetV3 = offset.offsetV3,
                                offsetV6 = offset.offsetV6,
                            ),
                            paramsUpdate.stabOffset.orEmpty(),
                        )
                    }
                    if (paramsUpdate.previewWidth > 0 && paramsUpdate.previewHeight > 0 && paramsUpdate.previewFps > 0) {
                        view.setPreviewResolution(paramsUpdate.previewWidth, paramsUpdate.previewHeight)
                        view.setFps(paramsUpdate.previewFps)
                    }
                    paramsUpdate.windowCropInfo?.let { crop ->
                        view.setWindowCropInfo(
                            MediaWindowCropInfo(
                                srcWidth = crop.src_width,
                                srcHeight = crop.src_height,
                                dstWidth = crop.dst_width,
                                dstHeight = crop.dst_height,
                                offsetX = crop.crop_offset_x,
                                offsetY = crop.crop_offset_y,
                            ),
                        )
                    }
                }
            }
        }

    private val touchScenePostureListener =
        object : CameraPostureUpdate {
            override fun updatePosture(cameraPosture: CameraPosture) {
                latestCameraPosture = cameraPosture
                val degrees =
                    when (cameraPosture) {
                        CameraPosture.CAMERA_POSTURE_ROTATE_90 -> 90
                        CameraPosture.CAMERA_POSTURE_ROTATE_180 -> 180
                        CameraPosture.CAMERA_POSTURE_ROTATE_270 -> 270
                        else -> 0
                    }
                touchSceneCoordinator?.setRotationDegrees(degrees)
                livePreviewView?.post { applyLivePreviewRotation(cameraPosture) }
            }
        }

    private fun applyLivePreviewRotation(posture: CameraPosture) {
        val view = livePreviewView ?: return
        val rotateDegreeContent = when (posture) {
            CameraPosture.CAMERA_POSTURE_ROTATE_90 -> 90
            CameraPosture.CAMERA_POSTURE_ROTATE_180 -> 180
            CameraPosture.CAMERA_POSTURE_ROTATE_270 -> 270
            else -> 0
        }
        view.updateRotate(rotateDegreeContent, 0, posture.nativeValue, posture.nativeValue)
        view.redetectCameraRotation()
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
        firstEncodedFrameLogged.set(false)
        lastTracedSessionState = null
        traceEvent(
            "connection",
            "preview_page_entered",
            code = if (connectionViewModel.getCameraDevice() == null) "NO_CAMERA" else "CAMERA_AVAILABLE",
            fallback = if (localDemoMode) "local_demo" else null,
        )
        if (!localDemoMode) observeCameraDisconnectedNavigateToConnection(connectionViewModel)
        setupTouchScene()

        binding.backLink.setOnClickListener { findNavController().popBackStack() }
        binding.streamToggleBtn.setOnClickListener {
            if (previewStarted) stopStreamByUser() else startStreamByUser()
        }
        binding.openModeParamsSheet.setOnClickListener {
            CaptureParamsBottomSheetFragment.show(childFragmentManager)
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
                    rebuildLensChips(s.lensOptions, s.selectedLens)
                    rebuildModeChips(s.modeOptions, s.selectedMode)
                    binding.captureBtn.text = s.captureButtonLabel
                    binding.captureProgressLabel.text = s.progressLabel.orEmpty()
                    binding.captureProgressLabel.isVisible = !s.progressLabel.isNullOrEmpty()
                    binding.captureSubStatusLabel.text = s.subStatusLabel.orEmpty()
                    binding.captureSubStatusLabel.isVisible = !s.subStatusLabel.isNullOrEmpty()
                    binding.resultPanel.isVisible = s.resultVisible
                    binding.resultSummary.text = s.resultSummary
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
                        if (needsRestart) {
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
                        if (!isAdded || _binding == null || !previewStarted) {
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
            stopPreview()
        }
        super.onStop()
    }

    private fun prepareOnDemandFrameCapture() {
        cancelStillRequest()
        firstRawFrameLogged.set(false)
        firstEncodedFrameLogged.set(false)
        rawStreamWidth = 0
        rawStreamHeight = 0
        rawVideoEncode = null
        LatestCameraFrameStore.clear()
        if (_binding != null) {
            binding.rawFrameStatus.setText(R.string.raw_frame_waiting)
            binding.rawFrameStatus.isVisible = true
        }
    }

    private fun startStillDecoder(generation: Long) {
        firstRawFrameLogged.set(false)
        rawFramePipeline =
            RawPreviewFramePipeline(
                analysisIntervalMs = Long.MAX_VALUE,
                onFrame = frameHandler@{ frame ->
                    if (!firstRawFrameLogged.compareAndSet(false, true)) return@frameHandler
                    traceEvent("decoder", "first_frame", code = "${frame.width}x${frame.height}")
                    _binding?.root?.post {
                        if (_binding == null || !stillRequestInFlight || generation != stillRequestGeneration) return@post
                        stopRawFramePipeline(clearFrameStore = false)
                        LatestCameraFrameStore.publish(frame)
                        binding.rawFrameStatus.text =
                            getString(R.string.raw_frame_ready_format, frame.width, frame.height, LatestCameraFrameStore.count())
                        touchSceneCoordinator?.analyzeAndFreeze(frame)
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
                        if (stillRequestInFlight && generation == stillRequestGeneration) {
                            failStillRequest(generation, "DECODER_ERROR")
                        }
                    }
                },
            )
        configureRawFramePipelineIfReady()
    }

    private fun configureRawFramePipelineIfReady() {
        val encode = rawVideoEncode ?: return
        if (rawStreamWidth <= 0 || rawStreamHeight <= 0) return
        runCatching {
            rawFramePipeline?.configure(rawStreamWidth, rawStreamHeight, encode)
        }.onFailure { Timber.w(it, "configure raw preview frame pipeline") }
    }

    private fun stopRawFramePipeline(clearFrameStore: Boolean = true) {
        rawFramePipeline?.close()
        rawFramePipeline = null
        if (clearFrameStore) LatestCameraFrameStore.clear()
        hapticRenderer?.cancel()
    }

    private val livePreviewPlayerListener =
        object : PlayerViewListener {
            override fun onLoadingStatusChanged(isLoading: Boolean) {}

            override fun onLoadingFinish() {
                val device = connectionViewModel.getCameraDevice()
                val pipeline = livePreviewView?.getPipeline()
                if (device == null || pipeline == null) {
                    Timber.w("live preview onLoadingFinish but device or pipeline is null")
                    return
                }
                Timber.d("live preview onLoadingFinish: bind pipeline")
                device.preview.setPipeline(pipeline)
                runCatching { device.preview.requestStreamIframe() }
            }

            override fun onFail(exception: InstaException) {
                Timber.w(exception, "live preview player onFail")
            }

            override fun onFirstFrameRendered() {
                Timber.d("live preview first frame rendered")
            }

            override fun onReleaseCameraPipeline() {
                Timber.d("live preview onReleaseCameraPipeline")
                connectionViewModel.getCameraDevice()?.preview?.setPipeline(null)
            }
        }

    private fun initLivePreviewPlayer() {
        if (livePreviewView != null || _binding == null) return
        livePreviewView =
            InstaCapturePlayerView(requireContext()).also { view ->
                view.setListener(livePreviewPlayerListener)
                view.setLifecycle(lifecycle)
                view.setGestureEnabled(false)
                binding.livePreviewContainer.addView(
                    view,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
    }

    /** 与 LiveStreamFragment 同一模式：onOpened 后重建渲染再 play，pipeline 在 onLoadingFinish 绑定。 */
    private fun prepareLivePreviewPlayer() {
        initLivePreviewPlayer()
        val view = livePreviewView ?: return
        view.post {
            if (!isAdded || _binding == null) return@post
            val pv = livePreviewView ?: return@post
            pv.destroyRender()
            pv.prepare(PreviewParams())
            pv.play()
            latestCameraPosture?.let(::applyLivePreviewRotation)
            updateLivePreviewVisibility()
        }
    }

    /** “调试图层：原图”与实时预览合并：仅 LIVE 且当前图层为原图、流在播时显示 SDK 渲染画面。 */
    private fun updateLivePreviewVisibility() {
        val b = _binding ?: return
        val showLive =
            previewStarted &&
                touchSceneCoordinator?.currentSessionState() == TactileSessionState.LIVE &&
                b.tactileMapView.debugMode == TactileDebugMode.ORIGINAL
        b.livePreviewContainer.isVisible = showLive
    }

    private fun releaseLivePreviewPlayer() {
        val view = livePreviewView ?: return
        view.setListener(null)
        view.destroy()
        _binding?.livePreviewContainer?.removeView(view)
        livePreviewView = null
    }

    private fun cancelStillRequest() {
        stillRequestGeneration++
        stillRequestInFlight = false
        touchSceneCoordinator?.cancelPendingStill()
        stopRawFramePipeline()
    }

    private fun setupTouchScene() {
        val haptics = AndroidHapticRenderer(requireContext())
        hapticRenderer = haptics
        speechOutput = AndroidSpeechOutput(requireContext(), ::onSpeechOutputChanged)
        binding.tactileMapView.hapticRenderer = haptics
        binding.tactileMapView.onExplorationStarted = { version ->
            traceEvent("exploration", "touch_started", mapVersion = version)
        }
        binding.tactileMapView.onTouchDebug = { px, py, grid, cell, layerValue ->
            _binding?.let { b ->
                val counts = touchDebugCounts
                val gx = grid?.x?.toString() ?: "-"
                val gy = grid?.y?.toString() ?: "-"
                b.touchsceneStatus.text =
                    "touch=(${px.toInt()},${py.toInt()}) grid=($gx,$gy) cell=$cell v=$layerValue " +
                        "B=${counts.boundary} S=${counts.subject} BG=${counts.background}"
            }
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
                TactileDebugMode.PHOTO_BINARY -> R.string.touchscene_debug_photo_binary
                TactileDebugMode.TOUCH_MAP -> R.string.touchscene_debug_touch_map
            }
            binding.debugLayerBtn.setText(label)
            updateLivePreviewVisibility()
        }
        touchSceneCoordinator =
            TouchSceneCoordinator(
                describer = RemoteAiSceneDescriber(
                    requireContext(),
                    fallback = MlKitSceneDescriber(requireContext()),
                ),
                descriptionTimeoutMs = 10_000L,
            ) { update ->
                val processedAtMs = SystemClock.elapsedRealtime()
                _binding?.root?.post {
                    if (_binding == null) return@post
                    update.debugSnapshot?.source?.let { source ->
                        performanceMetrics.record(
                            source.receivedAtElapsedRealtimeMs,
                            processedAtMs,
                            SystemClock.elapsedRealtime(),
                        )
                    }
                    renderTouchSceneUpdate(update)
                    if (stillRequestInFlight && update.sessionState == TactileSessionState.EXPLORING) {
                        finishStillRequest(update.map)
                    }
                }
            }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).also {
            it.setRecognitionListener(buildRecognitionListener())
        }

        binding.freezeMapBtn.setOnClickListener { freezeCurrentFrame() }
        binding.refreshMapBtn.setOnClickListener {
            if (binding.tactileMapView.isFingerExploring) {
                speechOutput?.speak(getString(R.string.touchscene_lift_finger_before_map_change))
                return@setOnClickListener
            }
            requestStillFrame(refresh = true)
        }
        binding.returnLiveBtn.setOnClickListener {
            if (binding.tactileMapView.isFingerExploring) {
                speechOutput?.speak(getString(R.string.touchscene_lift_finger_before_map_change))
                return@setOnClickListener
            }
            cancelStillRequest()
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
            binding.streamToggleBtn.isEnabled = false
        }
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
        binding.scrollView.gestureInterceptTarget =
            if (update.sessionState == TactileSessionState.LIVE) null else binding.tactileMapView
        if (map != null && touchDebugCountsMapVersion != map.version) {
            touchDebugCounts = computeCounts(map)
            touchDebugCountsMapVersion = map.version
        }
        val canExplore = update.sessionState != TactileSessionState.LIVE && map != null
        binding.freezeMapBtn.isEnabled = update.sessionState == TactileSessionState.LIVE && !stillRequestInFlight
        binding.returnLiveBtn.isEnabled = update.sessionState != TactileSessionState.LIVE
        binding.mapMoveUpBtn.isEnabled = canExplore
        binding.mapMoveDownBtn.isEnabled = canExplore
        binding.mapMoveLeftBtn.isEnabled = canExplore
        binding.mapMoveRightBtn.isEnabled = canExplore
        binding.refreshMapBtn.isEnabled = update.sessionState != TactileSessionState.LIVE && !stillRequestInFlight
        binding.touchsceneStatus.text =
            when (update.sessionState) {
                TactileSessionState.LIVE -> getString(R.string.touchscene_live_map)
                TactileSessionState.EXPLORING ->
                    getString(R.string.touchscene_exploring_map, map?.version ?: 0L) +
                        " · B=${touchDebugCounts.boundary} S=${touchDebugCounts.subject} BG=${touchDebugCounts.background}"
                TactileSessionState.STALE -> getString(R.string.touchscene_stale_map)
            }
        if (update.sessionState == TactileSessionState.STALE) {
            binding.touchsceneStatus.announceForAccessibility(getString(R.string.touchscene_stale_map))
        }
        updateLivePreviewVisibility()
    }

    /** 点击后获取一张新帧，分析完成才进入触觉探索。 */
    private fun freezeCurrentFrame() {
        if (binding.tactileMapView.isFingerExploring) {
            speechOutput?.speak(getString(R.string.touchscene_lift_finger_before_map_change))
            return
        }
        requestStillFrame(refresh = false)
    }

    private fun requestStillFrame(refresh: Boolean) {
        if (stillRequestInFlight) return
        if (!previewStarted && !localDemoMode) {
            traceEvent("map", "freeze_failed", code = "STREAM_NOT_STARTED")
            speechOutput?.speak(getString(R.string.touchscene_stream_not_started))
            return
        }
        stillRequestInFlight = true
        stillRequestIsRefresh = refresh
        val generation = ++stillRequestGeneration
        traceEvent("map", if (refresh) "refresh_requested" else "freeze_requested")
        binding.freezeMapBtn.isEnabled = false
        binding.refreshMapBtn.isEnabled = false
        binding.touchsceneStatus.setText(R.string.touchscene_freezing_frame)
        if (localDemoMode) {
            touchSceneCoordinator?.analyzeAndFreeze(
                FakeCameraGateway().frame(FakeContourScene.IRREGULAR, SystemClock.elapsedRealtime()),
            )
        } else {
            LatestCameraFrameStore.clear()
            startStillDecoder(generation)
        }
        binding.root.postDelayed({
            if (_binding == null || !stillRequestInFlight || generation != stillRequestGeneration) return@postDelayed
            failStillRequest(generation, "TIMEOUT")
        }, 8_000L)
    }

    private fun failStillRequest(generation: Long, code: String) {
        if (!stillRequestInFlight || generation != stillRequestGeneration) return
        cancelStillRequest()
        binding.freezeMapBtn.isEnabled = touchSceneCoordinator?.currentSessionState() == TactileSessionState.LIVE
        binding.refreshMapBtn.isEnabled = touchSceneCoordinator?.currentSessionState() != TactileSessionState.LIVE
        binding.touchsceneStatus.setText(R.string.touchscene_no_frame)
        traceEvent("map", "freeze_failed", code = code)
        speechOutput?.speak(getString(R.string.touchscene_no_frame))
    }

    private fun finishStillRequest(map: TactileMap?) {
        if (map == null) return
        val refreshed = stillRequestIsRefresh
        stillRequestInFlight = false
        stillRequestGeneration++
        traceEvent("map", if (refreshed) "refreshed" else "frozen", mapVersion = map.version)
        binding.refreshMapBtn.isEnabled = true
        binding.returnLiveBtn.isEnabled = true
        binding.tactileMapView.explorationEnabled = true
        binding.scrollView.gestureInterceptTarget = binding.tactileMapView
        binding.tactileMapView.requestFocus()
        if (refreshed) {
            speechOutput?.speak("触觉地图已刷新")
        } else {
            speechOutput?.speak("已冻结当前画面，正在识别画面内容")
            describeLatestScene()
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
                }
            }
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
            VoiceCommand.StartStream -> startStreamByUser()
            VoiceCommand.StopStream -> stopStreamByUser()
            VoiceCommand.FreezeFrame -> freezeCurrentFrame()
            VoiceCommand.ReturnLive -> binding.returnLiveBtn.performClick()
            VoiceCommand.DescribeScene -> describeLatestScene()
            VoiceCommand.Repeat -> if (speechOutput?.repeatLast() != true) speechOutput?.speak(getString(R.string.touchscene_no_frame))
            VoiceCommand.Help -> speechOutput?.speak(getString(R.string.touchscene_help))
            is VoiceCommand.Unknown -> speechOutput?.speak(getString(R.string.touchscene_unknown_command))
        }
    }

    private fun startStreamByUser() {
        if (localDemoMode || connectionViewModel.getCameraDevice() == null) {
            traceEvent("preview", "start_rejected", code = "NO_CAMERA", fallback = if (localDemoMode) "local_demo" else null)
            val message = getString(R.string.touchscene_stream_requires_camera)
            binding.touchsceneStatus.text = message
            speechOutput?.speak(message)
            return
        }
        if (previewStarted) {
            speechOutput?.speak(getString(R.string.touchscene_stream_started))
            return
        }
        speechOutput?.speak(getString(R.string.touchscene_stream_starting))
        startPreviewIfNeeded()
    }

    private fun stopStreamByUser() {
        if (!previewStarted) {
            speechOutput?.speak(getString(R.string.touchscene_stream_not_started))
            return
        }
        hapticRenderer?.cancel()
        stopPreview()
        speechOutput?.speak(getString(R.string.touchscene_stream_stopped))
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
            if (current.busy || current.captureCommand != null) {
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
        prepareOnDemandFrameCapture()
        runCatching {
            device.preview.setPipeline(null)
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
            Timber.w(it, "start preview stream")
        }
        updateStreamToggleLabel()
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

    private fun stopPreview() {
        Timber.d("stopPreview previewStarted=%s", previewStarted)
        if (previewStarted) traceEvent("preview", "stop_requested")
        val device = connectionViewModel.getCameraDevice()
        if (device != null && previewStarted) {
            stopCameraStreamBestEffort(device)
        } else {
            runCatching { device?.preview?.setPipeline(null) }
                .onFailure { Timber.w(it, "unbind inactive preview pipeline") }
        }
        cancelStillRequest()
        previewStarted = false
        traceEvent("preview", "stopped")
        updateStreamToggleLabel()
    }

    private fun updateStreamToggleLabel() {
        _binding?.streamToggleBtn?.setText(
            if (previewStarted) R.string.touchscene_stream_stop else R.string.touchscene_stream_start,
        )
        updateLivePreviewVisibility()
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
     * 切镜头/切模式统一入口：无预览播放器后，REBUILD_PLAYER 退化为纯 SDK 切换，
     * 只有 RESTART_STREAM 需要完整重启流。
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

            PreviewRefreshStrategy.REBUILD_PLAYER,
            PreviewRefreshStrategy.NO_OP,
            -> {
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

    /**
     * 完整重启 SDK 预览流，让底层重协商分辨率/帧率/解码器。
     * 顺序：解绑 pipeline → unregister → stopStream → 切 SDK → register → startStream。
     */
    private suspend fun doRestartStream(
        device: CameraDevice,
        switchSdk: suspend () -> Boolean,
    ): Boolean {
        if (_binding == null || !previewStarted) return false

        stopCameraStreamBestEffort(device)
        cancelStillRequest()
        previewStarted = false
        Timber.d("doRestartStream: stream stopped")

        val switched = runPreviewSdkSwitch(switchSdk, ::reportPreviewSwitchFailure)
        Timber.d("doRestartStream: sdkSwitch result=%s", switched)

        if (!isAdded || _binding == null) {
            Timber.d("doRestartStream: abort after sdkSwitch")
            return false
        }
        prepareOnDemandFrameCapture()
        var streamStarted = false
        runCatching {
            device.preview.setPipeline(null)
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
            Timber.w(it, "restart preview stream after lens switch")
        }
        updateStreamToggleLabel()
        return switched && streamStarted
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

    /** 全局 busy 时禁用全部次要入口；拍摄流程中也禁止切镜头/模式。 */
    private fun applySecondaryInteractionLock(locked: Boolean) {
        val alpha = if (locked) 0.45f else 1f
        listOf(
            binding.backLink,
            binding.openModeParamsSheet,
        ).forEach {
            it.isEnabled = !locked
            it.alpha = alpha
        }
        CaptureChips.setInteractionLocked(binding.lensChipContainer, locked)
        CaptureChips.setInteractionLocked(binding.modeChipContainer, locked)
    }

    private fun applyOperationLockState(s: CameraCaptureUiState) {
        // 拍摄命令在途（开始/停止过渡期）同样触发全屏遮罩与交互锁
        val operationLocked = s.busy || s.captureCommand != null
        binding.blockingOverlay.isVisible = operationLocked
        binding.blockingOverlay.setText(s.loadingText?:"")
        binding.captureBtn.isEnabled = s.captureButtonEnabled && !operationLocked
        applySecondaryInteractionLock(operationLocked || s.captureFlowActive)
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
        stopPreview()
        releaseLivePreviewPlayer()
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechOutput?.close()
        speechOutput = null
        touchSceneCoordinator?.close()
        touchSceneCoordinator = null
        hapticRenderer?.cancel()
        hapticRenderer = null
        traceEvent("session", "view_destroyed")
        flushTrace()
        traceIo?.shutdown()
        traceIo = null
        super.onDestroyView()
        _binding = null
    }
}
