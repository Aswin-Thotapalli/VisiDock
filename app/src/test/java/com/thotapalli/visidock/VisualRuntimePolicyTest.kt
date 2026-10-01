package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class VisualRuntimePolicyTest {
    @Test fun deviceGrammarFailureDoesNotReloadModelOnAnotherBackend() {
        assertFalse(VisualRuntimePolicy.canRetryVisionOnCpu("Failed to start nativeSendMessageAsync: INTERNAL: Failed to create LLGuidance constraint: Unimplemented keys: [\"propertyNames\"]"))
    }
    @Test fun requestBudgetAndCancellationFailuresAreNotImageBackendFailures() {
        listOf("Context length exceeded", "JSON schema is invalid", "max_num_tokens exceeded", "Request cancelled")
            .forEach {assertFalse(it,VisualRuntimePolicy.canRetryVisionOnCpu(it))}
    }
    @Test fun nativeEncoderFailureStillAllowsExistingCpuFallback() {
        assertTrue(VisualRuntimePolicy.canRetryVisionOnCpu("Failed to initialize GPU vision encoder: OpenCL compilation failed"))
    }
    @Test fun deviceDecodingMaskFailureDoesNotRetryImageEncoder() {
        assertFalse(VisualRuntimePolicy.canRetryVisionOnCpu(
            "llm_litert_compiled_model_executor.cc:1128 Failed to compute mask: compute_mask() called after stop"))
    }
}
