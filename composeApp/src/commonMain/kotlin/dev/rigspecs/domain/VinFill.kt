package dev.rigspecs.domain

import dev.rigspecs.data.vin.VpicDecode
import dev.rigspecs.data.vin.VpicParser

/**
 * Turns a vPIC decode into form values. Only blank fields are filled; anything typed stays.
 * On apparatus and ambulances the VIN describes the chassis (a Ford E-450 under a box body, a
 * Pierce or Spartan custom cab), so the decoded make also goes into Chassis make.
 */
object VinFill {

    /** Values for fields that were blank; null = leave the field alone. */
    data class Fill(
        val year: Int? = null,
        val make: String? = null,
        val model: String? = null,
        val engine: String? = null,
        val chassisMake: String? = null,
        /** Set when the decode clearly says what kind of rig this is and the user hasn't picked one. */
        val type: VehicleType? = null,
    ) {
        val isEmpty: Boolean get() = this == Fill()
    }

    /** What the decode says, for the confirm card. Not limited to blank fields. */
    data class Summary(
        val year: Int?,
        val make: String,
        val model: String?,
        val engine: String?,
        val bodyClass: String?,
        val type: VehicleType?,
    )

    fun summarize(d: VpicDecode.Decoded) = Summary(
        year = d.modelYear,
        make = displayMake(d.make),
        model = displayModel(d.model),
        engine = engineText(d),
        bodyClass = d.bodyClass,
        type = suggestedType(d),
    )

    fun fill(
        d: VpicDecode.Decoded,
        type: VehicleType,
        typeChosen: Boolean,
        year: String,
        make: String,
        model: String,
        engine: String,
        chassisMake: String,
    ): Fill {
        val s = summarize(d)
        val finalType = s.type.takeIf { !typeChosen } ?: type
        return Fill(
            year = s.year.takeIf { year.isBlank() },
            make = s.make.takeIf { make.isBlank() },
            model = s.model.takeIf { model.isBlank() },
            engine = s.engine.takeIf { engine.isBlank() },
            chassisMake = s.make.takeIf { chassisMake.isBlank() && finalType != VehicleType.CAR },
            type = s.type.takeIf { !typeChosen && it != type },
        )
    }

    /** CAR for passenger cars and SUVs, AMBULANCE when the body class says so; else no opinion. */
    fun suggestedType(d: VpicDecode.Decoded): VehicleType? {
        val body = d.bodyClass.orEmpty().lowercase()
        val vt = d.vehicleType.orEmpty().lowercase()
        return when {
            "ambulance" in body -> VehicleType.AMBULANCE
            "passenger car" in vt || "multipurpose passenger" in vt -> VehicleType.CAR
            else -> null
        }
    }

    /** "Ford 5.0L-4V", "Detroit Diesel DD15 14.8L", "Cummins ISL 350 hp". Null when vPIC has nothing. */
    fun engineText(d: VpicDecode.Decoded): String? {
        val parts = mutableListOf<String>()
        val model = d.engineModel?.trim().orEmpty()
        d.engineManufacturer?.trim()?.takeIf { it.isNotEmpty() && !model.contains(it, ignoreCase = true) }?.let { parts += it }
        if (model.isNotEmpty()) parts += model
        d.displacementL?.toDoubleOrNull()?.let { l ->
            val text = formatLitres(l)
            if (!model.contains(text.removeSuffix("L"))) parts += text
        }
        d.engineHp?.toDoubleOrNull()?.let { parts += "${it.toInt()} hp" }
        return parts.joinToString(" ").ifEmpty { null }
    }

    private fun formatLitres(l: Double): String {
        val tenths = kotlin.math.round(l * 10).toLong()
        return "${tenths / 10}.${tenths % 10}L"
    }

    /** Generic placeholders vPIC uses instead of a model name. */
    private val GenericModels = setOf("truck", "chassis", "incomplete vehicle")

    fun displayModel(model: String?): String? =
        model?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in GenericModels }

    /** Corporate words that aren't part of the name a crew would use. */
    private val Suffixes = setOf(
        "inc", "llc", "corp", "corporation", "co", "company", "manufacturing", "mfg", "ltd", "limited",
        "motor", "motors", "chassis", "truck", "trucks", "group", "industries", "north", "america", "usa",
    )

    /** Spellings crews and parts desks use, for makes title case would get wrong. */
    private val KnownMakes = listOf(
        "E-One", "KME", "GMC", "BMW", "AEV", "REV", "Ram", "Pierce", "Spartan", "Seagrave", "Sutphen",
        "Ferrara", "Rosenbauer", "Smeal", "Freightliner", "International", "Ford", "Chevrolet", "Dodge",
        "Horton", "Braun", "Demers", "Wheeled Coach", "Road Rescue", "Mercedes-Benz", "Kenworth", "Peterbilt",
        "Mack", "Volvo", "Toyota", "Honda", "Hino", "Isuzu",
    )

    /**
     * "PIERCE MANUFACTURING INC." -> "Pierce", "SPARTAN MOTORS CHASSIS" -> "Spartan",
     * "E-ONE, INC." -> "E-One", "GMC" -> "GMC".
     */
    fun displayMake(decoded: String): String {
        var words = decoded.substringBefore(',').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.firstOrNull().equals("the", ignoreCase = true)) words = words.drop(1)
        while (words.size > 1 && words.last().lowercase().trimEnd('.') in Suffixes) words = words.dropLast(1)
        val cleaned = words.joinToString(" ")
        val key = matchKey(cleaned)
        return KnownMakes.firstOrNull { matchKey(it) == key } ?: titleCase(cleaned)
    }

    /** Lowercase letters and digits only: "E-ONE" and "E One" both give "eone". */
    fun matchKey(s: String): String = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /** "BIG TEX" -> "Big Tex"; vowel-less words stay upper case (KME, GMC). */
    fun titleCase(s: String): String = s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
        if (word.none { it.lowercaseChar() in "aeiouy" }) word.uppercase()
        else word.lowercase().split('-').joinToString("-") { part -> capitalizeFirstLetter(part) }
    }
}

private fun capitalizeFirstLetter(s: String): String {
    val i = s.indexOfFirst(Char::isLetter)
    return if (i < 0) s else s.substring(0, i) + s[i].uppercaseChar() + s.substring(i + 1)
}

/** Labelled extras from the vPIC JSON stored on a rig, for the Overview tab. */
fun vinDecodeDetails(rawJson: String): List<Pair<String, String>> {
    if (rawJson.isBlank()) return emptyList()
    val f = VpicParser.fields(rawJson)
    // "UNITED STATES (USA)" reads better without the code.
    val plant = listOfNotNull(f["PlantCity"], f["PlantState"], f["PlantCountry"]?.substringBefore(" (")?.ifBlank { null })
        .joinToString(", ") { VinFill.titleCase(it) }.ifEmpty { null }
    return listOfNotNull(
        f["Manufacturer"]?.let { "Manufacturer" to it },
        f["BodyClass"]?.let { "Body class" to it },
        f["GVWR"]?.let { "GVWR" to it },
        f["Series"]?.let { "Series" to it },
        f["Trim"]?.let { "Trim" to it },
        f["DriveType"]?.let { "Drive" to it },
        f["FuelTypePrimary"]?.let { "Fuel" to it },
        f["BrakeSystemType"]?.let { "Brakes" to it },
        f["TransmissionStyle"]?.let { "Transmission" to it },
        plant?.let { "Built in" to it },
    )
}
