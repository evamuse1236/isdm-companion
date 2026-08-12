package org.isdm.companion.engine

/**
 * A faculty item explicitly listed in a course's Faculty Profile section.  It is course-scoped;
 * the LMS does not provide a safe way to match it to a scheduled classroom trainer.
 */
data class FacultyProfile(
    val courseCatId: String,
    val courseName: String,
    val displayName: String,
    val itemTitle: String,
    val sourceUrl: String,
    val photoUrl: String? = null,
    val roleTitle: String? = null,
    val email: String? = null,
    val bio: String? = null,
)

/** Read-only course faculty directory. */
interface FacultyDirectory {
    suspend fun facultyProfiles(course: LmsCourse): List<FacultyProfile>
}
