package org.isdm.companion.data

import org.isdm.companion.engine.LmsCourse
import org.isdm.companion.engine.LmsReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LmsReadingParserTest {
    @Test
    fun `course links are identified and deduplicated by cat id`() {
        val courses = parseCourses(
            """
            <article class="course-card"><h3>State, Market and Society</h3><a href="/course/details?cat_id=12">GO TO COURSE</a></article>
            <a href="/course/details?cat_id=12">Open again</a>
            <a href="/course/details?cat_id=21">Data Analysis for Development</a>
            <a href="/course/details?cat_id=12&course_id=91">Course Readings</a>
            """.trimIndent(),
            "https://lms.example/",
        )

        assertEquals(listOf("12", "21"), courses.map { it.catId })
        assertEquals("State, Market and Society", courses.first().name)
    }

    @Test
    fun `only reading-like sections are retained`() {
        val course = LmsCourse("12", "State, Market and Society")
        val sections = parseReadingSections(
            """
            <a href="/course/details?cat_id=12&course_id=91">Mandatory Reading</a>
            <a href="/course/details?cat_id=12&course_id=92">Course Readings</a>
            <a href="/course/details?cat_id=12&course_id=93">Resources</a>
            <a href="/course/details?cat_id=12&course_id=94">Lecture recordings</a>
            """.trimIndent(),
            course,
            "https://lms.example/",
        )

        assertEquals(listOf("91", "92", "93"), sections.map { it.sid })
    }

    @Test
    fun `items retain identity course type progress and absolute source url`() {
        val course = LmsCourse("12", "State, Market and Society")
        val section = parseReadingSections(
            "<a href='/course/details?cat_id=12&course_id=91'>Mandatory Reading</a>",
            course,
            "https://lms.example/",
        ).single()

        val items = parseReadingItems(
            """
            <div class="row document_viewed_status_icon">
              <a href="/subtopic/view?sid=91&vid=501&cid=7&cat_id=12">Session 1 Seeing Like a State</a>
            </div>
            <div class="row flat_list_video_status_completed">
              <a href="/subtopic/view?sid=91&vid=502&cid=7&cat_id=12">Institutions lecture</a>
              <a href="/subtopic/view?sid=91&vid=502&cid=7&cat_id=12">Duplicate action</a>
            </div>
            <div class="row">
              <a href="/subtopic/view?sid=91&vid=503&cid=7&cat_id=12">Chapter 12 Government</a>
            </div>
            """.trimIndent(),
            course,
            section,
            "https://lms.example/",
        )

        assertEquals(listOf("501", "502", "503"), items.map { it.vid })
        assertEquals(LmsReadingProgress.VIEWED, items[0].progress)
        assertEquals(LmsReadingProgress.COMPLETED, items[1].progress)
        assertEquals(LmsReadingProgress.UNKNOWN, items[2].progress)
        assertEquals("State, Market and Society", items[0].courseName)
        assertEquals(1, items[0].sessionNumber)
        assertEquals(null, items[1].sessionNumber)
        assertEquals(null, items[2].sessionNumber)
        assertTrue(items[0].mandatory)
        assertFalse(items[0].done)
        assertEquals(
            "https://lms.example/subtopic/view?sid=91&vid=501&cid=7&cat_id=12",
            items[0].sourceUrl,
        )
    }

    @Test
    fun `session mapping uses explicit unambiguous evidence only`() {
        assertEquals(3, readingSessionNumber("Methods overview", "Session 3 Readings"))
        assertEquals(null, readingSessionNumber("Chapter 12 Government", "Course Readings"))
        assertEquals(null, readingSessionNumber("Session 1 and Session 2 overview", "Course Readings"))
    }
}
