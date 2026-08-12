package org.isdm.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingRouteTest {
    @Test
    fun `extracts the requested LMS reading resource`() {
        assertEquals(
            "1298349",
            readingResourceId(
                "https://lms.isdm.org.in/subtopic/view?sid=1296141&vid=1298349&cid=1296339&cat_id=70952",
            ),
        )
    }

    @Test
    fun `rejects foreign or malformed reading resources`() {
        assertNull(readingResourceId("https://example.com/subtopic/view?vid=1298349"))
        assertNull(readingResourceId("https://lms.isdm.org.in/subtopic/view?vid=not-a-number"))
    }
}
