package com.example.test_project.processing.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.test_project.R
import com.example.test_project.contract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Owns image preparation and OCR. Speech and UI are consumers, never dependencies. */
class DocumentReader(private val context: Context) : AutoCloseable {
    private val ocr = LocalPPOCRv6Runner(context)
    private var uvdoc: UvDocRunner? = null

    suspend fun read(
        request: ProcessingRequest,
        onStatus: suspend (String) -> Unit,
        onBlock: suspend (TextBlock) -> Unit,
    ): LocalPPOCRv6Runner.OcrResult {
        val regions = mutableListOf<LocalPPOCRv6Runner.Region>()
        val blocks = mutableListOf<TextBlock>()
        suspend fun publish(block: TextBlock) {
            blocks += block
            onBlock(block)
        }
        val spine =
            if (request.mode == ReadingMode.BOOK_SPREAD) PagePreparation.spine(request.image)
            else request.image.width / 2
        val areas = PagePlan.areas(request.image.width, request.image.height, request.mode, spine)
        Log.i("ReadingTiming", "pageSplit=$spine mode=${request.mode}")
        for ((index, area) in areas.withIndex()) {
            currentCoroutineContext().ensureActive()
            onStatus(context.getString(R.string.reading_page, index + 1, areas.size))
            val crop =
                Bitmap.createBitmap(request.image, area.left, area.top, area.width, area.height)
            var prepared: Bitmap? = null
            var flattened: Bitmap? = null
            try {
                prepared = PagePreparation.prepare(crop, request.mode != ReadingMode.TEXT)
                if (request.mode != ReadingMode.TEXT) {
                    val start = SystemClock.elapsedRealtime()
                    try {
                        val runner = uvdoc ?: UvDocRunner(context).also { uvdoc = it }
                        flattened = runner.flatten(prepared)
                        Log.i(
                            "ReadingTiming",
                            "page=${index+1} dewarpMs=${SystemClock.elapsedRealtime()-start}",
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w("DocumentReader", "Page correction failed", error)
                        onStatus(context.getString(R.string.dewarp_fallback))
                    }
                }
                currentCoroutineContext().ensureActive()
                val assembler =
                    PassageAssembler(index + 1, context.getString(R.string.unclear_text))
                ocr.readPage(flattened ?: prepared, request.mode != ReadingMode.TEXT) { region ->
                    if (region.confidence >= .7f && region.text.isNotBlank()) regions += region
                    assembler.offer(region).forEach { publish(it) }
                }
                assembler.flush()?.let { publish(it) }
            } finally {
                flattened
                    ?.takeIf { it !== prepared && it !== crop && it !== request.image }
                    ?.recycle()
                prepared?.takeIf { it !== crop && it !== request.image }?.recycle()
                if (crop !== request.image) crop.recycle()
            }
        }
        return LocalPPOCRv6Runner.OcrResult(regions, documentBlocks = blocks.toList())
    }

    override fun close() {
        uvdoc?.close()
        ocr.close()
    }
}
