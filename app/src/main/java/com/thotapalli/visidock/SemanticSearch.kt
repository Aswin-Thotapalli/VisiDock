package com.thotapalli.visidock

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** BERT uncased basic + greedy WordPiece tokenizer, matching the bundled MiniLM vocabulary. */
class WordPieceTokenizer(vocabulary: List<String>) {
    private val vocab = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    fun encode(text: String, limit: Int = 256): LongArray {
        require(limit >= 2) { "Token limit must include CLS and SEP." }
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val basic = Regex("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}\\p{C}]").findAll(normalized).map { it.value }
        val output = mutableListOf(vocab.getValue("[CLS]"))
        for (word in basic) {
            if (output.size >= limit - 1) break
            var start = 0
            val pieces = mutableListOf<Long>()
            if (word.length > 100) pieces += vocab.getValue("[UNK]")
            else while (start < word.length) {
                var end = word.length
                var match: Long? = null
                while (end > start) {
                    match = vocab[(if (start > 0) "##" else "") + word.substring(start, end)]
                    if (match != null) break
                    end--
                }
                if (match == null) { pieces.clear(); pieces += vocab.getValue("[UNK]"); break }
                pieces += match
                start = end
            }
            output += pieces.take(limit - 1 - output.size)
        }
        output += vocab.getValue("[SEP]")
        return output.toLongArray()
    }
}

data class SearchResult(val cards: List<Card>, val semantic: Boolean, val unavailable: Boolean = false)

/** Embeddings are transient, per ViewModel; neither contacts nor vectors leave the device. */
class SemanticSearch(private val context: Context) : AutoCloseable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private val cache = object : LinkedHashMap<String, Pair<List<String>, List<FloatArray>>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<List<String>, List<FloatArray>>>?) = size > 256
    }
    private fun initialize() {
        if (session != null) return
        tokenizer = WordPieceTokenizer(context.assets.open("semantic/vocab.txt").bufferedReader().use { it.readLines() })
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setInterOpNumThreads(1) }
        try { session = context.assets.open("semantic/model.onnx").use { environment.createSession(it.readBytes(), options) } }
        finally { options.close() }
    }
    private fun embed(text: String): FloatArray {
        val ids = checkNotNull(tokenizer).encode(text)
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(environment, arrayOf(ids))
            inputs["attention_mask"] = OnnxTensor.createTensor(environment, arrayOf(LongArray(ids.size) { 1 }))
            inputs["token_type_ids"] = OnnxTensor.createTensor(environment, arrayOf(LongArray(ids.size)))
            checkNotNull(session).run(inputs).use { output ->
                @Suppress("UNCHECKED_CAST")
                val hidden = (output[0].value as Array<Array<FloatArray>>)[0]
                val pooled = FloatArray(hidden[0].size)
                hidden.forEach { token -> token.forEachIndexed { index, v -> pooled[index] += v / hidden.size } }
                val norm = sqrt(pooled.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-12f)
                return FloatArray(pooled.size) { pooled[it] / norm }
            }
        } finally { inputs.values.forEach { it.close() } }
    }
    suspend fun search(cards: List<Card>, query: String): SearchResult = withContext(Dispatchers.Default) {
        val exact = CardLogic.search(cards, query)
        if (cards.isEmpty()) return@withContext SearchResult(emptyList(), false)
        if (query.isBlank() || query.trim().split(Regex("\\s+")).size < 2) return@withContext SearchResult(exact, false)
        mutex.withLock {
            try {
                check(!closed.get()) { "Search engine is closed." }
                initialize()
                currentCoroutineContext().ensureActive()
                val queryVector = embed(query)
                cache.keys.retainAll(cards.map { it.id }.toSet())
                val dateFilter = when { query.contains("last month", true) -> "last month"; query.contains("this month", true) -> "this month"; else -> "" }
                val allowed = CardLogic.search(cards, dateFilter)
                val scored = allowed.map { card ->
                    currentCoroutineContext().ensureActive()
                    val fields = card.fields()
                    val embeddings = cache[card.id]?.takeIf { it.first == fields }?.second ?: run {
                        // Chunk long OCR and notes, rather than truncating away the remembered context.
                        val text = fields.filter(String::isNotBlank).joinToString(". ")
                        val words = text.split(Regex("\\s+"))
                        val chunks = words.windowed(100, 80, partialWindows=true).map { it.joinToString(" ") }.ifEmpty { listOf(card.name) }
                        chunks.map { currentCoroutineContext().ensureActive(); embed(it) }.also { cache[card.id] = fields to it }
                    }
                    card to (embeddings.maxOfOrNull { vector -> vector.indices.sumOf { (vector[it] * queryVector[it]).toDouble() } } ?: 0.0)
                }.filter { it.second >= 0.32 }.sortedByDescending { it.second }
                val ids = exact.map { it.id }.toSet()
                SearchResult(exact + scored.filter { it.first.id !in ids }.map { it.first }.take(20), true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { SearchResult(exact, false, unavailable=true) }
            finally { if (closed.get()) release() }
        }
    }
    suspend fun clear() = mutex.withLock { cache.clear() }
    private fun release() { session?.close(); session=null; tokenizer=null; cache.clear() }
    override fun close() {
        closed.set(true)
        // Inference is native work: close after an in-flight run leaves the mutex.
        if (mutex.tryLock()) try { release() } finally { mutex.unlock() }
    }
}
