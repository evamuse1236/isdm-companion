package org.isdm.companion.data

import org.isdm.companion.engine.LmsCourse
import org.isdm.companion.engine.LmsReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LmsReadingParserTest {
    @Test
    fun `live WID card and reading retain their LMS labels`() {
        val course = parseCourses(
            """
            <div class="course-card">
              <div class="course-image-wrapper">
                <a href="/course/details?cat_id=70966" class="course-link">
                  <img alt="B10 - T1 - WID" title="B10 - T1 - WID">
                </a>
              </div>
              <div class="course-details">
                <a href="/course/details?cat_id=70966" class="button btn-primary">
                  <div class="course-actions">GO TO COURSE</div>
                </a>
                <h4 class="course-title">Term 1</h4>
                <p class="course-description" title="WID">WID</p>
              </div>
            </div>
            """.trimIndent(),
            "https://lms.isdm.org.in/",
        ).single()

        val section = parseReadingSections(
            """
            <a href="/course/details?cat_id=70966&amp;course_id=1296239" title="Course Readings">
              Course Readings
            </a>
            """.trimIndent(),
            course,
            "https://lms.isdm.org.in/",
        ).single()

        val reading = parseReadingItems(
            """
            <div class="single_content_description_wrapper">
              <div class="single_content_title"
                   title="Poor Economics - A Radical thinking of way to fight Global Poverty"
                   cat_title="B10 - T1 - WID"
                   chapter_title="Course Readings"
                   topic_title="B10_T1_WID_Session1_Mandatory Readings">
                Poor Economics - A Radical thinking of way to fight Global Poverty
              </div>
              <a href="/subtopic/view?sid=1296239&amp;vid=1298304&amp;cid=1296859&amp;cat_id=70966">
                <div class="start_course_button" content_type="Document">View</div>
              </a>
            </div>
            """.trimIndent(),
            course,
            section,
            "https://lms.isdm.org.in/",
        ).single()

        assertEquals("Poor Economics - A Radical thinking of way to fight Global Poverty", reading.title)
        assertEquals("B10 - T1 - WID", course.name)
        assertEquals(1, reading.sessionNumber)
        assertTrue(reading.mandatory)
    }

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
