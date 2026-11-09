package com.tensei.ytxtube

import java.time.LocalDate

/**
 * Search filters, applied by YouTube itself rather than by trimming results locally:
 * - preset upload dates and the content type are encoded into YouTube's search `params`
 *   (the same protobuf the website puts in `sp=`),
 * - a custom date range uses YouTube's `after:` / `before:` search operators.
 * Pure Kotlin so it can be unit tested.
 */
data class SearchFilters(
    val date: DateFilter = DateFilter.Any,
    val type: TypeFilter = TypeFilter.All,
    /** Inclusive custom range, used when [date] is [DateFilter.Custom]. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    enum class DateFilter(val label: String, val code: Int) {
        Any("Any time", 0),
        Day("Last 24 hours", 2),
        Week("Last week", 3),
        Month("Last month", 4),
        Custom("Custom range", 0),
    }

    enum class TypeFilter(val label: String) { All("All"), Videos("Videos"), Shorts("Shorts"), Live("Live") }

    val isActive: Boolean get() = date != DateFilter.Any || type != TypeFilter.All

    /** Base64 `params` for the InnerTube search request, or null when nothing is encoded. */
    fun params(): String? {
        // Filters message (field 2): 1 = upload date, 2 = type (1 video, 9 shorts), 8 = live.
        val inner = ArrayList<Int>()
        if (date.code > 0) inner += listOf(0x08, date.code)
        when (type) {
            TypeFilter.Videos -> inner += listOf(0x10, 1)
            TypeFilter.Shorts -> inner += listOf(0x10, 9)
            TypeFilter.Live -> inner += listOf(0x40, 1)
            TypeFilter.All -> Unit
        }
        if (inner.isEmpty()) return null
        val bytes = (listOf(0x12, inner.size) + inner).map { it.toByte() }.toByteArray()
        return base64(bytes)
    }

    /** The query sent to YouTube: the text plus `after:`/`before:` for a custom range. */
    fun query(text: String): String {
        if (date != DateFilter.Custom) return text.trim()
        val parts = mutableListOf(text.trim())
        from?.let { parts += "after:$it" }
        // "before:" excludes that day, so the day after the end makes the range inclusive.
        to?.let { parts += "before:${it.plusDays(1)}" }
        return parts.joinToString(" ")
    }

    /** Short summary for the filter button, e.g. "Last week · Shorts". */
    fun summary(): String = listOfNotNull(
        when (date) {
            DateFilter.Any -> null
            DateFilter.Custom -> listOfNotNull(from?.toString(), to?.toString()).joinToString(" – ").ifBlank { date.label }
            else -> date.label
        },
        type.takeIf { it != TypeFilter.All }?.label,
    ).joinToString(" · ")

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /** Standard Base64 with padding (java.util.Base64 needs API 26; minSdk is 24). */
        fun base64(data: ByteArray): String {
            val out = StringBuilder()
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
                val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
                out.append(ALPHABET[b0 shr 2])
                out.append(ALPHABET[((b0 and 0x03) shl 4) or ((if (b1 < 0) 0 else b1) shr 4)])
                out.append(if (b1 < 0) '=' else ALPHABET[((b1 and 0x0F) shl 2) or ((if (b2 < 0) 0 else b2) shr 6)])
                out.append(if (b2 < 0) '=' else ALPHABET[b2 and 0x3F])
                i += 3
            }
            return out.toString()
        }
    }
}
