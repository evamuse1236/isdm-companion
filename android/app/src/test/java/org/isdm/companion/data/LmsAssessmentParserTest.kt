package org.isdm.companion.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class LmsAssessmentParserTest {
    @Test
    fun `active assessment keeps its submission link without trusting the task-list deadline`() {
        val assessment = parseAssessmentTasks(
            """
            <table><tbody><tr>
              <td>
                <p>B10 - T1 - PMDL - Reflection 2 from topic Assessments</p>
                <span>Starts On: 17-Aug-2026 - 11:37 AM</span>
                <span>Due On: 20-Sep-2026 - 12:32 PM</span>
                <span>Status: Not Submitted</span>
              </td>
              <td>
                <a href="/subtopic/view?sid=1298915&amp;vid=1305879&amp;cid=1298950&amp;cat_id=70954&amp;destination=my-activities">
                  Take Activity
                </a>
              </td>
            </tr></tbody></table>
            """.trimIndent(),
            "https://lms.isdm.org.in/",
        ).single()

        assertEquals("B10 - T1 - PMDL - Reflection 2", assessment.title)
        assertEquals("Not Submitted", assessment.status)
        assertEquals("1298915", assessment.sectionId)
        assertEquals("70954", assessment.courseId)
        assertEquals(
            "https://lms.isdm.org.in/subtopic/view?sid=1298915&vid=1305879&cid=1298950&cat_id=70954&destination=my-activities",
            assessment.submissionUrl,
        )
    }

    @Test
    fun `assessment page provides its true due date separately from its end date`() {
        val frameUrl = parseAssessmentFrameUrl(
            """
            <iframe id="iframe_load"
              src="/activity/user/attempt?nid=1305877&amp;assignment_nid=1305877&amp;videoid=1305879"></iframe>
            """.trimIndent(),
            "https://lms.isdm.org.in/",
        )
        val dates = parseAssessmentDates(
            """
            <div>Start Date : 17/08/2026</div>
            <div>Due Date : 20/08/2026</div>
            <div>End Date : 20/09/2026</div>
            """.trimIndent(),
        )

        assertEquals(
            "https://lms.isdm.org.in/activity/user/attempt?nid=1305877&assignment_nid=1305877&videoid=1305879",
            frameUrl,
        )
        assertEquals(LocalDate.of(2026, 8, 20), dates.dueDate)
        assertEquals(LocalDate.of(2026, 9, 20), dates.endDate)
    }

    @Test
    fun `submission is paired with the closest preceding downloadable assessment resource`() {
        val resource = parseAssessmentResource(
            """
            <a href="/subtopic/view?sid=1298915&amp;vid=1296029&amp;cid=1298950&amp;cat_id=70954">
              PMDL Assessment_Reflection Prompt 1_7th August
            </a>
            <a href="/download/video?sid=1298915&amp;vid=1296029&amp;cid=1298950&amp;cat_id=70954"></a>
            <a href="/subtopic/view?sid=1298915&amp;vid=1298954&amp;cid=1298950&amp;cat_id=70954">
              B10 - T0 - Reflection Prompt - Submission Link
            </a>
            <a href="/subtopic/view?sid=1298915&amp;vid=1305879&amp;cid=1298950&amp;cat_id=70954">
              Reflection Prompt 1 - Submission Link
            </a>
            <a href="/subtopic/view?sid=1298915&amp;vid=1306062&amp;cid=1298950&amp;cat_id=70954">Reflection Prompt 2</a>
            <a href="/download/video?sid=1298915&amp;vid=1306062&amp;cid=1298950&amp;cat_id=70954"></a>
            """.trimIndent(),
            submissionUrl = "https://lms.isdm.org.in/subtopic/view?sid=1298915&vid=1305879&cid=1298950&cat_id=70954",
            baseUrl = "https://lms.isdm.org.in/",
        )

        assertEquals("PMDL Assessment_Reflection Prompt 1_7th August", resource?.title)
        assertEquals(
            "https://lms.isdm.org.in/subtopic/view?sid=1298915&vid=1296029&cid=1298950&cat_id=70954",
            resource?.sourceUrl,
        )
    }
}
