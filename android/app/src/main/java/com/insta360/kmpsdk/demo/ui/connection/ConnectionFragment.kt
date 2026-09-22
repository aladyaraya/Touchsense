package com.insta360.kmpsdk.demo.ui.connection

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentConnectionBinding
import com.insta360.kmpsdk.demo.touchscene.AndroidHapticRenderer
import com.insta360.kmpsdk.demo.touchscene.AndroidSpeechOutput
import kotlinx.coroutines.launch

class ConnectionFragment : Fragment() {
    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(
            requireActivity().application,
        )
    }

    private var speechOutput: AndroidSpeechOutput? = null
    private var hapticRenderer: AndroidHapticRenderer? = null
    private var previousConnectState: ConnectState = ConnectState.Idle

    private val scanAdapter =
        ScanDeviceAdapter(
            onBluetooth = { connectionViewModel.onConnectBluetooth(it) },
            onWifi = { connectionViewModel.onConnectWifiByBluetooth(it) },
            onWifiAware = { requestWifiAwarePermissionsThenConnect(it) },
            isWifiAwareSupported = { connectionViewModel.isWifiAwareSupported() },
        )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val haptics = AndroidHapticRenderer(requireContext())
        hapticRenderer = haptics
        speechOutput = AndroidSpeechOutput(requireContext())

        // 首页隐藏底部菜单栏
        activity?.findViewById<View>(R.id.bottom_nav)?.visibility = View.GONE

        // 拦截机制：防止 NestedScrollView 在用户按压/滑动按键时抢夺触摸事件
        binding.connectionScrollView.gestureInterceptTarget = binding.btnWifi

        binding.scanRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.scanRecycler.adapter = scanAdapter
        binding.scanRecycler.isNestedScrollingEnabled = false

        // 手指按压/触碰到 Wi-Fi 按钮区域时产生与触觉图像一致的实时震动反馈 (参考 AndroidHapticRenderer / TactileMapView)
        var isFingerOnButton = false
        binding.btnWifi.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isFingerOnButton = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    haptics.render(1)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    v.isPressed = isInside
                    if (isInside) {
                        if (!isFingerOnButton) {
                            isFingerOnButton = true
                            haptics.render(1)
                        }
                    } else {
                        if (isFingerOnButton) {
                            isFingerOnButton = false
                            haptics.cancel()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    haptics.cancel()
                    val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    if (isInside && isFingerOnButton) {
                        v.performClick()
                    }
                    isFingerOnButton = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    haptics.cancel()
                    isFingerOnButton = false
                    true
                }
                else -> false
            }
        }

        // 屏蔽原生 Tooltip 弹窗，防止长按时系统窗口抢占焦点导致手势中断
        binding.btnWifi.setOnLongClickListener {
            true
        }

        // 针对 TalkBack 读屏手势探索：滑入按钮区域即震动，移出即停止
        binding.btnWifi.setOnHoverListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> haptics.render(1)
                MotionEvent.ACTION_HOVER_EXIT -> haptics.cancel()
            }
            false
        }

        // 点击时发出“连接中”语音提示音并直接进入触觉摄影页面（确保终止触摸震动，避免进入循环震动）
        binding.btnWifi.setOnClickListener {
            haptics.cancel()
            speechOutput?.speak("连接中")
            connectionViewModel.onConnectWifiClicked()
            if (findNavController().currentDestination?.id == R.id.connectionFragment) {
                findNavController().navigate(R.id.action_connectionFragment_to_previewFragment)
            }
        }

        binding.btnScan.setOnClickListener { requestBleScanPermissionsThenStartScan() }
        binding.btnUsb.setOnClickListener { connectionViewModel.onUsbClicked() }
        binding.disconnectBtn.setOnClickListener {
            haptics.selfTest()
            connectionViewModel.disconnect()
        }
        binding.refreshDynamicBtn.setOnClickListener {
            haptics.selfTest()
            connectionViewModel.refreshDynamicInfo()
        }
        binding.entryTouchsceneLocalDemo.setOnClickListener {
            findNavController().navigate(
                R.id.action_connectionFragment_to_previewFragment,
                Bundle().apply { putBoolean("touchsceneLocalDemo", true) },
            )
        }

        binding.entryPreview.setOnClickListener {
            haptics.playSuccess()
            findNavController().navigate(R.id.action_connectionFragment_to_previewFragment)
        }
        binding.entryNoPreview.setOnClickListener {
            findNavController().navigate(R.id.action_connectionFragment_to_captureFragment)
        }
        binding.entryLiveStream.setOnClickListener {
            findNavController().navigate(R.id.action_connectionFragment_to_liveStreamFragment)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectionViewModel.connectionUi.collect { state ->
                    val isConnected = state.connectState == ConnectState.Connected
                    val isConnecting = state.connectState == ConnectState.Connecting

                    // 连接结果语音播报逻辑：
                    // 当处于“连接中”后，根据结果播报“连接成功”或“连接失败”
                    if (previousConnectState == ConnectState.Connecting) {
                        if (state.connectState == ConnectState.Connected) {
                            speechOutput?.speak("连接成功")
                            haptics.playSuccess()
                            if (findNavController().currentDestination?.id == R.id.connectionFragment) {
                                findNavController().navigate(R.id.action_connectionFragment_to_previewFragment)
                            }
                        } else if (state.connectState == ConnectState.Idle) {
                            speechOutput?.speak("连接失败")
                            haptics.playError()
                        }
                    } else if (state.connectState == ConnectState.Connected && findNavController().currentDestination?.id == R.id.connectionFragment) {
                        findNavController().navigate(R.id.action_connectionFragment_to_previewFragment)
                    }
                    previousConnectState = state.connectState

                    // 隐藏不需要的次要入口，保持界面极致简洁
                    binding.btnScan.isVisible = false
                    binding.btnUsb.isVisible = false
                    binding.scanRecycler.isVisible = false
                    binding.entryTouchsceneLocalDemo.isVisible = false

                    // 统一核心 Wi-Fi 按钮在各种状态下的视觉与无障碍播报
                    when (state.connectState) {
                        ConnectState.Idle -> {
                            binding.btnWifi.text = getString(R.string.touchscene_wifi_hero_title)
                            binding.btnWifi.contentDescription = getString(R.string.touchscene_wifi_hero_accessibility)
                            binding.btnWifi.backgroundTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(requireContext(), R.color.touchscene_primary)
                            )
                            binding.statusIndicatorDot.imageTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(requireContext(), R.color.touchscene_primary)
                            )
                            binding.statusText.text = state.statusText.ifBlank { getString(R.string.touchscene_status_ready) }
                            binding.connectionResult.text = state.statusMessage.ifBlank { getString(R.string.touchscene_wifi_hero_desc) }
                            binding.disconnectBtn.isVisible = false
                            binding.btnWifi.isEnabled = true
                        }
                        ConnectState.Connecting -> {
                            binding.btnWifi.text = getString(R.string.touchscene_wifi_hero_connecting)
                            binding.btnWifi.contentDescription = getString(R.string.touchscene_wifi_hero_connecting)
                            binding.btnWifi.backgroundTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(requireContext(), R.color.touchscene_warning)
                            )
                            binding.statusIndicatorDot.imageTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(requireContext(), R.color.touchscene_warning)
                            )
                            binding.statusText.text = getString(R.string.connecting_via_wifi)
                            binding.connectionResult.text = state.statusMessage.ifBlank { getString(R.string.connecting_via_wifi) }
                            binding.disconnectBtn.isVisible = false
                            binding.btnWifi.isEnabled = false
                        }
                        ConnectState.Connected -> {
                            binding.btnWifi.text = getString(R.string.touchscene_wifi_hero_connected)
                            binding.btnWifi.contentDescription = getString(R.string.touchscene_wifi_hero_connected)
                            binding.btnWifi.backgroundTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(requireContext(), R.color.touchscene_success)
                            )
                            binding.statusIndicatorDot.imageTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(requireContext(), R.color.touchscene_success)
                            )
                            binding.statusText.text = state.statusText.ifBlank { getString(R.string.camera_connected) }
                            binding.connectionResult.text = state.statusMessage.ifBlank { "图传信道畅通" }
                            binding.disconnectBtn.isVisible = true
                            binding.disconnectBtn.isEnabled = true
                            binding.btnWifi.isEnabled = true
                        }
                    }

                    val canRefreshDynamic = isConnected && !state.dynamicInfoRefreshing
                    binding.refreshDynamicBtn.isEnabled = canRefreshDynamic
                    binding.refreshDynamicBtn.text =
                        if (state.dynamicInfoRefreshing) {
                            getString(R.string.refreshing_dynamic_info)
                        } else {
                            getString(R.string.refresh_dynamic_info)
                        }

                    // 隐藏图1中转卡片与次要入口，避免视觉与操作干扰
                    binding.deviceInfoCard.isVisible = false
                    binding.entryCaptureLayout.isVisible = false
                    binding.entryPreview.isVisible = false
                    binding.entryLiveStream.isVisible = false
                    binding.entryNoPreview.isVisible = false

                    state.device?.let { d ->
                        binding.cameraModel.text = d.cameraType
                        binding.cameraSn.text = d.sn
                        binding.firmwareVersion.text = d.firmware
                        binding.activateTime.text = d.activated
                        binding.sdTotal.text = d.sdTotal
                        binding.internalTotal.text = d.internalTotal
                        binding.sdRemaining.text = d.sdRemaining
                        binding.sdStatus.text = d.sdStatus
                        binding.internalRemaining.text = d.internalRemaining
                        binding.internalStatus.text = d.internalStatus
                        binding.batteryLevel.text = d.batteryLevel
                        binding.chargingStatus.text = d.chargingStatus
                        binding.cameraTime.text = d.cameraTime
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.findViewById<View>(R.id.bottom_nav)?.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        hapticRenderer?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hapticRenderer?.cancel()
        hapticRenderer = null
        speechOutput?.close()
        speechOutput = null
        _binding = null
    }

    /** 未授权时调 native 扫描易触发「ble scan reject」；此处先补齐权限再开始扫描。 */
    private fun requestBleScanPermissionsThenStartScan() {
        val req = XXPermissions.with(this.requireContext()).permission(Permission.Group.BLUETOOTH)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            req.permission(Permission.ACCESS_FINE_LOCATION, Permission.ACCESS_COARSE_LOCATION)
        }
        req.request { _, allGranted ->
            if (allGranted) {
                connectionViewModel.onScanClicked()
            } else {
                Toast
                    .makeText(
                        requireContext(),
                        getString(R.string.ble_scan_permissions_required),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    /** WiFi Aware 的 NAN 数据通道在 Android 13+ 需要 NEARBY_WIFI_DEVICES，低版本回退到定位权限。 */
    private fun requestWifiAwarePermissionsThenConnect(index: Int) {
        val req = XXPermissions.with(this.requireContext())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            req.permission(Permission.NEARBY_WIFI_DEVICES)
        } else {
            req.permission(Permission.ACCESS_FINE_LOCATION)
        }
        req.request { _, allGranted ->
            if (allGranted) {
                connectionViewModel.onConnectWiFiAwareByBluetooth(index)
            } else {
                Toast
                    .makeText(
                        requireContext(),
                        getString(R.string.wifi_aware_permissions_required),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }
}
