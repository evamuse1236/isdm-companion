package org.isdm.companion.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.isdm.companion.engine.FacultyProfile
import org.isdm.companion.engine.LmsCourse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Extracts the Faculty Profile topics embedded in an LMS course page.
 *
 * The LMS does not expose these topics as normal links. Each topic is a list item whose attributes
 * contain the section, content, course, and category identifiers needed by `/subtopic/view`.
 */
internal fun parseFacultyProfiles(
    html: String,
    course: LmsCourse,
    baseUrl: String,
): List<FacultyProfile> {
    val base = baseUrl.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return emptyList()
    val profiles = linkedMapOf<String, FacultyProfile>()

    for (topic in Jsoup.parse(html, baseUrl).select("li[nid][first_video_nid]")) {
        val topicTitle = topic.profileTopicTitle() ?: continue
        if (!FACULTY_PROFILE_PREFIX.containsMatchIn(topicTitle)) continue

        val sid = topic.attr("nid").takeIf(::isNumericId) ?: continue
        val vid = topic.attr("first_video_nid").takeIf(::isNumericId) ?: continue
        val cid = topic.attr("course_id").takeIf(::isNumericId) ?: continue
        val catId = topic.attr("cat_id").takeIf(::isNumericId) ?: course.catId
        if (catId != course.catId) continue

        val sourceUrl = base.newBuilder()
            .addPathSegments("subtopic/view")
            .addQueryParameter("sid", sid)
            .addQueryParameter("vid", vid)
            .addQueryParameter("cid", cid)
            .addQueryParameter("cat_id", catId)
            .build()
            .toString()
        val displayName = topic.facultyDisplayName(topicTitle, course.name)

        profiles.putIfAbsent(
            sourceUrl,
            FacultyProfile(
                courseCatId = course.catId,
                courseName = course.name,
                displayName = displayName,
                itemTitle = topicTitle,
                sourceUrl = sourceUrl,
            ),
        )
    }
    return profiles.values.toList()
}

private fun Element.profileTopicTitle(): String? {
    val titleElement = selectFirst(".left-topic-title") ?: return null
    return titleElement.attr("title").ifBlank { titleElement.text() }
        .trim()
        .replace(Regex("\\s+"), " ")
        .takeIf { it.isNotEmpty() }
}

private fun Element.facultyDisplayName(topicTitle: String, courseName: String): String {
    FACULTY_PROFILE_PREFIX.replaceFirst(topicTitle, "")
        .trim(' ', '_', '-', ':')
        .takeIf { it.isUsefulFacultyName(courseName) }
        ?.let { return it }

    val container = parents().firstOrNull { "category_left_nav_course" in it.classNames() }
    val accordionName = container?.selectFirst(
        ".accordion-header, .ui-accordion-header, .panel-title, summary",
    )?.text()?.normalised()
    if (accordionName.isUsefulFacultyName(courseName)) return accordionName!!

    val courseLabel = container?.select("a.subject_course_title, a[data-course-id]")
        ?.firstOrNull { it.attr("data-course-id") == attr("course_id") }
        ?.let { it.attr("title").ifBlank(it::text).normalised() }
    return courseLabel.takeIf { it.isUsefulFacultyName(courseName) } ?: "Faculty profile"
}

private fun String?.isUsefulFacultyName(courseName: String): Boolean =
    !isNullOrBlank() &&
        !equals(courseName, ignoreCase = true) &&
        !FACULTY_PROFILE_ONLY.matches(this)

private fun String.normalised(): String = trim().replace(Regex("\\s+"), " ")

private fun isNumericId(value: String): Boolean = value.matches(Regex("\\d+"))

private val FACULTY_PROFILE_PREFIX = Regex("(?i)^faculty\\s+profile")
private val FACULTY_PROFILE_ONLY = Regex("(?i)faculty\\s+profile")
