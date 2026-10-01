package com.thotapalli.visidock

/** Changing the image backend cannot repair a grammar, request or context-budget error. */
internal object VisualRuntimePolicy {
    fun canRetryVisionOnCpu(message:String):Boolean {
        val value=message.lowercase(java.util.Locale.ROOT)
        return listOf("llguidance", "json schema", "constraint", "compute mask", "compute_mask", "max_num_tokens",
            "context length", "context window", "token limit", "cancelled", "canceled")
            .none(value::contains)
    }
}
