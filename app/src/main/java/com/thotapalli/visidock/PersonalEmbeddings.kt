package com.thotapalli.visidock

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlin.math.sqrt

/** Uses the already bundled MiniLM sentence encoder. No new model download or network. */
internal class PersonalEmbeddings(context:Context):AutoCloseable {
    private val environment=OrtEnvironment.getEnvironment()
    private val tokenizer=WordPieceTokenizer(context.assets.open("semantic/vocab.txt").bufferedReader().use {it.readLines()})
    private val session:OrtSession
    init { val options=OrtSession.SessionOptions().apply {setIntraOpNumThreads(1);setInterOpNumThreads(1)}
        try {session=context.assets.open("semantic/model.onnx").use {environment.createSession(it.readBytes(),options)}} finally {options.close()} }
    fun encode(text:String):FloatArray {
        val ids=tokenizer.encode(text,128)
        val inputs=mapOf("input_ids" to OnnxTensor.createTensor(environment,arrayOf(ids)),
            "attention_mask" to OnnxTensor.createTensor(environment,arrayOf(LongArray(ids.size){1})),
            "token_type_ids" to OnnxTensor.createTensor(environment,arrayOf(LongArray(ids.size))))
        try { session.run(inputs).use {result ->
            @Suppress("UNCHECKED_CAST") val hidden=(result[0].value as Array<Array<FloatArray>>)[0]
            val vector=FloatArray(hidden[0].size)
            hidden.forEach {token -> token.forEachIndexed {i,v ->vector[i]+=v/hidden.size}}
            val norm=sqrt(vector.sumOf {(it*it).toDouble()}).toFloat().coerceAtLeast(1e-12f)
            return FloatArray(vector.size){vector[it]/norm}
        }} finally {inputs.values.forEach {it.close()}}
    }
    override fun close()=session.close()
}
