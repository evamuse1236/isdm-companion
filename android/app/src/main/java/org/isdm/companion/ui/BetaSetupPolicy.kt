package org.isdm.companion.ui

data class BetaSetupPermissions(
    val preciseLocation: Boolean,
    val backgroundLocation: Boolean,
    val exactAlarms: Boolean,
    val notifications: Boolean,
) {
    val allGranted: Boolean
        get() = preciseLocation && notifications && backgroundLocation && exactAlarms
}

internal enum class AutoAttendancePermissionStep {
    PRECISE_LOCATION, NOTIFICATIONS, BACKGROUND_LOCATION, EXACT_ALARMS, COMPLETE,
}

internal fun nextAutoAttendancePermission(permissions: BetaSetupPermissions): AutoAttendancePermissionStep = when {
    !permissions.preciseLocation -> AutoAttendancePermissionStep.PRECISE_LOCATION
    !permissions.notifications -> AutoAttendancePermissionStep.NOTIFICATIONS
    !permissions.backgroundLocation -> AutoAttendancePermissionStep.BACKGROUND_LOCATION
    !permissions.exactAlarms -> AutoAttendancePermissionStep.EXACT_ALARMS
    else -> AutoAttendancePermissionStep.COMPLETE
}

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
        permissions.allGranted
    return BetaSetupStatus(
        canEnableAutomaticAttendance = automaticAttendanceReady,
        notificationsAvailable = permissions.notifications,
    )
}
