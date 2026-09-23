package com.insta360.kmpsdk.demo.raw

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * Decodes a still image file into a bitmap whose long edge is at most [maxLongEdge],
 * using power-of-two subsampling so a full-resolution camera photo never materialises
 * as a huge ARGB buffer.
 */
fun decodeSampledBitmap(path: String, maxLongEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongEdge) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, options)
}

/** Decodes a gallery/document Uri with bounded memory and applies its EXIF rotation. */
fun decodeSampledBitmap(contentResolver: ContentResolver, uri: Uri, maxLongEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongEdge) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return null
    val rotation = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90f
                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180f
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    if (rotation == 0f) return decoded
    return Bitmap.createBitmap(
        decoded,
        0,
        0,
        decoded.width,
        decoded.height,
        Matrix().apply { postRotate(rotation) },
        true,
    ).also { if (it !== decoded) decoded.recycle() }
}

/**
 * Converts an RGB bitmap into the compact planar YUV420 layout expected by [Yuv420Frame]:
 * studio-range luma (16..235) and 2x2-subsampled chroma biased by 128, matching the
 * inverse transform in `Yuv420Frame.toRgbDebugBitmap`.
 *
 * Width and height are rounded down to even so the chroma planes align exactly.
 */
fun Bitmap.toYuv420Frame(sourceTimestampMs: Long, receivedAtElapsedRealtimeMs: Long): Yuv420Frame {
    val w = width and 0x7FFFFFFE
    val h = height and 0x7FFFFFFE
    require(w > 0 && h > 0) { "bitmap too small for YUV conversion: ${width}x${height}" }

    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)

    val yPlane = ByteArray(w * h)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        yPlane[i] = y.coerceIn(0, 255).toByte()
    }

    val chromaW = w / 2
    val chromaH = h / 2
    val uPlane = ByteArray(chromaW * chromaH)
    val vPlane = ByteArray(chromaW * chromaH)
    var c = 0
    for (cj in 0 until chromaH) {
        val rowBase = (cj * 2) * w
        for (ci in 0 until chromaW) {
            val p = pixels[rowBase + ci * 2]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
            val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
            uPlane[c] = u.coerceIn(0, 255).toByte()
            vPlane[c] = v.coerceIn(0, 255).toByte()
            c++
        }
    }

    return Yuv420Frame(
        width = w,
        height = h,
        sourceTimestampMs = sourceTimestampMs,
        receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
        y = yPlane,
        u = uPlane,
        v = vPlane,
    )
}
