package dev.rigspecs.domain

import dev.rigspecs.data.ImportRowEntity
import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity

/**
 * Brakes, wheels, aerial and pump: values that hurt someone when wrong. They keep a
 * "verify against the manual" badge even after confirmation and need a source document.
 */
private val SafetyWords = listOf(
    "brake", "lug", "wheel", "rim", "hub", "aerial", "ladder", "turntable", "outrigger", "stabilizer",
    "jack", "pump",
)

/** Word starts only, so "Rims" counts but "Primary fuel filter" and "Trim" don't. */
fun isSafetyCritical(component: String, name: String = ""): Boolean {
    val words = Regex("[a-z]+").findAll("$component $name".lowercase()).map { it.value }
    return words.any { w -> SafetyWords.any { w.startsWith(it) } }
}

/** Letters and digits only, lower case: "Lug nuts" and "lug-nuts" compare equal. */
fun matchKey(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

/** "450 ft-lb" and "450ft-lb" compare equal; so do "15W-40" and "15w40". */
private fun valueKey(value: String, unit: String) = matchKey(value) + "|" + matchKey(unit)

/** Part numbers compare without spaces, dashes or case: "LF-9009" equals "lf9009". */
fun partNumberKey(s: String): String = matchKey(s).uppercase()

/** How a staged import row relates to what the profile already has. */
sealed interface ImportMatch {
    /** Nothing like it in the profile: confirming adds a new row. */
    data object New : ImportMatch

    /** A blank with the same name (usually from a template): confirming fills it in. */
    data class FillsBlank(val targetId: String) : ImportMatch

    /** The profile already says the same thing: confirming only adds the source. */
    data class Same(val targetId: String) : ImportMatch

    /** The profile says something else: the editor chooses which to keep. */
    data class Conflict(val targetId: String, val current: String) : ImportMatch
}

fun matchImportRow(row: ImportRowEntity, specs: List<SpecItemEntity>, parts: List<PartEntity>): ImportMatch =
    when (row.kind) {
        ImportRowKind.SPEC -> {
            val target = specs.firstOrNull {
                !it.deleted && matchKey(it.component) == matchKey(row.component) && matchKey(it.name) == matchKey(row.name)
            }
            when {
                target == null -> ImportMatch.New
                target.value.isBlank() -> ImportMatch.FillsBlank(target.id)
                valueKey(target.value, target.unit) == valueKey(row.value, row.unit) -> ImportMatch.Same(target.id)
                else -> ImportMatch.Conflict(target.id, target.displayValue())
            }
        }
        ImportRowKind.PART -> {
            val target = parts.firstOrNull { !it.deleted && matchKey(it.component) == matchKey(row.component) }
            val rowNumbers = partNumbers(row.oemNumber, row.aftermarketNumbers)
            val targetNumbers = target?.let { partNumbers(it.oemNumber, it.aftermarketNumbers) }.orEmpty()
            when {
                target == null -> ImportMatch.New
                targetNumbers.isEmpty() -> ImportMatch.FillsBlank(target.id)
                rowNumbers.isNotEmpty() && targetNumbers.containsAll(rowNumbers) -> ImportMatch.Same(target.id)
                else -> ImportMatch.Conflict(target.id, target.copyText())
            }
        }
    }

private fun partNumbers(oem: String, crossRefs: String): Set<String> =
    (listOf(oem) + crossRefs.lines()).map(::partNumberKey).filter { it.isNotEmpty() }.toSet()

/**
 * Identity of an extracted row's content, for spotting the same row in a re-run: what it is
 * and what it says, ignoring page, wording of notes and formatting.
 */
fun ImportRowEntity.contentKey(): String = when (kind) {
    ImportRowKind.SPEC -> "S|" + matchKey(component) + "|" + matchKey(name) + "|" + valueKey(value, unit)
    ImportRowKind.PART -> "P|" + matchKey(component) + "|" +
        partNumbers(oemNumber, aftermarketNumbers).sorted().joinToString(",")
}

/** "p. 212" for a 1-based page number. */
fun pageHint(page: Int?): String = page?.let { "p. $it" }.orEmpty()

/** What a staged row says, for the review list: "450 ft-lb" or "LF9009 · NAPA 1792". */
fun ImportRowEntity.displayValue(): String = when (kind) {
    ImportRowKind.SPEC -> if (unit.isBlank()) value.trim() else "${value.trim()} ${unit.trim()}"
    ImportRowKind.PART -> (listOf(oemNumber) + aftermarketNumbers.lines()).map { it.trim() }
        .filter { it.isNotEmpty() }.joinToString(" · ")
}
