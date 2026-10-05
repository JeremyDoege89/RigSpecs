package dev.rigspecs.data

import dev.rigspecs.domain.EntityType
import dev.rigspecs.domain.FieldChange
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.Portal
import dev.rigspecs.domain.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Settings with their defaults applied, as screens read them. */
data class Settings(
    val portalUrls: Map<Portal, String>,
    val portalEnabled: Map<Portal, Boolean>,
    val importEnabled: Boolean,
    /** Device-only until sign-in exists: where the extraction function lives. */
    val serverUrl: String,
    val serverKey: String,
    /** Optional extra check the function can require, so a leaked project key alone can't run imports. */
    val importSecret: String,
) {
    /** The extraction endpoint, or null until a server is configured. */
    val extractUrl: String?
        get() = serverUrl.trim().trimEnd('/').takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.let { "$it/functions/v1/extract-specs" }

    val importReady: Boolean get() = importEnabled && extractUrl != null && serverKey.isNotBlank()
}

/**
 * Key-value settings in the synced `setting` table. Keys under "device." never leave this
 * device; everything else is department-wide and goes through the change log like any edit.
 */
class SettingsRepository(
    private val db: AppDatabase,
    private val rigs: RigRepository,
    private val session: Session,
    private val ids: Ids,
) {
    private val dao = db.settingDao()

    fun observe(): Flow<Settings> = dao.observeAll().map { rows -> toSettings(rows.associate { it.key to it.value }) }

    suspend fun current(): Settings = toSettings(Keys.all.mapNotNull { k -> dao.get(k)?.let { k to it.value } }.toMap())

    /** A blank or default value removes the row, so a later change of default reaches everyone. */
    suspend fun set(key: String, value: String) = rigs.write {
        val old = dao.get(key)
        val default = Keys.defaults[key]
        if (value == default || (value.isEmpty() && default == null)) {
            if (old != null) dao.delete(key)
        } else {
            dao.upsert(SettingEntity(key, session.departmentId, value, ids.now(), session.userId))
        }
        if (!key.startsWith(Keys.DEVICE_PREFIX) && old?.value != value) {
            rigs.log(EntityType.SETTING, key, listOf(FieldChange("value", old?.value ?: default, value.ifEmpty { default })))
        }
    }

    private fun toSettings(m: Map<String, String>) = Settings(
        portalUrls = Portal.entries.associateWith { m[it.urlKey] ?: it.defaultUrl },
        portalEnabled = Portal.entries.associateWith { (m[it.enabledKey] ?: "true") == "true" },
        importEnabled = (m[Keys.IMPORT_ENABLED] ?: "true") == "true",
        serverUrl = m[Keys.SERVER_URL].orEmpty(),
        serverKey = m[Keys.SERVER_KEY].orEmpty(),
        importSecret = m[Keys.IMPORT_SECRET].orEmpty(),
    )

    object Keys {
        const val DEVICE_PREFIX = "device."
        const val IMPORT_ENABLED = "import.enabled"
        const val SERVER_URL = "device.server.url"
        const val SERVER_KEY = "device.server.key"
        const val IMPORT_SECRET = "device.import.secret"

        val defaults: Map<String, String> = buildMap {
            Portal.entries.forEach {
                put(it.urlKey, it.defaultUrl)
                put(it.enabledKey, "true")
            }
            put(IMPORT_ENABLED, "true")
        }
        val all: List<String> = defaults.keys.toList() + listOf(SERVER_URL, SERVER_KEY, IMPORT_SECRET)
    }
}
