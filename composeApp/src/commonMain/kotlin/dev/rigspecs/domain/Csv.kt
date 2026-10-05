package dev.rigspecs.domain

import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity
import kotlin.time.Instant

/**
 * RFC 4180 CSV: quoted fields may hold commas, quotes ("") and line breaks; a UTF-8 byte-order
 * mark (Excel adds one) is ignored; lines may end in CRLF or LF. Trailing blank lines are dropped.
 */
fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = if (text.startsWith('﻿')) 1 else 0
    fun endField() {
        row += field.toString()
        field.clear()
    }
    fun endRow() {
        endField()
        rows += row.toList()
        row.clear()
    }
    while (i < text.length) {
        val c = text[i]
        if (quoted) {
            when {
                c == '"' && text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> quoted = false
                else -> field.append(c)
            }
        } else {
            when (c) {
                '"' -> quoted = true
                ',' -> endField()
                '\r' -> if (text.getOrNull(i + 1) != '\n') endRow()
                '\n' -> endRow()
                else -> field.append(c)
            }
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows.dropLastWhile { r -> r.all { it.isBlank() } }
}

/** CSV text with CRLF line endings (what spreadsheets expect), quoting only where needed. */
fun writeCsv(rows: List<List<String>>): String = buildString {
    rows.forEach { r ->
        append(r.joinToString(",") { f -> if (f.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${f.replace("\"", "\"\"")}\"" else f })
        append("\r\n")
    }
}

/** The columns of a rig's CSV, in order. Import reads them by name, so a spreadsheet may reorder or drop the extras. */
val RigCsvColumns = listOf(
    "kind", "category", "system", "component", "property", "value", "unit", "oem_number", "cross_references",
    "quantity", "notes", "safety_critical", "status", "source", "page", "verified_by", "verified_at",
)

/**
 * One rig's specs and parts as CSV, blanks included: export a rig, fill in the blanks in a
 * spreadsheet, and import it back. Cross-references are joined with "; ".
 */
fun exportRigCsv(specs: List<SpecItemEntity>, parts: List<PartEntity>, documents: Map<String, DocumentEntity>): String {
    fun stamp(at: Long?) = at?.let { Instant.fromEpochMilliseconds(it).toString() }.orEmpty()
    val rows = mutableListOf(RigCsvColumns)
    specs.filter { !it.deleted }.sortedBy { it.sortOrder }.forEach { s ->
        rows += listOf(
            "spec", s.category.name, "", s.component, s.name, s.value, s.unit, "", "", "", s.notes,
            if (s.safetyCritical) "yes" else "", s.status.name.lowercase(), s.sourceDocId?.let(documents::get)?.title.orEmpty(),
            s.pageHint, s.verifiedBy, stamp(s.verifiedAt),
        )
    }
    parts.filter { !it.deleted }.sortedBy { it.sortOrder }.forEach { p ->
        rows += listOf(
            "part", "", p.system.name, p.component, "", "", "", p.oemNumber,
            p.aftermarketNumbers.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; "),
            p.qtyPerVehicle?.toString().orEmpty(), p.notes, "", p.status.name.lowercase(),
            p.sourceDocId?.let(documents::get)?.title.orEmpty(), p.pageHint, p.verifiedBy, stamp(p.verifiedAt),
        )
    }
    return writeCsv(rows)
}

/** One line of an imported CSV: a usable row, a blank to skip, or an error to show. */
sealed interface CsvLine {
    /** 1-based line number in the file, counting the header, as a spreadsheet shows it. */
    val line: Int

    data class Row(override val line: Int, val row: CandidateRow) : CsvLine
    data class Blank(override val line: Int) : CsvLine
    data class Error(override val line: Int, val message: String) : CsvLine
}

/** A parsed CSV, or why the whole file can't be used. */
data class CsvImport(val lines: List<CsvLine>, val fileError: String? = null) {
    val rows: List<CsvLine.Row> get() = lines.filterIsInstance<CsvLine.Row>()
    val errors: List<CsvLine.Error> get() = lines.filterIsInstance<CsvLine.Error>()
    val blanks: Int get() = lines.count { it is CsvLine.Blank }
}

/** Header words people use for the same column, mapped to ours. */
private val ColumnAliases = mapOf(
    "kind" to "kind",
    "category" to "category",
    "system" to "system",
    "component" to "component", "item" to "component", "part" to "component",
    "property" to "property", "name" to "property",
    "value" to "value",
    "unit" to "unit", "units" to "unit",
    "oem_number" to "oem_number", "oem" to "oem_number", "part_number" to "oem_number", "oem_part_number" to "oem_number",
    "cross_references" to "cross_references", "cross_refs" to "cross_references", "aftermarket" to "cross_references",
    "quantity" to "quantity", "qty" to "quantity",
    "notes" to "notes", "note" to "notes",
    "safety_critical" to "safety_critical", "safety" to "safety_critical",
)

/**
 * Reads a rig CSV (ours, or a spreadsheet with recognisable headers) into rows to import. A row
 * whose value or part numbers are empty is a blank (as exported from a template) and is skipped.
 * `kind` may be left out: rows with a part number are parts, the rest specs.
 */
fun parseRigCsv(text: String): CsvImport {
    val table = parseCsv(text)
    if (table.isEmpty()) return CsvImport(emptyList(), "The file is empty.")
    val header = table.first().map { h -> ColumnAliases[h.trim().lowercase().replace(' ', '_')] }
    if ("component" !in header) return CsvImport(emptyList(), "No component column. The first row must name the columns, like an exported rig.")
    if ("value" !in header && "oem_number" !in header && "cross_references" !in header) {
        return CsvImport(emptyList(), "No value or part number column, so there's nothing to import.")
    }
    val lines = table.drop(1).mapIndexedNotNull { i, cells ->
        val line = i + 2
        if (cells.all { it.isBlank() }) return@mapIndexedNotNull null
        fun col(name: String) = header.indexOf(name).takeIf { it >= 0 }?.let { cells.getOrNull(it)?.trim() }.orEmpty()
        val oem = col("oem_number")
        val refs = col("cross_references").split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val kind = when (col("kind").lowercase()) {
            "spec", "specs" -> ImportRowKind.SPEC
            "part", "parts" -> ImportRowKind.PART
            "" -> if (oem.isNotEmpty() || refs.isNotEmpty() || col("system").isNotEmpty()) ImportRowKind.PART else ImportRowKind.SPEC
            else -> return@mapIndexedNotNull CsvLine.Error(line, "Kind must be spec or part, not \"${col("kind")}\".")
        }
        val component = col("component")
        if (component.isEmpty()) return@mapIndexedNotNull CsvLine.Error(line, "Component is empty.")
        val qtyText = col("quantity")
        val qty = qtyText.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return@mapIndexedNotNull CsvLine.Error(line, "Quantity \"$it\" isn't a whole number.") }
        val safety = col("safety_critical").lowercase() in setOf("yes", "y", "true", "1", "x")
        when (kind) {
            ImportRowKind.SPEC -> {
                val value = col("value")
                val property = col("property")
                if (value.isEmpty()) return@mapIndexedNotNull CsvLine.Blank(line)
                if (property.isEmpty()) return@mapIndexedNotNull CsvLine.Error(line, "Property is empty (Capacity, Torque, Type…).")
                val categoryText = col("category")
                val category = if (categoryText.isEmpty()) SpecCategory.OTHER else SpecCategory.entries.firstOrNull {
                    it.name.equals(categoryText, true) || it.label.equals(categoryText, true)
                } ?: return@mapIndexedNotNull CsvLine.Error(line, "Category \"$categoryText\" isn't one of ${SpecCategory.entries.joinToString { it.label }}.")
                CsvLine.Row(line, CandidateRow(ImportRowKind.SPEC, component, property, value, col("unit"), category = category, notes = col("notes"), safetyCritical = safety))
            }
            ImportRowKind.PART -> {
                if (oem.isEmpty() && refs.isEmpty()) return@mapIndexedNotNull CsvLine.Blank(line)
                val systemText = col("system")
                val system = if (systemText.isEmpty()) PartSystem.OTHER else PartSystem.entries.firstOrNull {
                    it.name.equals(systemText, true) || it.label.equals(systemText, true)
                } ?: return@mapIndexedNotNull CsvLine.Error(line, "System \"$systemText\" isn't one of ${PartSystem.entries.joinToString { it.label }}.")
                CsvLine.Row(line, CandidateRow(ImportRowKind.PART, component, oemNumber = oem, aftermarketNumbers = refs, system = system, qtyPerVehicle = qty, notes = col("notes")))
            }
        }
    }
    return CsvImport(lines)
}

/** A file name for a rig's export: "Engine 2" becomes "Engine-2-specs.csv". */
fun csvFileName(unitNumber: String): String =
    unitNumber.trim().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "rig" } + "-specs.csv"
