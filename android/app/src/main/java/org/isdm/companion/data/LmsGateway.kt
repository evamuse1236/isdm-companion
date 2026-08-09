package org.isdm.companion.data

/**
 * The data layer adopts the engine's network seam rather than creating a second application
 * contract. Keeping this alias here gives data-layer callers a short, discoverable name while
 * [org.isdm.companion.engine.CompanionEngine] remains the owner of the public seam.
 */
typealias LmsGateway = org.isdm.companion.engine.LmsGateway

open class LmsException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LmsAuthenticationException(message: String, cause: Throwable? = null) : LmsException(message, cause)

class LmsHttpException(
    val status: Int,
    val requestUrl: String,
    message: String = "The LMS returned HTTP $status for $requestUrl",
) : LmsException(message)

class LmsProtocolException(message: String, cause: Throwable? = null) : LmsException(message, cause)
