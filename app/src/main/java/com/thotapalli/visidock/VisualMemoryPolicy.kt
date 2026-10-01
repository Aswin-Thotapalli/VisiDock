package com.thotapalli.visidock

/** Conservative admission budget, not a promise that native allocation cannot fail.
 * A 3.5GB emulator was killed with ~2.56GB RSS plus ~225MB swap during inference.
 * Model weights alone are not the peak: encoder, KV cache, photos and UI coexist.
 * Use reported physical memory (never swap/storage) and reserve space for Android.
 */
internal object VisualMemoryPolicy {
    const val GIB = 1024L * 1024 * 1024
    const val MIN_TOTAL = 5 * GIB // permits nominal 6GB devices after reserved memory

    fun requiredAvailable(modelBytes: Long, warm: Boolean, imageCount: Int, lowMemoryThreshold: Long): Long {
        val runtimeReserve = maxOf(GIB, lowMemoryThreshold.coerceAtLeast(0) * 2)
        val secondSideReserve = if (imageCount > 1) GIB / 4 else 0L
        return (if (warm) 0L else modelBytes) + runtimeReserve + secondSideReserve
    }

    fun allows(total: Long, available: Long, lowMemory: Boolean, lowRamDevice: Boolean,
               modelBytes: Long, warm: Boolean, imageCount: Int, lowMemoryThreshold: Long): Boolean =
        !lowRamDevice && !lowMemory && total >= MIN_TOTAL &&
            available >= requiredAvailable(modelBytes, warm, imageCount, lowMemoryThreshold)
}
