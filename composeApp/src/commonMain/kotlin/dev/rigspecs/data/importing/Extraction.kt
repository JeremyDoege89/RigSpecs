package dev.rigspecs.data.importing

import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.Settings
import dev.rigspecs.domain.CandidateRow
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.SpecCategory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** What the model is told about the rig, so it can tell which column of a table applies. */
@Serializable
data class ExtractVehicle(
    val unit: String,
    val type: String,
    val year: Int? = null,
    val make: String = "",
    val model: String = "",
    val engine: String = "",
    val chassis: String = "",
    val body: String = "",
    /** The profile's existing fields, so extracted rows reuse their names and fill template blanks. */
    val fields: List<KnownField> = emptyList(),
)

/** A field the profile already has: a spec's component and property, or a part's component. */
@Serializable
data class KnownField(val kind: String, val component: String, val name: String = "")

/** Pages for one extraction call: part of a PDF, or photos. */
sealed interface ExtractInput {
    /** A standalone PDF holding [pages] (1-based, in the original document) in order. */
    class Pdf(val bytes: ByteArray, val pages: List<Int>) : ExtractInput

    /** One JPEG per page; a photo counts as page 1. */
    class Images(val images: List<Pair<Int, ByteArray>>) : ExtractInput

    val pageNumbers: List<Int>
        get() = when (this) {
            is Pdf -> pages
            is Images -> images.map { it.first }
        }
}

data class ExtractOutput(val rows: List<CandidateRow>, val model: String)

/** Why a call failed, and whether trying again (or with fewer pages) can help. */
sealed class ExtractionException(message: String) : Exception(message) {
    class NotConfigured : ExtractionException("Assisted import isn't set up on this device. Add the server in Settings.")
    class Offline : ExtractionException("No signal. The import picks up where it stopped when you resume it.")

    /** The pages held more rows than one answer can carry: retry with fewer pages. */
    class TooMuch : ExtractionException("Too much on these pages for one pass.")

    /** Worth retrying as is: rate limits, timeouts, an overloaded service. */
    class Temporary(message: String) : ExtractionException(message)

    /** Won't succeed on retry: bad key, rejected request, refused content. */
    class Permanent(message: String) : ExtractionException(message)
}

/** The extraction service. One implementation calls the department's server function. */
interface ExtractionService {
    suspend fun extract(vehicle: ExtractVehicle, input: ExtractInput, dense: Boolean): ExtractOutput

    /** Checks the server is reachable and set up, without spending anything on the model. */
    suspend fun ping(): PingResult
}

data class PingResult(val ok: Boolean, val message: String)

// Wire format, shared with supabase/functions/extract-specs. Field names are the JSON keys.

@Serializable
internal data class WireRequest(
    val vehicle: ExtractVehicle? = null,
    val document: WireDocument? = null,
    val dense: Boolean = false,
    val ping: Boolean = false,
)

@Serializable
internal data class WireDocument(
    /** "pdf" or "images". */
    val kind: String,
    val pages: List<Int> = emptyList(),
    val data: String? = null,
    val images: List<WireImage> = emptyList(),
)

@Serializable
internal data class WireImage(val page: Int, val mediaType: String, val data: String)

@Serializable
internal data class WireResponse(
    val rows: List<WireRow> = emptyList(),
    val model: String = "",
    val ok: Boolean? = null,
    val error: String? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
internal data class WireRow(
    val kind: String,
    val page: Int? = null,
    val category: String = "",
    val system: String = "",
    val component: String = "",
    val name: String = "",
    val value: String = "",
    val unit: String = "",
    @SerialName("oemNumber") val oemNumber: String = "",
    @SerialName("crossReferences") val crossReferences: List<String> = emptyList(),
    val quantity: Int? = null,
    val notes: String = "",
    val evidence: String = "",
)

internal val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal fun WireRow.toCandidate(): CandidateRow? {
    val kind = when (kind.lowercase()) {
        "spec" -> ImportRowKind.SPEC
        "part" -> ImportRowKind.PART
        else -> return null
    }
    return CandidateRow(
        kind = kind,
        component = component,
        name = name,
        value = value,
        unit = unit,
        category = SpecCategory.entries.firstOrNull { it.name == category.uppercase() },
        system = PartSystem.entries.firstOrNull { it.name == system.uppercase() },
        oemNumber = oemNumber,
        aftermarketNumbers = crossReferences,
        qtyPerVehicle = quantity?.takeIf { it > 0 },
        notes = notes,
        page = page,
        evidence = evidence,
    )
}

/** Calls the department's `extract-specs` function, which holds the model API key. */
class ServerExtractionService(
    private val client: HttpClient,
    private val settings: suspend () -> Settings,
) : ExtractionService {

    override suspend fun extract(vehicle: ExtractVehicle, input: ExtractInput, dense: Boolean): ExtractOutput {
        val document = when (input) {
            is ExtractInput.Pdf -> WireDocument(kind = "pdf", pages = input.pages, data = Base64.encode(input.bytes))
            is ExtractInput.Images -> WireDocument(
                kind = "images",
                images = input.images.map { (page, jpeg) -> WireImage(page, "image/jpeg", Base64.encode(jpeg)) },
            )
        }
        val response = post(WireRequest(vehicle = vehicle, document = document, dense = dense))
        return ExtractOutput(response.rows.mapNotNull { it.toCandidate() }, response.model)
    }

    override suspend fun ping(): PingResult = try {
        val r = post(WireRequest(ping = true))
        PingResult(r.ok == true, r.message ?: if (r.ok == true) "Connected" else "The server answered but isn't ready")
    } catch (e: ExtractionException) {
        PingResult(false, e.message ?: "Couldn't reach the server")
    }

    private suspend fun post(body: WireRequest): WireResponse {
        val s = settings()
        val url = s.extractUrl ?: throw ExtractionException.NotConfigured()
        if (s.serverKey.isBlank()) throw ExtractionException.NotConfigured()
        val response = try {
            client.post(url) {
                header("Authorization", "Bearer ${s.serverKey}")
                header("apikey", s.serverKey)
                if (s.importSecret.isNotBlank()) header("x-rigspecs-secret", s.importSecret)
                setBody(TextContent(WireJson.encodeToString(WireRequest.serializer(), body), ContentType.Application.Json))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw classifyNetworkError(e)
        }
        val text = response.bodyAsText()
        val parsed = runCatching { WireJson.decodeFromString(WireResponse.serializer(), text) }.getOrNull()
        val status = response.status.value
        if (status in 200..299 && parsed != null && parsed.error == null) return parsed
        val message = parsed?.error ?: "Server error $status"
        throw when {
            parsed?.code == "too_much_output" -> ExtractionException.TooMuch()
            // The function's own codes say when retrying can't help: a bad server key, refused or rejected pages.
            parsed?.code in setOf("server_config", "refused", "rejected") -> ExtractionException.Permanent(message)
            status == 401 || status == 403 -> ExtractionException.Permanent("The server refused this device's key ($status). Check Settings.")
            status == 404 -> ExtractionException.Permanent("No extract-specs function at this server address. Check Settings.")
            status == 413 -> ExtractionException.TooMuch()
            status == 408 || status == 429 || status >= 500 -> ExtractionException.Temporary(message)
            else -> ExtractionException.Permanent(message)
        }
    }

    private fun classifyNetworkError(e: Exception): ExtractionException = when {
        dev.rigspecs.data.vin.isOfflineError(e) -> ExtractionException.Offline()
        e is io.ktor.client.plugins.HttpRequestTimeoutException || dev.rigspecs.data.vin.isPlatformTimeout(e) ->
            ExtractionException.Temporary("The server took too long to answer.")
        else -> ExtractionException.Temporary(e.message ?: "Network error")
    }
}

/** Extraction reads several pages per call; the model can take a minute or two on dense ones. */
fun importHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 240_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 240_000
    }
    expectSuccess = false
}
