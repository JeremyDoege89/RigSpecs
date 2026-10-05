package dev.rigspecs.data.importing

import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.Settings
import dev.rigspecs.domain.Portal
import dev.rigspecs.domain.SpecCategory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerExtractionServiceTest {
    private val configured = Settings(
        portalUrls = Portal.entries.associateWith { it.defaultUrl },
        portalEnabled = Portal.entries.associateWith { true },
        importEnabled = true,
        serverUrl = "https://abc.supabase.co",
        serverKey = "anon-key",
        importSecret = "s3cret",
    )
    private val vehicle = ExtractVehicle(unit = "Engine 2", type = "Engine", year = 2019, make = "Pierce")
    private val json = io.ktor.http.ContentType.Application.Json

    private fun service(settings: Settings = configured, handler: MockRequestHandler) =
        ServerExtractionService(HttpClient(MockEngine(handler)), settings = { settings })

    @Test
    fun sendsPagesAndParsesRows() = runTest {
        var body = ""
        var headers = mapOf<String, String?>()
        var url = ""
        val s = service { req ->
                url = req.url.toString()
                headers = listOf("Authorization", "apikey", "x-rigspecs-secret").associateWith { req.headers[it] }
                body = (req.body as TextContent).text
                respond(
                    """{"model":"m-1","rows":[
                        {"kind":"spec","page":212,"category":"TORQUE","component":"Lug nuts","name":"Torque","value":"450","unit":"ft-lb","evidence":"Wheel nuts 450 ft-lb"},
                        {"kind":"part","page":213,"system":"ENGINE","component":"Oil filter","oemNumber":"LF9009","crossReferences":["NAPA 1792"],"quantity":0},
                        {"kind":"mystery","component":"?"}]}""",
                    HttpStatusCode.OK, headersOf("Content-Type", json.toString()),
                )
        }
        val out = s.extract(vehicle, ExtractInput.Pdf(byteArrayOf(1, 2, 3), listOf(212, 213)), dense = true)
        assertEquals("https://abc.supabase.co/functions/v1/extract-specs", url)
        assertEquals(mapOf("Authorization" to "Bearer anon-key", "apikey" to "anon-key", "x-rigspecs-secret" to "s3cret"), headers)
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("pdf", sent["document"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("AQID", sent["document"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals(listOf("212", "213"), sent["document"]!!.jsonObject["pages"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", sent["dense"]!!.jsonPrimitive.content)

        assertEquals("m-1", out.model)
        assertEquals(2, out.rows.size)
        val (lug, filter) = out.rows
        assertEquals(ImportRowKind.SPEC, lug.kind)
        assertEquals(SpecCategory.TORQUE, lug.category)
        assertEquals(212, lug.page)
        assertEquals(listOf("NAPA 1792"), filter.aftermarketNumbers)
        assertEquals(null, filter.qtyPerVehicle)
    }

    @Test
    fun errorsMapToWhatTheRunnerCanDo() = runTest {
        suspend fun outcome(status: HttpStatusCode, body: String) = runCatching {
            service { respond(body, status, headersOf("Content-Type", json.toString())) }
                .extract(vehicle, ExtractInput.Images(listOf(1 to byteArrayOf(9))), dense = false)
        }.exceptionOrNull()

        assertIs<ExtractionException.TooMuch>(outcome(HttpStatusCode.UnprocessableEntity, """{"error":"too long","code":"too_much_output"}"""))
        assertIs<ExtractionException.Temporary>(outcome(HttpStatusCode.TooManyRequests, """{"error":"Rate limited"}"""))
        assertIs<ExtractionException.Temporary>(outcome(HttpStatusCode.BadGateway, "<html>"))
        assertIs<ExtractionException.Permanent>(outcome(HttpStatusCode.Unauthorized, """{"error":"bad key"}"""))
        assertIs<ExtractionException.Permanent>(outcome(HttpStatusCode.BadRequest, """{"error":"Pages must be JPEG"}"""))
        assertIs<ExtractionException.TooMuch>(outcome(HttpStatusCode.PayloadTooLarge, ""))
        // A 500 is usually worth a retry, but not when the server says its own key is wrong.
        assertIs<ExtractionException.Permanent>(outcome(HttpStatusCode.InternalServerError, """{"error":"The server's model API key was rejected.","code":"server_config"}"""))
        assertIs<ExtractionException.Temporary>(outcome(HttpStatusCode.ServiceUnavailable, """{"error":"busy","code":"upstream"}"""))
        assertIs<ExtractionException.Permanent>(outcome(HttpStatusCode.UnprocessableEntity, """{"error":"declined","code":"refused"}"""))
    }

    @Test
    fun notConfiguredAndPing() = runTest {
        val bare = configured.copy(serverUrl = "")
        val e = runCatching { service(bare) { error("no call expected") }.extract(vehicle, ExtractInput.Images(emptyList()), false) }.exceptionOrNull()
        assertIs<ExtractionException.NotConfigured>(e)

        val ok = service { respond("""{"ok":true,"message":"Ready: m-1"}""", HttpStatusCode.OK, headersOf("Content-Type", json.toString())) }.ping()
        assertTrue(ok.ok)
        assertEquals("Ready: m-1", ok.message)
        val missingKey = service { respond("""{"ok":false,"message":"The model API key isn't set"}""", HttpStatusCode.OK, headersOf("Content-Type", json.toString())) }.ping()
        assertFalse(missingKey.ok)
    }
}
