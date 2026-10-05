package dev.rigspecs.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Who is editing, and for which department. Auth (milestone 4) replaces [LocalSession]. */
interface Session {
    val departmentId: String
    val userId: String
    val canEdit: Boolean
}

/** Before sign-in exists, everything belongs to one local department and this device can edit. */
object LocalSession : Session {
    override val departmentId = "local"
    override val userId = "local"
    override val canEdit = true
}

/** Time and ids behind one seam so tests can pin them. */
interface Ids {
    fun newId(): String
    fun now(): Long
}

object SystemIds : Ids {
    override fun newId() = Uuid.random().toString()
    override fun now() = Clock.System.now().toEpochMilliseconds()
}
