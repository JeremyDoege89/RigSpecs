package dev.rigspecs.data.vin

import dev.rigspecs.domain.VehicleType
import dev.rigspecs.domain.VinFill
import dev.rigspecs.domain.VinValidator
import dev.rigspecs.domain.extractVin
import dev.rigspecs.domain.vinDecodeDetails
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun decoded(json: String) = VpicParser.parse(json) as VpicDecode.Decoded

class VinValidatorTest {
    @Test
    fun validCheckDigitAndWarnings() {
        assertIs<VinValidator.Result.Valid>(VinValidator.validate(" 1ftfw1e56pfa00000 "))
        assertIs<VinValidator.Result.BadCheckDigit>(VinValidator.validate("1FTFW1E50PFA00000"))
        assertEquals('X', VinValidator.checkDigit("4P1CT02SXKA000000"))
    }

    @Test
    fun badFormatReasons() {
        val short = VinValidator.validate("1FTFW1E5")
        assertIs<VinValidator.Result.BadFormat>(short)
        assertTrue("8" in short.reason)
        val ioq = VinValidator.validate("1FTFW1E56PFO00000")
        assertIs<VinValidator.Result.BadFormat>(ioq)
        assertTrue("I, O or Q" in ioq.reason)
    }

    @Test
    fun barcodeExtraction() {
        assertEquals("1FTFW1E56PFA00000", extractVin("1FTFW1E56PFA00000"))
        // Code 39 import prefix, and a VIN wrapped in other label text.
        assertEquals("1FTFW1E56PFA00000", extractVin("I1FTFW1E56PFA00000"))
        assertEquals("1FDXE4FN8LDC00000", extractVin("VIN:1FDXE4FN8LDC00000;MFG 05/20"))
        // No valid check digit anywhere: still the first VIN-shaped run.
        assertEquals("4P1CT02S5KA000000", extractVin("4P1CT02S5KA000000"))
        assertNull(extractVin("https://example.com/manual"))
        assertNull(extractVin("12345"))
    }
}

class VpicParserTest {
    @Test
    fun pierceFallsBackToManufacturer() {
        val d = decoded(VpicFixtures.PIERCE)
        assertEquals("PIERCE MANUFACTURING INC.", d.make)
        assertEquals(2019, d.modelYear)
        assertNull(d.model)
        assertEquals("Pierce", VinFill.displayMake(d.make))
    }

    @Test
    fun emptyAnswers() {
        assertEquals(VpicDecode.Empty, VpicParser.parse(VpicFixtures.NO_RESULTS))
        assertEquals(VpicDecode.Empty, VpicParser.parse(VpicFixtures.NOTHING_DECODED))
    }

    @Test
    fun junkBodyThrows() {
        runCatching { VpicParser.parse("<html>") }.let { assertIs<VpicBodyException>(it.exceptionOrNull()) }
        runCatching { VpicParser.parse("[1,2]") }.let { assertIs<VpicBodyException>(it.exceptionOrNull()) }
    }

    @Test
    fun storedJsonDetails() {
        val details = vinDecodeDetails(decoded(VpicFixtures.FORD_E450).rawJson).toMap()
        assertEquals("Incomplete - Cutaway", details["Body class"])
        assertEquals("Hydraulic", details["Brakes"])
        assertEquals("Avon Lake, Ohio, United States", details["Built in"])
        assertTrue(vinDecodeDetails("").isEmpty())
        assertTrue(vinDecodeDetails("not json").isEmpty())
    }
}

class VinFillTest {
    @Test
    fun makesCrewsRecognize() {
        assertEquals("E-One", VinFill.displayMake("E-ONE, INC."))
        assertEquals("Spartan", VinFill.displayMake("SPARTAN MOTORS CHASSIS"))
        assertEquals("Volvo", VinFill.displayMake("VOLVO TRUCK"))
        assertEquals("Ford", VinFill.displayMake("FORD"))
        assertEquals("GMC", VinFill.displayMake("GMC"))
        assertEquals("(Big) Tex", VinFill.titleCase("(BIG) TEX"))
        assertEquals("Freightliner", VinFill.displayMake("FREIGHTLINER"))
        assertEquals("Wheeled Coach", VinFill.displayMake("WHEELED COACH INDUSTRIES"))
    }

    @Test
    fun engineText() {
        assertEquals("Ford 5.0L-4V", VinFill.engineText(decoded(VpicFixtures.FORD_F150)))
        assertEquals("Detroit Diesel DD15 14.8L", VinFill.engineText(decoded(VpicFixtures.FREIGHTLINER)))
        assertEquals("Cummins ISL 8.9L 310 hp", VinFill.engineText(decoded(VpicFixtures.EONE)))
        assertNull(VinFill.engineText(decoded(VpicFixtures.FORD_E450)))
    }

    @Test
    fun fillsOnlyBlanksAndChassisOnApparatus() {
        val d = decoded(VpicFixtures.EONE)
        val all = VinFill.fill(d, VehicleType.ENGINE, typeChosen = false, year = "", make = "", model = "", engine = "", chassisMake = "")
        assertEquals(VinFill.Fill(year = 2019, make = "E-One", model = null, engine = "Cummins ISL 8.9L 310 hp", chassisMake = "E-One"), all)

        val typed = VinFill.fill(d, VehicleType.ENGINE, false, year = "2018", make = "E-One Typhoon", model = "", engine = "", chassisMake = "Custom")
        assertNull(typed.year)
        assertNull(typed.make)
        assertNull(typed.chassisMake)
        assertEquals("Cummins ISL 8.9L 310 hp", typed.engine)
    }

    @Test
    fun carsGetNoChassisMake() {
        val d = decoded(VpicFixtures.FORD_F150)
        val f = VinFill.fill(d, VehicleType.CAR, typeChosen = true, year = "", make = "", model = "", engine = "", chassisMake = "")
        assertEquals("F-150", f.model)
        assertNull(f.chassisMake)
        assertNull(f.type)
    }

    @Test
    fun typeSuggestion() {
        val car = decoded(VpicFixtures.FORD_F150.replace("\"TRUCK\"", "\"MULTIPURPOSE PASSENGER VEHICLE (MPV)\""))
        assertEquals(VehicleType.CAR, VinFill.fill(car, VehicleType.ENGINE, false, "", "", "", "", "").type)
        // Once the user picked a type, the decode leaves it alone.
        assertNull(VinFill.fill(car, VehicleType.ENGINE, true, "", "", "", "", "").type)
        assertNull(VinFill.suggestedType(decoded(VpicFixtures.FORD_E450)))
    }
}

class VinDecoderTest {
    private class FakeRemote(private vararg val answers: () -> String) : VpicRemote {
        var calls = 0
        override suspend fun fetch(vin: String, modelYear: Int?): String = answers[minOf(calls++, answers.size - 1)]()
    }

    @Test
    fun decodesThenServesFromSessionCache() = runTest {
        val remote = FakeRemote({ VpicFixtures.FORD_E450 })
        val decoder = VinDecoder(remote) { null }
        val first = decoder.decode("1FDXE4FN8LDC00000", null)
        assertIs<VinDecoder.Outcome.Decoded>(first)
        assertEquals(false, first.fromCache)
        val second = decoder.decode("1FDXE4FN8LDC00000", null)
        assertEquals(true, (second as VinDecoder.Outcome.Decoded).fromCache)
        assertEquals(1, remote.calls)
    }

    @Test
    fun storedDecodeSkipsTheNetwork() = runTest {
        val stored = decoded(VpicFixtures.EONE).rawJson
        val remote = FakeRemote({ error("should not be called") })
        val r = VinDecoder(remote) { if (it == "4EN6AAA85K1000000") stored else null }.decode("4EN6AAA85K1000000", null)
        assertEquals("E-ONE", (r as VinDecoder.Outcome.Decoded).decode.make)
        assertEquals(0, remote.calls)
    }

    @Test
    fun retriesOnceOn5xxAndTimeout() = runTest {
        val flaky = FakeRemote({ throw VpicHttpException(503) }, { VpicFixtures.FORD_F150 })
        assertIs<VinDecoder.Outcome.Decoded>(VinDecoder(flaky) { null }.decode("1FTFW1E56PFA00000", null))
        assertEquals(2, flaky.calls)

        val slow = FakeRemote({ throw HttpRequestTimeoutException("url", 10_000) })
        assertEquals(VinDecoder.Outcome.Failed(VinDecoder.Failure.TIMEOUT), VinDecoder(slow) { null }.decode("1FTFW1E56PFA00000", null))
        assertEquals(2, slow.calls)
    }

    @Test
    fun noRetryOn4xxEmptyOrJunk() = runTest {
        val notFound = FakeRemote({ throw VpicHttpException(404) })
        assertEquals(VinDecoder.Outcome.Failed(VinDecoder.Failure.SERVER), VinDecoder(notFound) { null }.decode("X", null))
        assertEquals(1, notFound.calls)

        val empty = FakeRemote({ VpicFixtures.NOTHING_DECODED })
        assertEquals(VinDecoder.Outcome.Failed(VinDecoder.Failure.EMPTY), VinDecoder(empty) { null }.decode("X", null))

        val junk = FakeRemote({ "<html>maintenance</html>" })
        assertEquals(VinDecoder.Outcome.Failed(VinDecoder.Failure.SERVER), VinDecoder(junk) { null }.decode("X", null))
        assertEquals(1, junk.calls)
    }
}

class KtorVpicRemoteTest {
    @Test
    fun requestShapeAndStatus() = runTest {
        var url = ""
        val ok = HttpClient(MockEngine { req ->
            url = req.url.toString()
            respond(VpicFixtures.FORD_F150)
        })
        val body = KtorVpicRemote(ok).fetch("1FTFW1E56PFA00000", 2023)
        assertEquals("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/1FTFW1E56PFA00000?format=json&modelyear=2023", url)
        assertTrue("F-150" in body)

        val down = HttpClient(MockEngine { respond("busy", HttpStatusCode.ServiceUnavailable) })
        val e = runCatching { KtorVpicRemote(down).fetch("1FTFW1E56PFA00000", null) }.exceptionOrNull()
        assertEquals(503, (e as VpicHttpException).status)
    }
}
