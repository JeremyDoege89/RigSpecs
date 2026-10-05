package dev.rigspecs.domain

/**
 * Checks a 17-character VIN before it is sent to the decoder. Validation gates the Decode
 * button only: saving a rig accepts any VIN text, including older serials shorter than 17.
 */
object VinValidator {

    sealed interface Result {
        val vin: String

        data class Valid(override val vin: String) : Result

        /** Not decodable; [reason] is shown under the field and no request is made. */
        data class BadFormat(override val vin: String, val reason: String) : Result

        /** A warning only: plenty of real VINs, imports especially, fail the check digit. */
        data class BadCheckDigit(override val vin: String) : Result
    }

    const val LENGTH = 17

    private val FORMAT = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    /** Uppercase with all whitespace removed. */
    fun normalize(raw: String): String = raw.filterNot(Char::isWhitespace).uppercase()

    fun validate(raw: String): Result {
        val vin = normalize(raw)
        if (vin.length != LENGTH) {
            return Result.BadFormat(vin, "A VIN has $LENGTH characters; this one has ${vin.length}.")
        }
        if (!FORMAT.matches(vin)) {
            val reason = if (vin.any { it in "IOQ" }) "VINs never use I, O or Q." else "VINs use only letters and digits."
            return Result.BadFormat(vin, reason)
        }
        return if (vin[8] == checkDigit(vin)) Result.Valid(vin) else Result.BadCheckDigit(vin)
    }

    fun isWellFormed(raw: String): Boolean = validate(raw) !is Result.BadFormat

    /** The expected 9th character of a well-formed [vin]: weighted sum mod 11, 10 written as X. */
    fun checkDigit(vin: String): Char {
        val sum = vin.withIndex().sumOf { (i, c) -> value(c) * WEIGHTS[i] }
        val r = sum % 11
        return if (r == 10) 'X' else '0' + r
    }

    private fun value(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'H' -> c - 'A' + 1
        in 'J'..'N' -> c - 'J' + 1
        'P' -> 7
        'R' -> 9
        in 'S'..'Z' -> c - 'S' + 2
        else -> 0
    }
}

/**
 * Pulls a VIN out of whatever a barcode on a door-jamb or windshield sticker holds. Code 39
 * VIN labels often carry an extra leading "I" (import) or trailing character, and 2D codes may
 * wrap the VIN in other text, so this looks for a well-formed 17-character run, preferring one
 * whose check digit is right. Null when nothing looks like a VIN.
 */
fun extractVin(barcode: String): String? {
    val text = barcode.uppercase()
    val candidates = Regex("[A-HJ-NPR-Z0-9]{17,}").findAll(text)
        .flatMap { m -> (0..m.value.length - VinValidator.LENGTH).asSequence().map { m.value.substring(it, it + VinValidator.LENGTH) } }
        .toList()
    return candidates.firstOrNull { VinValidator.validate(it) is VinValidator.Result.Valid }
        ?: candidates.firstOrNull()
}
