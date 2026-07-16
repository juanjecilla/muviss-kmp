package com.codingpit.muviss.feature.settings.domain

/**
 * Minimal hand-rolled CSV tokenizer (RFC 4180 subset: quoted fields, `""` as
 * an escaped quote, commas/newlines inside quotes, `\r\n` or `\n` line
 * endings) — EPIC 18 explicitly rules out a third-party CSV library, and the
 * generic/TV Time import formats need nothing fancier than this.
 */
internal object CsvParser {
    fun parse(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val length = content.length

        fun endField() {
            row.add(field.toString())
            field.clear()
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (i < length) {
            val c = content[i]
            when {
                inQuotes && c == '"' && i + 1 < length && content[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }

                inQuotes && c == '"' -> inQuotes = false

                inQuotes -> field.append(c)

                c == '"' -> inQuotes = true

                c == ',' -> endField()

                c == '\r' -> Unit

                c == '\n' -> endRow()

                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows.filter { fields -> fields.any { it.isNotBlank() } }
    }
}

/**
 * A parsed CSV with its header row split out, offering case-insensitive
 * column lookup by name — every EPIC 18 CSV parser (generic + TV Time) reads
 * columns by name, never by position, since the exact column set/order
 * varies by source and export version.
 */
internal class CsvTable private constructor(private val headerIndex: Map<String, Int>, val rows: List<List<String>>) {

    /** Trimmed, blank-to-null value of [column] in [row] (case-insensitive column name), or null if the column doesn't exist or is empty. */
    fun value(row: List<String>, column: String): String? {
        val idx = headerIndex[column.lowercase()] ?: return null
        return row.getOrNull(idx)?.trim()?.ifBlank { null }
    }

    /** True if any of [columns] is present in the header (case-insensitive) — used for format sniffing/sub-shape dispatch. */
    fun hasAnyColumn(vararg columns: String): Boolean = columns.any { headerIndex.containsKey(it.lowercase()) }

    companion object {
        /** Null when [content] has no rows at all (not even a header). */
        fun from(content: String): CsvTable? {
            val raw = CsvParser.parse(content)
            val header = raw.firstOrNull() ?: return null
            val index = header.withIndex().associate { (i, name) -> name.trim().lowercase() to i }
            return CsvTable(index, raw.drop(1))
        }
    }
}
