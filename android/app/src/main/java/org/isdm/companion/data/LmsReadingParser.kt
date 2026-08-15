package org.isdm.companion.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.isdm.companion.engine.LmsCourse
import org.isdm.companion.engine.LmsReadingProgress
import org.isdm.companion.engine.LmsReadingSection
import org.isdm.companion.engine.ReadingItem

internal fun parseCourses(html: String, baseUrl: String): List<LmsCourse> {
    val courses = linkedMapOf<String, LmsCourse>()
    for (anchor in Jsoup.parse(html, baseUrl).select("a[href]")) {
        val url = anchor.absUrl("href").toHttpUrlOrNull() ?: continue
        if (!url.encodedPath.endsWith("/course/details")) continue
        if (url.queryParameter("course_id") != null) continue
        val catId = url.queryParameter("cat_id")?.takeIf(::isNumericId) ?: continue
        val title = anchor.courseLabel()
        if (title.isBlank()) continue
        courses.putIfAbsent(catId, LmsCourse(catId, title))
    }
    return courses.values.toList()
}

internal fun parseReadingSections(
    html: String,
    course: LmsCourse,
    baseUrl: String,
): List<LmsReadingSection> {
    val sections = linkedMapOf<String, LmsReadingSection>()
    for (anchor in Jsoup.parse(html, baseUrl).select("a[href]")) {
        val url = anchor.absUrl("href").toHttpUrlOrNull() ?: continue
        if (!url.encodedPath.endsWith("/course/details")) continue
        if (url.queryParameter("cat_id") != course.catId) continue
        val sid = url.queryParameter("course_id")?.takeIf(::isNumericId) ?: continue
        val title = anchor.readableLabel()
        if (!title.isReadingSectionLabel()) continue
        sections.putIfAbsent(sid, LmsReadingSection(sid, course.catId, title))
    }
    return sections.values.toList()
}

internal fun parseReadingItems(
    html: String,
    course: LmsCourse,
    section: LmsReadingSection,
    baseUrl: String,
): List<ReadingItem> {
    val items = linkedMapOf<String, ReadingItem>()
    for (anchor in Jsoup.parse(html, baseUrl).select("a[href]")) {
        val sourceUrl = anchor.absUrl("href")
        val url = sourceUrl.toHttpUrlOrNull() ?: continue
        if (!url.encodedPath.endsWith("/subtopic/view")) continue
        val catId = url.queryParameter("cat_id")?.takeIf(::isNumericId) ?: continue
        val sid = url.queryParameter("sid")?.takeIf(::isNumericId) ?: continue
        val vid = url.queryParameter("vid")?.takeIf(::isNumericId) ?: continue
        val cid = url.queryParameter("cid")?.takeIf(::isNumericId) ?: continue
        if (catId != course.catId || sid != section.sid) continue
        val titleElement = anchor.readingItemTitleElement()
        val title = anchor.readingItemLabel(titleElement)
        if (title.isBlank()) continue
        val topicLabel = titleElement?.attr("topic_title")?.trim().orEmpty()
        items.putIfAbsent(
            vid,
            ReadingItem(
                vid = vid,
                sid = sid,
                cid = cid,
                catId = catId,
                title = title,
                courseName = course.name,
                sectionName = section.name,
                sourceUrl = sourceUrl,
                sessionNumber = readingSessionNumber(title, section.name)
                    ?: readingSessionNumber("", topicLabel.replace('_', ' ')),
                progress = progressAround(anchor),
                mandatory = section.name.contains("mandatory", ignoreCase = true) ||
                    topicLabel.contains("mandatory", ignoreCase = true),
            ),
        )
    }
    return items.values.toList()
}

internal fun readingSessionNumber(title: String, sectionName: String): Int? {
    val titleNumbers = SESSION_NUMBER.findAll(title).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
    if (titleNumbers.size > 1) return null
    if (titleNumbers.size == 1) return titleNumbers.single()
    val sectionNumbers = SESSION_NUMBER.findAll(sectionName).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
    return sectionNumbers.singleOrNull()
}

private fun Element.readableLabel(): String =
    text().trim().ifEmpty { attr("title").trim() }.replace(Regex("\\s+"), " ")

private fun Element.courseLabel(): String {
    val direct = readableLabel()
    if (direct.isNotBlank() && !direct.isGenericCourseAction()) return direct
    val labelled = listOf(
        attr("aria-label"),
        attr("title"),
        selectFirst("img[alt]")?.attr("alt").orEmpty(),
        selectFirst("img[title]")?.attr("title").orEmpty(),
    )
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .firstOrNull { it.isNotBlank() && !it.isGenericCourseAction() }
    if (labelled != null) return labelled

    val containers = parents().take(6)
        .takeWhile { it.tagName() != "body" && it.tagName() != "html" }
    val imageLabel = containers.asSequence()
        .flatMap { it.select("img[alt], img[title]").asSequence() }
        .flatMap { image -> sequenceOf(image.attr("alt"), image.attr("title")) }
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .firstOrNull { it.isUsefulCourseLabel() }
    if (imageLabel != null) return imageLabel

    for (container in containers) {
        val heading = container.select("h1, h2, h3, h4, h5, .course-title, .title, .field-name-title")
            .asSequence()
            .map { it.text().trim().replace(Regex("\\s+"), " ") }
            .firstOrNull { it.isUsefulCourseLabel() }
        if (heading != null) return heading
        val withoutAction = container.text()
            .replace(Regex("(?i)\\b(?:go to|open|view) course\\b"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (withoutAction.isUsefulCourseLabel()) return withoutAction
    }
    return direct
}

private fun Element.readingItemTitleElement(): Element? =
    parents().asSequence()
        .take(5)
        .mapNotNull { it.selectFirst(".single_content_title") }
        .firstOrNull()

private fun Element.readingItemLabel(titleElement: Element?): String {
    val direct = readableLabel()
    if (direct.isNotBlank() && !direct.isGenericReadingAction()) return direct
    return titleElement?.readableLabel().orEmpty().ifBlank { direct }
}

private fun String.isGenericReadingAction(): Boolean =
    matches(Regex("(?i)\\s*(?:view|open|read|start|resume|download)\\s*"))

private fun String.isGenericCourseAction(): Boolean =
    matches(Regex("(?i)\\s*(?:go to|open|view) course\\s*"))

private fun String.isUsefulCourseLabel(): Boolean =
    isNotBlank() && !isGenericCourseAction() && length in 3..120

private fun String.isReadingSectionLabel(): Boolean =
    contains("reading", ignoreCase = true) || contains("resource", ignoreCase = true)

private fun progressAround(anchor: Element): LmsReadingProgress {
    val context = sequenceOf(anchor) + anchor.parents().asSequence().take(5)
    for (element in context) {
        if (element.tagName() == "body" || element.tagName() == "html") break
        val classNames = (sequenceOf(element) + element.select("[class]").asSequence())
            .flatMap { it.classNames().asSequence() }
            .map { it.lowercase() }
            .toSet()
        when {
            "flat_list_video_status_completed" in classNames -> return LmsReadingProgress.COMPLETED
            "flat_list_video_status_inprogress" in classNames -> return LmsReadingProgress.IN_PROGRESS
            "document_viewed_status_icon" in classNames -> return LmsReadingProgress.VIEWED
            "document_status_icon" in classNames -> return LmsReadingProgress.UNOPENED
        }
    }
    return LmsReadingProgress.UNKNOWN
}

private fun isNumericId(value: String): Boolean = value.matches(Regex("\\d+"))

private val SESSION_NUMBER = Regex("(?i)\\bsession\\s*(?:no\\.?\\s*)?[-:#]?\\s*(\\d{1,2})\\b")
