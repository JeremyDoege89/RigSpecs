package dev.rigspecs.data.vin

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

actual fun isOfflineError(t: Throwable): Boolean =
    generateSequence(t) { it.cause }.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException }

actual fun isPlatformTimeout(t: Throwable): Boolean =
    generateSequence(t) { it.cause }.any { it is SocketTimeoutException }
