package org.isdm.companion.ui

class ReadingUrlPolicyTest {
    @org.junit.Test
    fun validatesNavigationBoundaries() {
    var checks = 0
    fun verify(value: Boolean) { check(value); checks++ }
    val allowed = listOf("https://lms.isdm.org.in/home", "https://LMS.ISDM.ORG.IN:443/course?vid=123")
    allowed.forEach { verify(ReadingUrlPolicy.isTrustedLmsUrl(it)) }
    val blocked = listOf(null, "", "javascript:alert(1)", "file:///private.txt", "content://lms.isdm.org.in/x",
        "intent://lms.isdm.org.in/x", "http://lms.isdm.org.in/x", "https://lms.isdm.org.in.evil.test/x",
        "https://evil.test/?lms.isdm.org.in", "https://user@lms.isdm.org.in/x", "https://lms.isdm.org.in:8443/x",
        "https://lms.isdm.org.in\\@evil.test/x", "https://lms.isdm.org.in/x\n", "//lms.isdm.org.in/x")
    blocked.forEach { verify(!ReadingUrlPolicy.isTrustedLmsUrl(it)) }
    verify(ReadingUrlPolicy.destination("https://lms.isdm.org.in/home", true) == ReadingUrlPolicy.Destination.WEBVIEW)
    verify(ReadingUrlPolicy.destination("https://documents.example.test/book.pdf", true) == ReadingUrlPolicy.Destination.EXTERNAL_BROWSER)
    verify(ReadingUrlPolicy.destination("https://documents.example.test/book.pdf", false) == ReadingUrlPolicy.Destination.WEBVIEW)
    verify(ReadingUrlPolicy.destination("http://documents.example.test/book.pdf", false) == ReadingUrlPolicy.Destination.BLOCK)
    for (url in listOf("file:///tmp/a", "content://provider/item", "javascript:alert(1)", "intent://test", "mailto:x@example.test")) {
        verify(!ReadingUrlPolicy.isExternalWebUrl(url))
        verify(ReadingUrlPolicy.destination(url, false) == ReadingUrlPolicy.Destination.BLOCK)
    }
    verify(ReadingUrlPolicy.isExternalWebUrl("https://example.test:8443/a"))
    verify(!ReadingUrlPolicy.isExternalWebUrl("https://example.test:70000/a"))
    verify(!ReadingUrlPolicy.isExternalWebUrl("https://user:password@example.test/a"))
    check(checks > 0)
}
}
