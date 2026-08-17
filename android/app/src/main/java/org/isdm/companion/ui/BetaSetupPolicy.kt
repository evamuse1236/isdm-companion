package org.isdm.companion.ui

data class BetaSetupPermissions(
    val preciseLocation: Boolean,
    val backgroundLocation: Boolean,
    val exactAlarms: Boolean,
    val notifications: Boolean,
)

data class BetaSetupStatus(
    val canEnableAutomaticAttendance: Boolean,
    val notificationsAvailable: Boolean,
)

fun betaSetupStatus(
    consented: Boolean,
    profileKnown: Boolean,
    permissions: BetaSetupPermissions,
): BetaSetupStatus {
    val automaticAttendanceReady = consented && profileKnown &&
        permissions.preciseLocation && permissions.backgroundLocation && permissions.exactAlarms
    return BetaSetupStatus(
        canEnableAutomaticAttendance = automaticAttendanceReady,
        notificationsAvailable = permissions.notifications,
    )
}
