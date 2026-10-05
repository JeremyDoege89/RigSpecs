package dev.rigspecs.domain

import dev.rigspecs.data.VehicleEntity

data class FleetFilter(
    val query: String = "",
    val types: Set<VehicleType> = emptySet(),
    val stations: Set<String> = emptySet(),
)

/**
 * Rigs matching [filter]. Every word of the query must appear in one of the rig's identifying
 * fields, so "medic 3" and "3 medic" both find Medic 3. Empty type/station sets mean "any".
 */
fun List<VehicleEntity>.filterFleet(filter: FleetFilter): List<VehicleEntity> {
    val words = filter.query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return filter { v ->
        (filter.types.isEmpty() || v.type in filter.types) &&
            (filter.stations.isEmpty() || v.station.trim() in filter.stations) &&
            words.all { w -> v.searchText().contains(w) }
    }
}

private fun VehicleEntity.searchText(): String =
    listOf(
        unitNumber, type.label, vin, year?.toString().orEmpty(), make, model, chassisMake,
        bodyBuilder, builderJobNumber, engine, station,
    ).joinToString(" ").lowercase()

/** Distinct, non-blank stations in natural order ("Station 2" before "Station 10"). */
fun List<VehicleEntity>.stations(): List<String> =
    map { it.station.trim() }.filter { it.isNotEmpty() }.distinct().sortedWith(naturalOrder)

val naturalOrder: Comparator<String> = Comparator { a, b ->
    val re = Regex("\\d+|\\D+")
    val pa = re.findAll(a.lowercase()).map { it.value }.toList()
    val pb = re.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(pa.size, pb.size)) {
        val x = pa[i]
        val y = pb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 }
                ?: x.trimStart('0').compareTo(y.trimStart('0'))
        } else {
            x.compareTo(y)
        }
        if (c != 0) return@Comparator c
    }
    pa.size.compareTo(pb.size)
}
