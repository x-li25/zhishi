package com.zhishi.schedule.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CampusCredentialAutofillTest {
    @Test fun `credentials are allowed only in the official secure form`() {
        assertTrue(CampusCredentialAutofill.supports(CampusUrls.INFO_AUTH))
        assertTrue(CampusCredentialAutofill.supports(CampusUrls.LEARN_AUTH))
        listOf(CampusUrls.VPN, CampusUrls.INFO_AUTH.replace("https:", "http:"),
            CampusUrls.INFO_AUTH.replace("id.tsinghua.edu.cn", "id.tsinghua.edu.cn.evil.example"),
            CampusUrls.INFO_AUTH.replace("id.tsinghua.edu.cn", "id.tsinghua.edu.cn:444"),
            CampusUrls.INFO_AUTH.replace("id.tsinghua.edu.cn", "someone@id.tsinghua.edu.cn"),
            "https://id.tsinghua.edu.cn/do/off/ui/auth/login/check").forEach {
            assertFalse(it, CampusCredentialAutofill.supports(it))
        }
    }
    @Test fun `autofill uses safe string literals and delegates encryption to official scripts`() {
        val credentials = CampusCredentials("student", "\"\\\n');window.bad=true;//")
        val script = CampusCredentialAutofill.script(CampusUrls.INFO_AUTH, credentials)
        assertTrue(script.contains("p.value = " + org.json.JSONObject.quote(credentials.password)))
        assertTrue(script.contains("location.origin !== 'https://id.tsinghua.edu.cn'"))
        assertTrue(script.contains("a.pathname !== '/do/off/ui/auth/login/check'"))
        assertTrue(script.contains("window.doLogin()"))
        assertFalse(script.contains("fetch("))
        assertFalse(script.contains("f.submit()"))
        assertFalse(credentials.toString().contains("student"))
        assertFalse(credentials.toString().contains(credentials.password))
    }
}
