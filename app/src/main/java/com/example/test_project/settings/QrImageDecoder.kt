package com.example.test_project.settings

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Bounded local decoder for QR images selected with Android's photo picker. */
object QrImageDecoder {
    private const val MAX_FILE_BYTES = 12L * 1_024 * 1_024
    private const val MAX_SOURCE_PIXELS = 40_000_000L
    private const val MAX_DECODE_DIMENSION = 4_096

    sealed interface Result {
        data class Content(val value: String) : Result
        data object Unreadable : Result
        data object Oversized : Result
    }

    fun decode(resolver: ContentResolver, uri: Uri): Result {
        val length = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (length != null && length > MAX_FILE_BYTES) return Result.Oversized
        val exceedsByteLimit = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (total <= MAX_FILE_BYTES) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                }
                total > MAX_FILE_BYTES
            }
        }.getOrNull() ?: return Result.Unreadable
        if (exceedsByteLimit) return Result.Oversized

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsRead = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.isSuccess
        if (!boundsRead || bounds.outWidth <= 0 || bounds.outHeight <= 0) return Result.Unreadable
        val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (sourcePixels > MAX_SOURCE_PIXELS) return Result.Oversized

        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_DIMENSION ||
            bounds.outHeight / sample > MAX_DECODE_DIMENSION
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // getPixels() cannot read a hardware-backed bitmap.
            inMutable = true
        }
        val bitmap = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return Result.Unreadable

        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            )
            val text = runCatching { MultiFormatReader().decode(binary, hints).text }.getOrNull()
            if (text == null) Result.Unreadable else Result.Content(text)
        } catch (_: RuntimeException) {
            Result.Unreadable
        } finally {
            bitmap.recycle()
        }
    }
}
