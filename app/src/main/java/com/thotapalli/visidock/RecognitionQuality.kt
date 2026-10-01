package com.thotapalli.visidock

enum class OcrScript(val title: String) {
    Auto("Automatic"), Latin("Latin"), Devanagari("Devanagari"), Chinese("Chinese"), Japanese("Japanese"), Korean("Korean");
    companion object {
        fun forLanguage(language: String): OcrScript = when (language.lowercase(java.util.Locale.ROOT)) {
            "hi", "mr", "ne", "sa" -> Devanagari; "zh" -> Chinese; "ja" -> Japanese; "ko" -> Korean; else -> Latin
        }
    }
}

data class OcrDeviceBudget(val availableBytes: Long, val lowMemory: Boolean = false,
    val batteryLow: Boolean = false, val thermalStatus: Int = 0)
data class OcrQualityBudget(val baseEdge: Int, val detailPasses: Int, val detailPixels: Long, val admissionMillis: Long)

/** Quality remains bounded by device capacity. Native OCR work is never cancelled while using its bitmap. */
object RecognitionQuality {
    fun budget(device: OcrDeviceBudget): OcrQualityBudget = when {
        device.lowMemory || device.availableBytes < 160L * 1024 * 1024 -> OcrQualityBudget(1600, 0, 0, 0)
        device.thermalStatus >= 3 || device.batteryLow -> OcrQualityBudget(2400, 1, 1_500_000, 1000)
        else -> OcrQualityBudget(2400, 2, 3_000_000, 1800)
    }

    /** Detail work must actually provide more original pixels than the already successful full read. */
    fun addsDetail(fullWidth: Int, fullHeight: Int, detailWidth: Int, detailHeight: Int, area: OcrRegion): Boolean {
        val previousPixels = fullWidth.toDouble() * fullHeight * (area.right-area.left) * (area.bottom-area.top)
        return previousPixels > 0 && detailWidth.toDouble()*detailHeight > previousPixels*1.25
    }
}
