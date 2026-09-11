package com.example.test_project.processing.ocr

import com.example.test_project.contract.TextBlock

/** First readable line goes immediately. Later lines form small, bounded speech passages. */
class PassageAssembler(private val page: Int, private val unclear: String) {
    private val pending = mutableListOf<LocalPPOCRv6Runner.Region>()
    private var first = true

    fun offer(region: LocalPPOCRv6Runner.Region): List<TextBlock> {
        val ready = mutableListOf<TextBlock>()
        val previous = pending.lastOrNull()
        if (
            previous != null &&
                (region.confidence < .7f ||
                    previous.confidence < .7f ||
                    TextBlocks.group(listOf(previous, region)).size > 1)
        )
            flush()?.let(ready::add)
        pending += region
        if (
            (first && pending.sumOf { it.text.length } >= 24) ||
                region.confidence < .7f ||
                pending.size >= 3 ||
                pending.sumOf { it.text.length } >= 180 ||
                region.text.trimEnd().lastOrNull() in listOf('.', '!', '?', ':')
        ) {
            flush()?.let(ready::add)
        }
        return ready
    }

    fun flush(): TextBlock? {
        if (pending.isEmpty()) return null
        val block =
            TextBlock(
                pending.map { if (it.confidence < .35f || it.text.isBlank()) unclear else it.text },
                pending.minOf { it.left },
                pending.minOf { it.top },
                pending.maxOf { it.right },
                pending.maxOf { it.bottom },
                uncertain = pending.any { it.confidence < .7f },
                page = page,
            )
        pending.clear()
        first = false
        return block
    }
}
