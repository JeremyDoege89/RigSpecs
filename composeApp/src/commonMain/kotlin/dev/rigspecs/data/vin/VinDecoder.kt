package dev.rigspecs.data.vin

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException

/** True for "no network at all" errors (no DNS, no route, refused), as opposed to a slow server. */
expect fun isOfflineError(t: Throwable): Boolean

/** True for timeouts the platform reports outside Ktor's own exception types. */
expect fun isPlatformTimeout(t: Throwable): Boolean

/**
 * Decode cache first (a rig already saved with that VIN, or an earlier decode this session),
 * then one vPIC request, retried once on a timeout or 5xx. Only real decodes are cached.
 */
class VinDecoder(
    private val remote: VpicRemote,
    /** The stored vPIC JSON of a rig with this VIN, if any. */
    private val storedDecode: suspend (vin: String) -> String?,
) {
    enum class Failure { OFFLINE, TIMEOUT, SERVER, EMPTY }

    sealed interface Outcome {
        data class Decoded(val decode: VpicDecode.Decoded, val fromCache: Boolean) : Outcome
        data class Failed(val failure: Failure) : Outcome
    }

    private val session = mutableMapOf<String, VpicDecode.Decoded>()

    /** [vin] must already be normalized. [modelYear] is sent only when the user typed one. */
    suspend fun decode(vin: String, modelYear: Int?): Outcome {
        session[vin]?.let { return Outcome.Decoded(it, fromCache = true) }
        storedDecode(vin)?.let { json ->
            val d = runCatching { VpicParser.parse("""{"Results":[$json]}""") }.getOrNull()
            if (d is VpicDecode.Decoded) {
                session[vin] = d
                return Outcome.Decoded(d, fromCache = true)
            }
        }
        val r = fetchWithRetry(vin, modelYear)
        if (r is Outcome.Decoded) session[vin] = r.decode
        return r
    }

    private suspend fun fetchWithRetry(vin: String, modelYear: Int?): Outcome {
        var attempt = 0
        while (true) {
            attempt++
            val failure = try {
                return when (val d = VpicParser.parse(remote.fetch(vin, modelYear))) {
                    is VpicDecode.Decoded -> Outcome.Decoded(d, fromCache = false)
                    VpicDecode.Empty -> Outcome.Failed(Failure.EMPTY)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRequestTimeoutException) {
                Failure.TIMEOUT
            } catch (e: ConnectTimeoutException) {
                Failure.TIMEOUT
            } catch (e: SocketTimeoutException) {
                Failure.TIMEOUT
            } catch (e: VpicHttpException) {
                if (e.status in 500..599) Failure.SERVER else return Outcome.Failed(Failure.SERVER)
            } catch (e: VpicBodyException) {
                return Outcome.Failed(Failure.SERVER)
            } catch (e: Exception) {
                when {
                    isOfflineError(e) -> return Outcome.Failed(Failure.OFFLINE)
                    isPlatformTimeout(e) -> Failure.TIMEOUT
                    // TLS trouble, connection reset and the like: not worth a retry.
                    else -> return Outcome.Failed(Failure.SERVER)
                }
            }
            if (attempt >= 2) return Outcome.Failed(failure)
        }
    }
}
