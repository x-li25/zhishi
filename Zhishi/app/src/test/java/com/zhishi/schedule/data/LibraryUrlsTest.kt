package com.zhishi.schedule.data

import org.junit.Assert.*
import org.junit.Test

class LibraryUrlsTest {
    @Test fun `both official services and their vpn entries are supported`() {
        LibraryService.entries.forEach {
            assertTrue(LibraryUrls.allowed(it.officialUrl))
            assertTrue(LibraryUrls.allowed(it.vpnUrl))
            assertEquals(it.vpnUrl, LibraryUrls.navigation(it.officialUrl, true))
            assertEquals(it.officialUrl, LibraryUrls.navigation(it.officialUrl, false))
        }
    }
    @Test fun `school authentication is allowed without rewriting its destination`() {
        val url = "https://id.tsinghua.edu.cn/do/off/ui/auth/login/form/example?redirect=library"
        assertEquals(url, LibraryUrls.navigation(url, true))
    }
    @Test fun `vpn navigation preserves path query and room application fragment`() {
        val url = "https://cab.lib.tsinghua.edu.cn/path?redirect=a%2Fb#/reserve?date=2026-10-30"
        assertEquals(LibraryService.ROOMS.vpnUrl + "path?redirect=a%2Fb#/reserve?date=2026-10-30", LibraryUrls.navigation(url, true))
    }
    @Test fun `external deceptive insecure local and credential bearing links are rejected`() {
        listOf("https://seat.lib.tsinghua.edu.cn.evil.test/", "https://evil.test/", "http://evil.test/",
            "https://seat.lib.tsinghua.edu.cn:444/", "https://name:secret@seat.lib.tsinghua.edu.cn/", "file:///data/local/a",
            "content://files/document/1", "javascript:alert(1)", "intent://open", "not a URL").forEach {
            assertFalse(it, LibraryUrls.allowed(it)); assertNull(it, LibraryUrls.navigation(it, true))
        }
    }
    @Test fun `legacy official http links are upgraded before direct loading`() {
        LibraryService.entries.forEach {
            val oldLink = it.officialUrl.replace("https:", "http:") + "?callback=a%2Fb#reserve"
            assertFalse(LibraryUrls.allowed(oldLink))
            assertEquals(it.officialUrl + "?callback=a%2Fb#reserve", LibraryUrls.navigation(oldLink, false))
            assertEquals(it.vpnUrl + "?callback=a%2Fb#reserve", LibraryUrls.navigation(oldLink, true))
        }
    }
    @Test fun `legacy authentication links upgrade without proxying authentication`() {
        val path = "/do/off/ui/auth/login/form/example?return=a%2Fb"
        assertEquals("https://id.tsinghua.edu.cn$path", LibraryUrls.navigation("http://id.tsinghua.edu.cn$path", true))
        assertNull(LibraryUrls.navigation("http://id.tsinghua.edu.cn:8080$path", false))
        assertNull(LibraryUrls.navigation("http://name:secret@id.tsinghua.edu.cn$path", false))
    }
    @Test fun `actual seat cas and room authcenter routes retain callback parameters`() {
        listOf(
            "https://seat.lib.tsinghua.edu.cn/cas/index.php?callback=https%3A%2F%2Fseat.lib.tsinghua.edu.cn%2Fhome%2Fweb%2Ff_second",
            "https://cab.lib.tsinghua.edu.cn/authcenter/toLoginPage?redirectUrl=https%3A%2F%2Fcab.lib.tsinghua.edu.cn%2Fic-web%2Fauth%2Ftoken%3Fuuid%3Dexample&extInfo="
        ).forEach { assertEquals(it, LibraryUrls.navigation(it, false)) }
    }
    @Test fun `blocked link diagnostic never exposes tickets credentials paths or fragments`() {
        val message = LibraryUrls.unsupportedMessage("https://user:password@example.org/private?ticket=secret#token")
        assertTrue(message.contains("https://example.org:443"))
        listOf("user", "password", "private", "ticket", "secret", "token").forEach { assertFalse(message.contains(it)) }
        assertFalse(LibraryUrls.unsupportedMessage("intent://secret").contains("secret"))
    }
}
