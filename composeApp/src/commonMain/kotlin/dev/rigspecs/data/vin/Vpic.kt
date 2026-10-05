package dev.rigspecs.data.vin

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One NHTSA vPIC DecodeVinValues answer, reduced to what the app reads. */
sealed interface VpicDecode {
    data class Decoded(
        /** "FORD", or the manufacturer ("PIERCE MANUFACTURING INC.") when vPIC has no make. */
        val make: String,
        val model: String?,
        val modelYear: Int?,
        val vehicleType: String?,
        val bodyClass: String?,
        val engineManufacturer: String?,
        val engineModel: String?,
        val displacementL: String?,
        val engineHp: String?,
        /** Comma-separated codes such as "1,14"; partial decodes carry non-zero codes with good data. */
        val errorCode: String?,
        /** Results[0] verbatim, stored on the rig so more fields can be shown without a refetch. */
        val rawJson: String,
    ) : VpicDecode

    /** Neither make nor manufacturer: the VIN could not be decoded. */
    data object Empty : VpicDecode
}

/** HTTP status other than 2xx. */
class VpicHttpException(val status: Int) : Exception("vPIC HTTP $status")

/** The body was not the JSON vPIC sends. */
class VpicBodyException(message: String, cause: Throwable? = null) : Exception(message, cause)

object VpicParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Throws [VpicBodyException] when [body] isn't a vPIC JSON object. */
    fun parse(body: String): VpicDecode {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: IllegalArgumentException) {
            throw VpicBodyException("vPIC body is not JSON", e)
        } ?: throw VpicBodyException("vPIC body is not a JSON object")
        val first = (root["Results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return VpicDecode.Empty
        return parseResult(first)
    }

    /** Also used to re-read the JSON stored on a rig. */
    fun parseResult(first: JsonObject): VpicDecode {
        val f = fields(first)
        // Custom apparatus builders (Pierce, for one) come back with a manufacturer but no make.
        val make = f["Make"] ?: f["Manufacturer"] ?: return VpicDecode.Empty
        return VpicDecode.Decoded(
            make = make,
            model = f["Model"],
            modelYear = f["ModelYear"]?.toIntOrNull(),
            vehicleType = f["VehicleType"],
            bodyClass = f["BodyClass"],
            engineManufacturer = f["EngineManufacturer"],
            engineModel = f["EngineModel"],
            displacementL = f["DisplacementL"],
            engineHp = f["EngineHP"],
            errorCode = f["ErrorCode"],
            rawJson = first.toString(),
        )
    }

    /** Non-blank string fields of a stored Results[0]; "Not Applicable" counts as blank. */
    fun fields(rawJson: String): Map<String, String> =
        runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()?.let(::fields).orEmpty()

    private fun fields(obj: JsonObject): Map<String, String> = obj.mapNotNull { (k, v) ->
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeUnless { it.isEmpty() || it.equals("Not Applicable", ignoreCase = true) }
        s?.let { k to it }
    }.toMap()
}

/** The network side of a decode, so tests can swap it out. Returns the response body. */
fun interface VpicRemote {
    suspend fun fetch(vin: String, modelYear: Int?): String
}

class KtorVpicRemote(
    private val client: HttpClient,
    private val baseUrl: String = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/",
) : VpicRemote {
    override suspend fun fetch(vin: String, modelYear: Int?): String {
        val response = client.get(baseUrl + vin.encodeURLPathPart()) {
            parameter("format", "json")
            modelYear?.let { parameter("modelyear", it) }
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) throw VpicHttpException(response.status.value)
        return response.bodyAsText()
    }
}

/** The platform's default engine (OkHttp on Android and desktop). Retries live in [VinDecoder]. */
fun vpicHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 10_000
    }
    expectSuccess = false
}
