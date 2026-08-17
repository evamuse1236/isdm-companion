package org.isdm.companion.ui

import android.content.Context
import org.isdm.companion.BuildConfig

data class ReleaseNotes(
    val versionCode: Int,
    val versionName: String,
    val items: List<String>,
)

val CURRENT_RELEASE_NOTES = ReleaseNotes(
    versionCode = BuildConfig.VERSION_CODE,
    versionName = BuildConfig.VERSION_NAME,
    items = listOf(
        "Attendance now uses your completed LMS sessions from the last year, so upcoming classes no longer lower your percentage.",
        "Profile has a clearer attendance overview and simpler personal details.",
    ),
)

internal fun shouldShowReleaseNotes(
    lastSeenVersionCode: Int?,
    currentVersionCode: Int,
    firstInstallTime: Long,
    lastUpdateTime: Long,
): Boolean = if (lastSeenVersionCode == null) {
    lastUpdateTime > firstInstallTime
} else {
    lastSeenVersionCode < currentVersionCode
}

class ReleaseNotesStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun pending(): ReleaseNotes? {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val lastSeen = if (preferences.contains(LAST_SEEN_VERSION)) {
            preferences.getInt(LAST_SEEN_VERSION, 0)
        } else {
            null
        }
        val show = shouldShowReleaseNotes(
            lastSeenVersionCode = lastSeen,
            currentVersionCode = CURRENT_RELEASE_NOTES.versionCode,
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
        )
        if (!show && lastSeen == null) markSeen()
        return CURRENT_RELEASE_NOTES.takeIf { show }
    }

    fun markSeen() {
        preferences.edit().putInt(LAST_SEEN_VERSION, CURRENT_RELEASE_NOTES.versionCode).apply()
    }

    private companion object {
        const val PREFERENCES = "release_notes"
        const val LAST_SEEN_VERSION = "last_seen_version_code"
    }
}
