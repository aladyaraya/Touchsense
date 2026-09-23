package com.insta360.kmpsdk.demo

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.arashivision.inskmp.editsdk.manager.INSKMPEditSDKMgr
import com.insta360.kmpsdk.demo.util.DemoLogcatDumper
import java.io.File
import org.json.JSONObject
import timber.log.Timber

class DemoApplication : Application() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: DemoApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.plant(Timber.DebugTree())
        quarantineCorruptCameraParams()
        // 尽早开始录制系统日志：不依赖 SDK 初始化与运行时权限，覆盖冷启动阶段
        DemoLogcatDumper.autoStart(this)
        // 添加网络监听器监控网络状态，方便调试
        startNetworkListener()

        INSKMPEditSDKMgr.enableDebug(true)
    }

    /** SDK 偶尔留下写到一半的参数 JSON；保留原文件供排查，并允许下次连接重新下载。 */
    private fun quarantineCorruptCameraParams() {
        val filesDir = getExternalFilesDir(null) ?: return
        val paramsDir = File(filesDir, "insta/camera/params")
        paramsDir.listFiles()?.filter { it.isDirectory }?.forEach { cameraDir ->
            cameraDir.listFiles()?.filter { it.isFile && it.extension == "json" }?.forEach { file ->
                runCatching { JSONObject(file.readText()) }.onFailure { error ->
                    val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
                    if (file.renameTo(backup)) {
                        Timber.w(error, "Quarantined corrupt camera params: %s", file.name)
                    } else {
                        Timber.w(error, "Could not quarantine corrupt camera params: %s", file.name)
                    }
                }
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }

    fun startNetworkListener() {
        val connManager: ConnectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

        val mNetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Timber.d("onAvailable ==> %s", network)
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Timber.d("onLost ==> %s", network)
                }
            }
        connManager.registerNetworkCallback(request, mNetworkCallback)
    }
}
