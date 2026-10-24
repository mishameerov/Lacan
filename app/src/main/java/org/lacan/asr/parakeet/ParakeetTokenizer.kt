package org.lacan.asr.parakeet

import org.json.JSONObject
import java.io.File

/** Decoding only: normalization and BPE merges are encoding operations and are not needed here. */
internal class ParakeetTokenizer(file: File) {
    private val vocabulary: List<String>
    private val specialIds: Set<Int>

    init {
        require(file.length() in 1..2_000_000) { "Invalid Parakeet tokenizer size" }
        val root = JSONObject(file.readText())
        val decoder = root.getJSONObject("decoder")
        require(decoder.getString("type") == "Metaspace" && decoder.getString("replacement") == "▁" &&
            decoder.getString("prepend_scheme") == "always") { "Unsupported Parakeet tokenizer decoder" }
        val model = root.getJSONObject("model")
        require(model.getString("type") == "BPE") { "Unsupported Parakeet tokenizer model" }
        val vocab = model.getJSONObject("vocab")
        require(vocab.length() == 8192) { "Expected 8192 Parakeet token pieces" }
        val pieces = arrayOfNulls<String>(8192)
        for (piece in vocab.keys()) {
            val id = vocab.getInt(piece)
            require(id in pieces.indices && pieces[id] == null) { "Invalid tokenizer vocabulary ID" }
            pieces[id] = piece
        }
        require(pieces.all { it != null })
        vocabulary = pieces.map { it!! }
        val added = root.getJSONArray("added_tokens")
        specialIds = buildSet {
            for (index in 0 until added.length()) {
                val entry = added.getJSONObject(index)
                if (entry.getBoolean("special")) add(entry.getInt("id"))
            }
        }
    }

    fun words(tokens: List<TimedParakeetToken>, windowStart: Int): List<ParakeetWord> =
        parakeetWords(tokens, vocabulary, specialIds, windowStart)

    fun decodeWords(words: List<ParakeetWord>): String =
        decodeMetaspace(vocabulary, specialIds, words.flatMap { it.ids }.toIntArray())

    fun trace(tokens: List<TimedParakeetToken>): List<ParakeetTokenTrace> =
        tokens.map { ParakeetTokenTrace(it.id, it.time, vocabulary[it.id]) }

    fun decodeOwned(tokens: List<TimedParakeetToken>, startSample: Int, endSample: Int): String =
        decodeMetaspace(vocabulary, specialIds, ownedParakeetTokens(tokens, vocabulary, specialIds, startSample, endSample))
}
