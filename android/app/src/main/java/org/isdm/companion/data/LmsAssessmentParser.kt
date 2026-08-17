package org.isdm.companion.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

internal data class AssessmentTaskDraft(
    val id: String,
    val title: String,
    val status: String,
    val courseId: String,
    val sectionId: String,
    val submissionUrl: String,
)

internal data class AssessmentDates(
    val dueDate: LocalDate?,
    val endDate: LocalDate?,
)

internal data class AssessmentResource(
    val title: String,
    val sourceUrl: String,
)

internal fun parseAssessmentTasks(html: String, baseUrl: String): List<AssessmentTaskDraft> {
    val tasks = linkedMapOf<String, AssessmentTaskDraft>()
    val document = Jsoup.parse(html, baseUrl)
    for (anchor in document.select("a[href]")) {
        val submissionUrl = anchor.absUrl("href")
        val url = submissionUrl.toHttpUrlOrNull() ?: continue
        if (!url.encodedPath.endsWith("/subtopic/view")) continue
        if (url.queryParameter("destination") != "my-activities") continue
        val sectionId = url.queryParameter("sid")?.takeIf(::isAssessmentId) ?: continue
        val id = url.queryParameter("vid")?.takeIf(::isAssessmentId) ?: continue
        val courseId = url.queryParameter("cat_id")?.takeIf(::isAssessmentId) ?: continue
        val row = anchor.parents().firstOrNull { parent ->
            parent.tagName() == "tr" && "Starts On:" in parent.text()
        } ?: continue
        val rowText = row.text().trim().replace(Regex("\\s+"), " ")
        val title = row.selectFirst("p")?.text()?.trim()
            ?.substringBefore(" from topic Assessments")
            ?.takeIf { it.isNotBlank() }
            ?: ASSESSMENT_TITLE.find(rowText)?.groupValues?.get(1)?.trim().orEmpty()
        if (title.isBlank()) continue
        val status = ASSESSMENT_STATUS.find(rowText)?.groupValues?.get(1)?.trim().orEmpty()
            .ifBlank { "Open" }
        tasks.putIfAbsent(
            id,
            AssessmentTaskDraft(
                id = id,
                title = title,
                status = status,
                courseId = courseId,
                sectionId = sectionId,
                submissionUrl = submissionUrl,
            ),
        )
    }
    return tasks.values.toList()
}

internal fun parseAssessmentFrameUrl(html: String, baseUrl: String): String? {
    val base = baseUrl.toHttpUrlOrNull() ?: return null
    val frame = Jsoup.parse(html, baseUrl).selectFirst("iframe#iframe_load[src]")
        ?.absUrl("src")
        ?.toHttpUrlOrNull()
        ?: return null
    if (!frame.host.equals(base.host, ignoreCase = true)) return null
    if (base.isHttps && !frame.isHttps) return null
    return frame.toString()
}

internal fun parseAssessmentDates(html: String): AssessmentDates {
    val text = Jsoup.parse(html).text()
    return AssessmentDates(
        dueDate = ASSESSMENT_DUE.find(text)?.groupValues?.get(1)?.toAssessmentDate(),
        endDate = ASSESSMENT_END.find(text)?.groupValues?.get(1)?.toAssessmentDate(),
    )
}

internal fun parseAssessmentResource(
    html: String,
    submissionUrl: String,
    baseUrl: String,
): AssessmentResource? {
    val targetId = submissionUrl.toHttpUrlOrNull()?.queryParameter("vid") ?: return null
    val document = Jsoup.parse(html, baseUrl)
    val downloadableIds = document.select("a[href]").mapNotNullTo(mutableSetOf()) { anchor ->
        val url = anchor.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNullTo null
        url.queryParameter("vid")?.takeIf {
            url.encodedPath.endsWith("/download/video") && isAssessmentId(it)
        }
    }
    var latestResource: AssessmentResource? = null
    for (anchor in document.select("a[href]")) {
        val sourceUrl = anchor.absUrl("href")
        val url = sourceUrl.toHttpUrlOrNull() ?: continue
        if (!url.encodedPath.endsWith("/subtopic/view")) continue
        val id = url.queryParameter("vid") ?: continue
        if (id == targetId) return latestResource
        if (id in downloadableIds) {
            val title = anchor.text().trim().replace(Regex("\\s+"), " ")
                .ifBlank { anchor.attr("title").trim() }
            if (title.isNotBlank()) latestResource = AssessmentResource(title, sourceUrl)
        }
    }
    return null
}

private fun String.toAssessmentDate(): LocalDate? =
    runCatching { LocalDate.parse(this, ASSESSMENT_DATE_FORMAT) }.getOrNull()

private fun isAssessmentId(value: String): Boolean = value.matches(Regex("\\d+"))

private val ASSESSMENT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu")
private val ASSESSMENT_TITLE = Regex("(.+?)\\s+from topic\\s+Assessments", RegexOption.IGNORE_CASE)
private val ASSESSMENT_STATUS = Regex("Status:\\s*(.+?)(?:\\s+Take Activity|$)", RegexOption.IGNORE_CASE)
private val ASSESSMENT_DUE = Regex("Due Date\\s*:\\s*(\\d{2}/\\d{2}/\\d{4})", RegexOption.IGNORE_CASE)
private val ASSESSMENT_END = Regex("End Date\\s*:\\s*(\\d{2}/\\d{2}/\\d{4})", RegexOption.IGNORE_CASE)
