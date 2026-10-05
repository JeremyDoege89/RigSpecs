package dev.rigspecs.data

import dev.rigspecs.data.vin.KtorVpicRemote
import dev.rigspecs.data.vin.VinDecoder
import dev.rigspecs.data.vin.vpicHttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Hits the real NHTSA service; runs only with VPIC_LIVE=1 so normal builds stay offline. */
class LiveVpicTest {
    @Test
    fun realDecode() {
        if (System.getenv("VPIC_LIVE") != "1") return
        val decoder = VinDecoder(KtorVpicRemote(vpicHttpClient())) { null }
        val r = runBlocking { decoder.decode("1FDXE4FN8LDC00000", null) }
        assertEquals("E-450", (r as VinDecoder.Outcome.Decoded).decode.model)

        val offline = VinDecoder(KtorVpicRemote(vpicHttpClient(), baseUrl = "https://no-such-host.invalid/")) { null }
        assertEquals(VinDecoder.Outcome.Failed(VinDecoder.Failure.OFFLINE), runBlocking { offline.decode("1FDXE4FN8LDC00000", null) })
    }
}
