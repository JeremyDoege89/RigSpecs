package dev.rigspecs.data.importing

import dev.rigspecs.data.Settings
import dev.rigspecs.domain.Portal
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Talks to a running extract-specs function (a local `deno run` started with a deliberately
 * invalid model key), set with RIGSPECS_FN_URL and RIGSPECS_FN_SECRET. Skipped otherwise, so
 * normal builds stay offline.
 */
class LiveFunctionTest {
    @Test
    fun appAndFunctionAgree() {
        val url = System.getenv("RIGSPECS_FN_URL") ?: return
        val settings = Settings(
            portalUrls = Portal.entries.associateWith { it.defaultUrl },
            portalEnabled = Portal.entries.associateWith { true },
            importEnabled = true, serverUrl = url, serverKey = "local", importSecret = System.getenv("RIGSPECS_FN_SECRET").orEmpty(),
        )
        val service = ServerExtractionService(importHttpClient()) { settings }
        val ping = runBlocking { service.ping() }
        assertTrue(ping.ok, ping.message)
        // The invalid model key must come back as a configuration problem the runner won't retry.
        val e = runCatching {
            runBlocking { service.extract(ExtractVehicle("E2", "Engine"), ExtractInput.Images(listOf(1 to byteArrayOf(-1, -40, -1))), false) }
        }.exceptionOrNull()
        assertIs<ExtractionException.Permanent>(e)
        assertEquals("The server's model API key was rejected.", e.message)
    }
}
