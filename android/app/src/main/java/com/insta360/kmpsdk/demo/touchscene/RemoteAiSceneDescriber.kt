package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import com.insta360.kmpsdk.demo.BuildConfig
import com.insta360.kmpsdk.demo.raw.Yuv420Frame
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 冻结帧远程 AI 描述，走阿里百炼 OpenAI 兼容协议（chat/completions，qwen-vl 视觉模型）。
 *
 * 网络：相机热点没有互联网，且连相机时进程被 bindProcessToNetwork 绑在相机
 * Wi-Fi 上，因此请求必须逐连接显式绑定到可上网的 Network：优先 USB 以太网
 * （TRANSPORT_ETHERNET），蜂窝（TRANSPORT_CELLULAR）兜底；不切换进程默认路由。
 *
 * 凭据：endpoint/key/model 通过 gradle 属性 `touchscene.ai.endpoint` /
 * `touchscene.ai.key` / `touchscene.ai.model` 注入 BuildConfig，不进版本库；
 * 未配置或请求失败时回退 [fallback] 本地描述。
 */
class RemoteAiSceneDescriber(
    context: Context,
    private val endpoint: String = BuildConfig.TOUCHSCENE_AI_ENDPOINT,
    private val apiKey: String = BuildConfig.TOUCHSCENE_AI_KEY,
    private val model: String = BuildConfig.TOUCHSCENE_AI_MODEL,
    private val fallback: SceneDescriber,
    private val ethernetWaitMs: Long = 1_500L,
    private val cellularWaitMs: Long = 3_000L,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
) : SceneDescriber {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    val isConfigured: Boolean get() = endpoint.isNotBlank() && apiKey.isNotBlank()

    override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription {
        if (!isConfigured) return fallback.describe(frame, map)
        return runCatching { describeRemote(frame) }
            .onFailure { Timber.w(it, "remote AI description failed, using fallback") }
            .getOrElse { fallback.describe(frame, map) }
    }

    private fun describeRemote(frame: Yuv420Frame): SceneDescription {
        val url = URL(endpoint)
        require(url.protocol == "https" && url.host.endsWith(BAILIAN_HOST_SUFFIX)) {
            "remote AI endpoint must be https on *$BAILIAN_HOST_SUFFIX"
        }
        val bitmap = frame.toRgbDebugBitmap(frame.width, frame.height)
        val jpegBase64 = try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
        val body = buildRequestBody(model, jpegBase64)
        val response = post(url, body)
        val text = parseResponseText(response)?.takeIf { it.isNotBlank() }
            ?: error("remote AI response had no description text")
        return SceneDescription(text, frame.sourceTimestampMs, "bailian-$model", subject = text)
    }

    private fun post(url: URL, body: String): String {
        // 只把这一条连接绑到可上网的 Network；进程默认绑定仍留在相机 Wi-Fi 上。
        // lease 必须在整个请求完成后才释放：openConnection 不会真正发起连接，
        // 提前注销回调可能让系统在请求中途拆掉这条网络。
        val lease = awaitInternetNetwork()
        try {
            val connection = (lease?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code !in 200..299) error("remote AI HTTP $code: ${text.take(200)}")
                return text
            } finally {
                connection.disconnect()
            }
        } finally {
            lease?.release()
        }
    }

    private fun awaitInternetNetwork(): NetworkLease? =
        awaitNetwork(NetworkCapabilities.TRANSPORT_ETHERNET, ethernetWaitMs)
            ?: awaitNetwork(NetworkCapabilities.TRANSPORT_CELLULAR, cellularWaitMs)

    private fun awaitNetwork(transportType: Int, waitMs: Long): NetworkLease? {
        val manager = connectivityManager ?: return null
        val latch = CountDownLatch(1)
        val found = AtomicReference<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.compareAndSet(null, network)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }
        return runCatching {
            manager.requestNetwork(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(transportType)
                    .build(),
                callback,
                waitMs.toInt(),
            )
            latch.await(waitMs + 500L, TimeUnit.MILLISECONDS)
            found.get()?.let {
                Timber.d("remote AI bound to network transport=%s", transportName(transportType))
                NetworkLease(manager, callback, it)
            }
        }.onFailure { Timber.w(it, "request %s network for remote AI", transportName(transportType)) }
            .getOrElse {
                runCatching { manager.unregisterNetworkCallback(callback) }
                null
            }
    }

    private class NetworkLease(
        private val manager: ConnectivityManager,
        private val callback: ConnectivityManager.NetworkCallback,
        private val network: Network,
    ) {
        fun openConnection(url: URL) = network.openConnection(url)

        fun release() {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    companion object {
        private const val JPEG_QUALITY = 80
        private const val MAX_TOKENS = 220
        internal const val BAILIAN_HOST_SUFFIX = ".maas.aliyuncs.com"
        private const val PROMPT =
            "请根据照片输出简体中文 JSON，只包含一个 description 字段。" +
                "用一句简短中文描述：主要主体是什么、在画面什么位置、大约占多大，供盲人摄影者判断构图。"

        /** 百炼 OpenAI 兼容 chat/completions 请求体；jpegBase64 不带 data: 前缀。 */
        internal fun buildRequestBody(model: String, jpegBase64: String): String =
            JSONObject()
                .put("model", model)
                .put("enable_thinking", false)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("max_tokens", MAX_TOKENS)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", PROMPT))
                                .put(
                                    JSONObject().put("type", "image_url").put(
                                        "image_url",
                                        JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64"),
                                    ),
                                ),
                        ),
                    ),
                )
                .toString()

        /**
         * 从 chat/completions 响应取描述文本。message.content 可能是字符串，
         * 也可能是分段数组；按 response_format 约定优先取 JSON 的 description
         * 字段，拿不到 JSON 时退回原始文本，保证语音播报不为空。
         */
        internal fun parseResponseText(body: String): String? {
            val message = runCatching {
                JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            }.getOrElse { return null }
            val content = when (val raw = runCatching { message.get("content") }.getOrNull()) {
                is JSONArray -> buildString {
                    for (i in 0 until raw.length()) {
                        val part = raw.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text"))
                    }
                }
                is String -> raw
                else -> return null
            }.trim()
            if (content.isEmpty()) return null
            val description = runCatching { JSONObject(content).optString("description").trim() }
                .getOrDefault("")
            return description.ifEmpty { content }
        }

        private fun transportName(transportType: Int): String = when (transportType) {
            NetworkCapabilities.TRANSPORT_ETHERNET -> "ethernet"
            NetworkCapabilities.TRANSPORT_CELLULAR -> "cellular"
            NetworkCapabilities.TRANSPORT_WIFI -> "wifi"
            else -> "transport-$transportType"
        }
    }
}
