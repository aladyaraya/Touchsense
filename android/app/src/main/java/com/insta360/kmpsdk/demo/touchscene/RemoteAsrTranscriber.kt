package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import com.insta360.kmpsdk.demo.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Sends a user-ended WAV recording to Bailian's Qwen ASR OpenAI-compatible endpoint. */
class RemoteAsrTranscriber(
    context: Context,
    private val endpoint: String = BuildConfig.TOUCHSCENE_AI_ENDPOINT,
    private val apiKey: String = BuildConfig.TOUCHSCENE_AI_KEY,
    private val model: String = BuildConfig.TOUCHSCENE_AI_ASR_MODEL,
    private val ethernetWaitMs: Long = 1_500L,
    private val cellularWaitMs: Long = 3_000L,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    val isConfigured: Boolean get() = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun transcribe(wav: ByteArray): String {
        check(isConfigured) { "remote speech recognition is not configured" }
        require(wav.isNotEmpty()) { "audio is empty" }
        val url = URL(endpoint)
        require(url.protocol == "https" && url.host.endsWith(RemoteAiSceneDescriber.BAILIAN_HOST_SUFFIX)) {
            "remote ASR endpoint must be HTTPS on Bailian"
        }
        val audio = Base64.encodeToString(wav, Base64.NO_WRAP)
        val response = post(url, buildRequestBody(model, audio))
        return parseResponseText(response)?.takeIf(String::isNotBlank)
            ?: error("remote ASR response contained no transcript")
    }

    private fun post(url: URL, body: String): String {
        val lease = awaitInternetNetwork()
        try {
            val connection = (lease?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 45_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code !in 200..299) error("remote ASR HTTP $code: ${text.take(200)}")
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

    private fun awaitNetwork(transport: Int, waitMs: Long): NetworkLease? {
        val manager = connectivityManager ?: return null
        val latch = CountDownLatch(1)
        val found = AtomicReference<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.compareAndSet(null, network)
                latch.countDown()
            }

            override fun onUnavailable() = latch.countDown()
        }
        return runCatching {
            manager.requestNetwork(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(transport)
                    .build(),
                callback,
                waitMs.toInt(),
            )
            latch.await(waitMs + 500L, TimeUnit.MILLISECONDS)
            found.get()?.let { NetworkLease(manager, callback, it) }
        }.onFailure { Timber.w(it, "request internet network for remote ASR") }
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
        fun release() = runCatching { manager.unregisterNetworkCallback(callback) }.let { Unit }
    }

    companion object {
        internal fun buildRequestBody(model: String, wavBase64: String): String =
            JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_audio")
                                        .put(
                                            "input_audio",
                                            JSONObject().put("data", "data:audio/wav;base64,$wavBase64"),
                                        ),
                                ),
                            ),
                    ),
                )
                .put("stream", false)
                .put(
                    "asr_options",
                    JSONObject()
                        .put("language", "zh")
                        .put("enable_itn", false),
                )
                .toString()

        internal fun parseResponseText(body: String): String? {
            val message = runCatching {
                JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            }.getOrElse { return null }
            return when (val content = runCatching { message.get("content") }.getOrNull()) {
                is String -> content.trim().ifEmpty { null }
                is JSONArray -> buildString {
                    for (index in 0 until content.length()) {
                        val part = content.optJSONObject(index) ?: continue
                        append(part.optString("text"))
                    }
                }.trim().ifEmpty { null }
                else -> null
            }
        }
    }
}
