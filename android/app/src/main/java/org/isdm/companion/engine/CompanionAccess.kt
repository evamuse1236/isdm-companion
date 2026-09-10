package org.isdm.companion.engine

/** Access to Companion is separate from LMS authentication and the location gate. */
fun interface CompanionAccessPort {
    suspend fun requireAccess(fresh: Boolean, allowEnrollment: Boolean)
}

object AllowCompanionAccess : CompanionAccessPort {
    override suspend fun requireAccess(fresh: Boolean, allowEnrollment: Boolean) = Unit
}

class CompanionAccessException(val reason: String) : Exception(reason)
