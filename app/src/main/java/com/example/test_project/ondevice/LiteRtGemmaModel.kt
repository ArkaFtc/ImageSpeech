package com.example.test_project.ondevice

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gemma 4 E2B through LiteRT-LM. The engine is expensive to load and cheap to keep, so it is loaded
 * once, lazily, and held until [close]. Conversations are cheap and disposable.
 */
class LiteRtGemmaModel(
    context: Context,
    private val store: GemmaModelStore,
    private val profile: GemmaProfile = GemmaProfile.LARGE,
    private val sampler: com.google.ai.edge.litertlm.SamplerConfig? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : OnDeviceModel {
    private val cacheDir = context.applicationContext.cacheDir.absolutePath
    private val loadLock = Mutex()
    private val guard = Any()
    private var engine: Engine? = null
    private var epoch = 0

    /** Set when neither backend could start, so every press does not pay for another failed load. */
    @Volatile
    var unusable = false
        private set

    override val isLoaded: Boolean get() = synchronized(guard) { engine != null }

    suspend fun warmUp() {
        engine()
    }

    private suspend fun engine(): Engine = loadLock.withLock {
        synchronized(guard) { engine }?.let { return@withLock it }
        if (unusable) throw GemmaUnavailableException("Gemma could not start on this phone.")
        if (!store.isPresent) throw GemmaUnavailableException("The Gemma model is not downloaded.")
        val loadingEpoch = synchronized(guard) { epoch }
        val created = withContext(dispatcher) { create() }
        synchronized(guard) {
            if (epoch != loadingEpoch) {
                runCatching { created.close() }
                throw GemmaUnavailableException("Gemma was turned off while it was loading.")
            }
            engine = created
        }
        created
    }

    /**
     * GPU first on large phones, falling back to CPU when the GPU delegate is missing or refuses
     * the model. CPU only on smaller phones, where GPU memory gets the app killed.
     */
    private fun create(): Engine {
        val path = store.modelFile.absolutePath
        val tokens = profile.contextTokens
        val cpu = "CPU" to { EngineConfig(path, Backend.CPU(), Backend.CPU(), Backend.CPU(), tokens, GemmaConfig.MAX_IMAGES, cacheDir) }
        val gpu = "GPU" to { EngineConfig(path, Backend.GPU(), Backend.GPU(), Backend.CPU(), tokens, GemmaConfig.MAX_IMAGES, cacheDir) }
        val attempts = if (profile.useGpu) listOf(gpu, cpu) else listOf(cpu)
        var lastError: Throwable? = null
        for ((name, config) in attempts) {
            var candidate: Engine? = null
            try {
                candidate = Engine(config())
                candidate.initialize()
                Log.i(TAG, "Gemma loaded on $name")
                return candidate
            } catch (error: Throwable) {
                if (error is VirtualMachineError && error !is OutOfMemoryError) throw error
                Log.w(TAG, "Gemma could not start on $name", error)
                runCatching { candidate?.close() }
                lastError = error
            }
        }
        unusable = true
        throw GemmaUnavailableException("Gemma could not start on this phone.", lastError)
    }

    override suspend fun open(history: List<ModelTurn>, preface: ModelPrompt?): ModelSession {
        val engine = engine()
        return withContext(dispatcher) {
            val messages = history.flatMap { turn ->
                listOf(Message.user(turn.prompt.toContents()), Message.model(turn.reply))
            } + listOfNotNull(preface?.let { Message.user(it.toContents()) })
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(GemmaConfig.SYSTEM_PROMPT),
                    initialMessages = messages,
                    samplerConfig = sampler,
                    // Process the photo and history now, not when the first request arrives.
                    prefillPrefaceOnInit = messages.isNotEmpty(),
                    // Thinking would delay the first spoken word with nothing to show for it.
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    maxOutputToken = profile.outputTokens,
                ),
            )
            LiteRtSession(conversation, dispatcher)
        }
    }

    /** Callers must close their sessions first; closing the engine under a live one is unsafe. */
    override fun close() {
        val old = synchronized(guard) {
            epoch++
            unusable = false
            engine.also { engine = null }
        }
        old?.let { runCatching { it.close() } }
    }

    private companion object {
        const val TAG = "Gemma"
    }
}

private fun ModelPrompt.toContents(): Contents = Contents.of(
    buildList {
        // Image, then audio, then the instruction: the order the model was tuned on.
        images.forEach { add(Content.ImageBytes(it)) }
        audioWav?.let { add(Content.AudioBytes(it)) }
        add(Content.Text(text))
    },
)

private class LiteRtSession(
    private val conversation: Conversation,
    private val dispatcher: CoroutineDispatcher,
) : ModelSession {
    override fun send(prompt: ModelPrompt): Flow<String> = callbackFlow {
        val deltas = StreamDeltas()
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                // Thinking arrives on a separate channel; only answer text is kept.
                val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                val fresh = deltas.fresh(text)
                if (fresh.isNotEmpty()) trySend(fresh)
            }

            override fun onDone() {
                close()
            }

            override fun onError(throwable: Throwable) {
                close(throwable)
            }
        }
        conversation.sendMessageAsync(prompt.toContents(), callback)
        // Stopping collection must stop the GPU too, or a cancelled answer keeps generating.
        awaitClose { runCatching { conversation.cancelProcess() } }
    }
        // trySend must never drop text while speech catches up; the token limit bounds the buffer.
        .buffer(Channel.UNLIMITED)
        .flowOn(dispatcher)

    override fun close() {
        runCatching { conversation.cancelProcess() }
        runCatching { conversation.close() }
    }
}
