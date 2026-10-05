package dev.rigspecs.domain

import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity

/** A spec or part that matched a search, with how well it matched. */
data class SearchHit(val spec: SpecItemEntity? = null, val part: PartEntity? = null, val score: Int) {
    val isUnverified: Boolean
        get() = (spec?.status ?: part?.status) == VerificationStatus.UNVERIFIED
}

/** Search results for one rig: whether the rig itself matched, and its matching specs and parts. */
data class SearchGroup(val vehicle: VehicleEntity, val rigMatches: Boolean, val hits: List<SearchHit>)

/**
 * Searches every rig's specs and parts. Every word of the query has to match somewhere in the
 * rig's own details or the item's ("oil filter medic 3"), and at least one word has to match the
 * item itself, so a query that only names a rig finds the rig, not each of its 40 fields.
 *
 * A query that contains a rig's unit number ("engine 2 oil", "engine2 oil") is about that rig
 * alone, and the rest of the query picks its items. Without this, "engine 2" would list the engine
 * oil and engine parts of every rig with a 2 anywhere in its details, such as Station 2. Only unit
 * numbers with a number in them count, so a rig called just "Air" doesn't take over "air filter".
 *
 * Words match the start of a word ("lug" finds "Lug nuts"); numbers match whole numbers only, so
 * "Medic 3" doesn't also find Medic 30. Punctuation is ignored, so "LF-9009" finds "LF9009".
 * Blank fields aren't searched: crews look for values.
 */
class SearchIndex(vehicles: List<VehicleEntity>, specs: List<SpecItemEntity>, parts: List<PartEntity>) {

    private class Entry(val spec: SpecItemEntity?, val part: PartEntity?, val vehicleId: String, val tokens: Set<String>)

    private val rigs = vehicles.filter { !it.deleted }.associateBy { it.id }
    private val rigTokens: Map<String, Set<String>> = rigs.mapValues { (_, v) ->
        searchTokens(
            v.unitNumber, v.type.label, v.vin, v.year?.toString().orEmpty(), v.make, v.model, v.chassisMake,
            v.bodyBuilder, v.builderJobNumber, v.engine, v.engineSerial, v.station,
        )
    }
    /** Each rig's unit number in words: "Engine 2" gives engine, 2. */
    private val unitWords: Map<String, List<String>> = rigs.mapValues { (_, v) ->
        v.unitNumber.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }.filterValues { words -> words.any { w -> w.any { it.isDigit() } } }
    private val entries: List<Entry> =
        specs.filter { !it.deleted && it.value.isNotBlank() && it.vehicleId in rigs }.map {
            Entry(it, null, it.vehicleId, searchTokens(it.component, it.name, it.value, it.unit, it.notes, it.category.label))
        } + parts.filter { !it.deleted && it.vehicleId in rigs && (it.oemNumber.isNotBlank() || it.aftermarketNumbers.isNotBlank()) }.map {
            Entry(null, it, it.vehicleId, searchTokens(it.component, it.oemNumber, it.aftermarketNumbers, it.supplier, it.notes, it.system.label))
        }

    fun search(query: String, limit: Int = 200): List<SearchGroup> {
        val words = queryWords(query)
        if (words.isEmpty()) return emptyList()
        val named = mutableSetOf<String>()
        val nameWords = mutableSetOf<Int>()
        unitWords.forEach { (id, unit) ->
            unitIn(words, unit)?.let {
                named += id
                nameWords += it
            }
        }
        val itemWords = words.filterIndexed { i, _ -> i !in nameWords }
        val rigMatches = named.ifEmpty { rigTokens.filterValues { tokens -> words.all { matches(it, tokens) } }.keys }
        val candidates = when {
            itemWords.isEmpty() -> emptyList()
            named.isEmpty() -> entries
            else -> entries.filter { it.vehicleId in named }
        }
        val hits = candidates.mapNotNull { e ->
            val rig = rigTokens.getValue(e.vehicleId)
            var score = 0
            var onItem = false
            for (w in itemWords) {
                score += when {
                    e.tokens.contains(w) -> 3.also { onItem = true }
                    matches(w, e.tokens) -> 2.also { onItem = true }
                    matches(w, rig) -> 1
                    else -> return@mapNotNull null
                }
            }
            if (onItem) e to score else null
        }.take(limit)
        val byRig = hits.groupBy { it.first.vehicleId }
        return (byRig.keys + rigMatches).distinct().map { id ->
            val group = byRig[id].orEmpty()
                .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.spec?.sortOrder ?: it.first.part?.sortOrder ?: 0 })
                .map { (e, s) -> SearchHit(e.spec, e.part, s) }
            SearchGroup(rigs.getValue(id), id in rigMatches, group)
        }.sortedWith(
            compareByDescending<SearchGroup> { g -> g.hits.maxOfOrNull { it.score } ?: 0 }
                .thenComparator { a, b -> naturalOrder.compare(a.vehicle.unitNumber, b.vehicle.unitNumber) },
        )
    }

    private fun matches(word: String, tokens: Set<String>): Boolean =
        if (word.all { it.isDigit() }) word in tokens else tokens.any { it.startsWith(word) }

    /** Where the query spells out a unit number, word for word or run together; null if it doesn't. */
    private fun unitIn(words: List<String>, unit: List<String>): IntRange? {
        val joined = unit.joinToString("")
        words.indices.forEach { i ->
            if (words[i] == joined) return i..i
            val end = i + unit.size - 1
            if (end < words.size && unit.indices.all { k -> words[i + k] == unit[k] }) return i..end
        }
        return null
    }
}

/** Query words with punctuation dropped: "LF-9009" becomes "lf9009", "15W-40" becomes "15w40". */
fun queryWords(query: String): List<String> =
    query.lowercase().split(Regex("\\s+")).map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }

/**
 * Words to match against: each word lower-cased with its punctuation dropped ("LF-9009" gives
 * "lf9009"), plus the pieces between the punctuation ("lf", "9009"), so either spelling matches.
 */
fun searchTokens(vararg fields: String): Set<String> = buildSet {
    fields.forEach { field ->
        field.lowercase().split(Regex("\\s+")).forEach { chunk ->
            val joined = chunk.filter { it.isLetterOrDigit() }
            if (joined.isNotEmpty()) add(joined)
            chunk.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.forEach { add(it) }
        }
    }
}

/** The profile tab a spec shows on. */
fun SpecItemEntity.profileTab(): ProfileTab =
    ProfileTab.entries.firstOrNull { category in it.categories } ?: ProfileTab.OVERVIEW
