package org.isdm.companion.data

import org.isdm.companion.engine.LmsCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LmsFacultyProfileParserTest {
    private val course = LmsCourse("70954", "Term 1")
    private val baseUrl = "https://lms.example/"

    @Test
    fun `live LMS topic attributes produce an authenticated faculty profile link`() {
        val profiles = parseFacultyProfiles(
            coursePage(
                courseTitle = "Radical Transformational Leadership - Workshop",
                topics = """
                    <li class="single_content_image_show left-topic-title-wrapper" nid="1298695"
                        first_video_nid="1298955" clipping_type="2" course_id="1298622" cat_id="70954">
                      <div class="left-topic-title" title="Faculty Profile_Sudarshan Rodriguez">
                        Faculty Profile_Sudarshan Rodriguez
                      </div>
                    </li>
                """.trimIndent(),
            ),
            course,
            baseUrl,
        )

        val profile = profiles.single()
        assertEquals("70954", profile.courseCatId)
        assertEquals("Term 1", profile.courseName)
        assertEquals("Sudarshan Rodriguez", profile.displayName)
        assertEquals("Faculty Profile_Sudarshan Rodriguez", profile.itemTitle)
        assertEquals(
            "https://lms.example/subtopic/view?sid=1298695&vid=1298955&cid=1298622&cat_id=70954",
            profile.sourceUrl,
        )
    }

    @Test
    fun `plain faculty profile uses its enclosing LMS course label as the name`() {
        val profile = parseFacultyProfiles(
            coursePage(
                courseTitle = "Jahnvi Andharia",
                topics = """
                    <li nid="91" first_video_nid="501" course_id="7" cat_id="70954">
                      <div class="left-topic-title" title="Faculty Profile">Faculty Profile</div>
                    </li>
                """.trimIndent(),
            ),
            course,
            baseUrl,
        ).single()

        assertEquals("Jahnvi Andharia", profile.displayName)
    }

    @Test
    fun `non faculty topics and mismatched categories are ignored`() {
        val profiles = parseFacultyProfiles(
            coursePage(
                courseTitle = "Jahnvi Andharia",
                topics = """
                    <li nid="91" first_video_nid="501" course_id="7" cat_id="70954">
                      <div class="left-topic-title" title="Course Readings">Course Readings</div>
                    </li>
                    <li nid="92" first_video_nid="502" course_id="7" cat_id="99999">
                      <div class="left-topic-title" title="Faculty Profile_Test Person">Faculty Profile_Test Person</div>
                    </li>
                """.trimIndent(),
            ),
            course,
            baseUrl,
        )

        assertTrue(profiles.isEmpty())
    }

    @Test
    fun `invalid identifiers and non https bases are rejected`() {
        val html = coursePage(
            courseTitle = "Jahnvi Andharia",
            topics = """
                <li nid="not-a-number" first_video_nid="501" course_id="7" cat_id="70954">
                  <div class="left-topic-title" title="Faculty Profile">Faculty Profile</div>
                </li>
            """.trimIndent(),
        )

        assertTrue(parseFacultyProfiles(html, course, baseUrl).isEmpty())
        assertTrue(parseFacultyProfiles(html, course, "http://lms.example/").isEmpty())
    }

    private fun coursePage(courseTitle: String, topics: String): String = """
        <li id="7" class="category_left_nav_course">
          <a class="subject_course_title" href="/course/details?cat_id=70954&amp;course_id=7"
             title="$courseTitle" data-course-id="7"><span class="chapter-title">$courseTitle</span></a>
          <ul>$topics</ul>
        </li>
    """.trimIndent()
}
