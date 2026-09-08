package com.example.test_project.processing.model

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.test_project.contract.RecordedAudio
import com.example.test_project.processing.ocr.LocalPPOCRv6Runner
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Gemma 4 E2B via LiteRT-LM. */
class GemmaSceneAnswerer(
    private val context: Context,
    private val modelStore: ModelStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SceneAnswerer {

    private val engineLock = Mutex()
    private var engine: Engine? = null

    @Volatile
    private var unusable = false

    override val isAvailable: Boolean
        get() = !unusable && modelStore.isPresent

    /**
     * Brings the engine up. Loading ~1.5 GB per question would be fatal, so this is called once and
     * the result held for the life of the process.
     */
    private suspend fun engine(): Engine? = engineLock.withLock {
        engine?.let { return it }
        if (unusable) return null

        val model = modelStore.modelFile
        if (!model.exists()) {
            Log.w(TAG, "No model at ${model.absolutePath}")
            return null
        }

        return withContext(dispatcher) {
            runCatching {
                Engine(
                    EngineConfig(
                        model.absolutePath,
                        Backend.GPU(),
                        Backend.GPU(),
                        Backend.GPU(),
                        MAX_CONTEXT_TOKENS,
                        MAX_IMAGES,
                        context.cacheDir.absolutePath,
                    )
                ).also { it.initialize() }
            }.onSuccess {
                engine = it
            }.onFailure {
                // A GPU backend that will not come up is not worth retrying on every press, and
                // falling back to CPU would decode at 2-5 tokens/sec - worse than not offering it.
                Log.e(TAG, "Engine failed to initialise; disabling the model lane", it)
                unusable = true
            }.getOrNull()
        }
    }

    override suspend fun beginTurn(
        image: Bitmap,
        ocr: LocalPPOCRv6Runner.OcrResult,
    ): SceneTurn? {
        val engine = engine() ?: return null

        return withContext(dispatcher) {
            runCatching {
                // The system instruction is text only - it accepts no images, and passing one
                // there fails with "Provided more images than expected in the prompt". The frame
                // and the OCR block go in as the opening user turn instead, which is also the
                // truthful shape: they are what the user is showing, not how to behave.
                val systemInstruction = Contents.of(ScenePrompt.SYSTEM)

                // Prompt order still matters: image, then the OCR block, then - later - the
                // question. This whole turn is what prefillPrefaceOnInit encodes during the hold.
                val context = listOf(
                    Message.user(
                        Contents.of(
                            Content.ImageBytes(image.toJpegBytes()),
                            Content.Text(ScenePrompt.ocrBlock(ocr)),
                        )
                    )
                )

                val conversation = engine.createConversation(
                    ConversationConfig(
                        systemInstruction,
                        context,
                        emptyList(),
                        null,
                        false,
                        emptyList(),
                        emptyMap(),
                        null,
                        // The reason this class exists in two halves: encode the preface now,
                        // under the user's voice, rather than after the button comes up.
                        true,
                        MAX_OUTPUT_TOKENS,
                        // Thinking is opt-in and stays off - reasoning tokens would delay the first
                        // spoken word with nothing to show for it.
                        ThinkingConfig(false, 0),
                        false,
                    )
                )
                GemmaTurn(conversation, dispatcher)
            }.onFailure {
                Log.e(TAG, "Could not open a turn", it)
            }.getOrNull()
        }
    }

    override fun close() {
        engine?.runCatching { close() }
        engine = null
    }

    private companion object {
        const val TAG = "GemmaSceneAnswerer"

        /**
         * E2B carries a 128K window, so this is headroom rather than a budget - it only has to
         * cover one image, an OCR block, a clip and the answer.
         */
        const val MAX_CONTEXT_TOKENS = 8192
        const val MAX_OUTPUT_TOKENS = 4096
        const val MAX_IMAGES = 1

        /** JPEG rather than raw: the encoder resamples anyway, and this keeps the JNI copy small. */
        const val JPEG_QUALITY = 90

        fun Bitmap.toJpegBytes(): ByteArray = ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }
}

/** One prefilled conversation, good for a single question. */
private class GemmaTurn(
    private val conversation: Conversation,
    private val dispatcher: CoroutineDispatcher,
) : SceneTurn {

    override fun ask(audio: RecordedAudio?, transcript: String?): Flow<String> = callbackFlow {
        val parts = buildList {
            // The clip carries emphasis that the transcript drops, and costs ~6 tokens per second.
            if (audio != null) add(Content.AudioBytes(audio.toWavBytes()))
            add(Content.Text(ScenePrompt.question(transcript, hasAudio = audio != null)))
        }

        // Emissions may arrive as deltas or as a growing full message depending on the runtime, so
        // the accumulated text is used to work out what is genuinely new rather than assuming.
        val seen = StringBuilder()

        val callback = object : com.google.ai.edge.litertlm.MessageCallback {
            override fun onMessage(message: Message) {
                val text = message.text()
                if (text.isEmpty()) return
                val fresh = when {
                    text.startsWith(seen) -> text.substring(seen.length)
                    else -> text
                }
                if (fresh.isEmpty()) return
                seen.append(fresh)
                trySendBlocking(fresh)
            }

            override fun onDone() {
                close()
            }

            override fun onError(throwable: Throwable) {
                close(throwable)
            }
        }

        withContext(dispatcher) {
            conversation.sendMessageAsync(Contents.of(parts), callback)
        }

        // Cancelling the collection has to stop generation, not just stop listening to it -
        // otherwise a barge-in would leave the GPU running for the rest of the abandoned answer.
        awaitClose {
            runCatching { conversation.cancelProcess() }
        }
    }.buffer(1).flowOn(dispatcher)

    override fun close() {
        runCatching { conversation.cancelProcess() }
        runCatching { conversation.close() }
    }

    private companion object {
        fun Message.text(): String =
            contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    }
}
