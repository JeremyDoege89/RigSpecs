package dev.rigspecs.domain

import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.data.vin.VpicParser

/**
 * Parts portals that have no public API. The app can only open them with the rig's identifier
 * ready to paste (or filled in, when the department's URL template has a placeholder for it).
 * None of these portals documents a pre-filled search URL, so the defaults are their front
 * pages; templates are department settings and can be changed without an app update.
 */
enum class Portal(
    val id: String,
    val label: String,
    /** What the portal searches by, as the crew would call it. */
    val identifierLabel: String,
    val placeholder: String,
    val defaultUrl: String,
) {
    CUMMINS("cummins", "Cummins QuickServe", "ESN", "{esn}", "https://quickserve.cummins.com/"),
    PIERCE("pierce", "Pierceparts", "job number", "{job}", "https://www.pierceparts.com/"),
    FREIGHTLINER("freightliner", "Freightliner Excelerator", "VIN", "{vin}", "https://www.dtnaparts.com/excelerator/"),
    ;

    val urlKey: String get() = "portal.$id.url"
    val enabledKey: String get() = "portal.$id.enabled"

    /** Whether this portal covers the rig at all. */
    fun appliesTo(v: VehicleEntity): Boolean = when (this) {
        CUMMINS -> isCumminsEngine(v)
        PIERCE -> listOf(v.make, v.chassisMake, v.bodyBuilder).any { "pierce" in it.lowercase() }
        FREIGHTLINER -> listOf(v.make, v.chassisMake).any { val s = it.lowercase(); "freightliner" in s || "western star" in s }
    }

    /** The rig's value for [identifierLabel]; blank when nobody has entered it yet. */
    fun identifier(v: VehicleEntity): String = when (this) {
        CUMMINS -> v.engineSerial.trim()
        PIERCE -> v.builderJobNumber.trim()
        FREIGHTLINER -> v.vin.trim()
    }
}

/** Cummins engine families as they appear in engine fields and vPIC: ISL, X15, B6.7, QSB… */
private val CumminsFamily = Regex("""\b(IS[BCLMXZ]|QS[BCKLMXZ]|X1[0-5]|L9|L10|B6\.7|M11|N14)\b""", RegexOption.IGNORE_CASE)

private fun isCumminsEngine(v: VehicleEntity): Boolean {
    val decode = VpicParser.fields(v.vinDecodeJson)
    val text = listOf(v.engine, decode["EngineManufacturer"].orEmpty(), decode["EngineModel"].orEmpty()).joinToString(" ")
    return "cummins" in text.lowercase() || CumminsFamily.containsMatchIn(text)
}

/**
 * Fills a URL template: {esn}, {job}, {vin}, {make}, {model} and {year} become the rig's values,
 * URL-encoded. Unknown placeholders are left alone so a typo is visible, not silently dropped.
 */
fun expandPortalUrl(template: String, v: VehicleEntity): String {
    val values = mapOf(
        "{esn}" to v.engineSerial, "{job}" to v.builderJobNumber, "{vin}" to v.vin,
        "{make}" to v.make, "{model}" to v.model, "{year}" to (v.year?.toString() ?: ""),
    )
    return values.entries.fold(template.trim()) { url, (key, value) -> url.replace(key, urlEncode(value.trim())) }
}

/** RFC 3986 unreserved characters pass; everything else is percent-encoded as UTF-8. */
fun urlEncode(s: String): String = buildString {
    s.encodeToByteArray().forEach { b ->
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c)
        else append('%').append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1).uppercase())
    }
}

/** Only web links: a template must not be able to launch some other app or a local file. */
fun isAllowedPortalUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    return (u.startsWith("https://") || u.startsWith("http://")) && u.length > "https://".length + 3 && ' ' !in u
}
