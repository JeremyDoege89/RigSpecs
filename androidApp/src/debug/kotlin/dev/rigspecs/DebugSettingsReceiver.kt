package dev.rigspecs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rigspecs.data.SettingsRepository
import dev.rigspecs.data.importing.ExtractionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * adb-only settings, for setting up a test phone without tapping through the UI:
 *
 *   adb shell am broadcast -n dev.rigspecs/.DebugSettingsReceiver -a set \
 *       --es serverUrl https://<ref>.supabase.co --es serverKey <publishable key> --es secret <passphrase>
 *   adb shell am broadcast -n dev.rigspecs/.DebugSettingsReceiver -a check
 *
 * Results are logged under the tag DebugSettings.
 */
class DebugSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val koin = GlobalContext.get()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    "set" -> {
                        val settings = koin.get<SettingsRepository>()
                        intent.getStringExtra("serverUrl")?.let { settings.set(SettingsRepository.Keys.SERVER_URL, it.trim()) }
                        intent.getStringExtra("serverKey")?.let { settings.set(SettingsRepository.Keys.SERVER_KEY, it.trim()) }
                        intent.getStringExtra("secret")?.let { settings.set(SettingsRepository.Keys.IMPORT_SECRET, it.trim()) }
                        val s = settings.current()
                        Log.i(TAG, "saved: url=${s.serverUrl} key=${if (s.serverKey.isBlank()) "missing" else "set"} " +
                            "passphrase=${if (s.importSecret.isBlank()) "missing" else "set"} ready=${s.importReady}")
                    }
                    "check" -> {
                        val r = koin.get<ExtractionService>().ping()
                        Log.i(TAG, "check: ok=${r.ok} ${r.message}")
                    }
                    else -> Log.w(TAG, "unknown action ${intent.action}; use set or check")
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugSettings"
    }
}
