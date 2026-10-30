package com.zhishi.schedule.data

import org.junit.Assert.*
import org.junit.Test

class CampusAutofillAttemptTest {
    @Test fun `duplicate form finished callback is neither resubmitted nor mistaken for rejection`() {
        val attempt = CampusAutofillAttempt()
        assertTrue(attempt.claim(CampusUrls.INFO_AUTH))
        attempt.submitted(CampusUrls.INFO_AUTH, 2)
        assertFalse(attempt.claim(CampusUrls.INFO_AUTH))
        assertFalse(attempt.claim(CampusUrls.INFO_AUTH + "?retry=1"))
        assertFalse(attempt.returnedToForm(CampusUrls.INFO_AUTH, 2))
        assertTrue(attempt.returnedToForm(CampusUrls.INFO_AUTH, 3))
        assertTrue(attempt.returnedToForm("https://id.tsinghua.edu.cn/do/off/ui/auth/login/check", 3))
    }
    @Test fun `captcha not yet submitted cannot reject saved credentials`() {
        val attempt = CampusAutofillAttempt()
        assertTrue(attempt.claim(CampusUrls.INFO_AUTH))
        assertFalse(attempt.returnedToForm(CampusUrls.INFO_AUTH, 10))
        assertFalse(attempt.returnedToForm("https://id.tsinghua.edu.cn/do/off/ui/auth/login/check", 10))
    }
    @Test fun `second service form is independent and info or deceptive hosts never reject credentials`() {
        val attempt = CampusAutofillAttempt()
        assertTrue(attempt.claim(CampusUrls.INFO_AUTH)); attempt.submitted(CampusUrls.INFO_AUTH, 1)
        assertTrue(attempt.claim(CampusUrls.LEARN_AUTH))
        assertFalse(attempt.returnedToForm(CampusUrls.LEARN_AUTH, 3))
        assertFalse(attempt.returnedToForm(CampusUrls.INFO, 4))
        assertFalse(attempt.returnedToForm("https://id.tsinghua.edu.cn.evil.example/do/off/ui/auth/login/check", 4))
        assertFalse(attempt.claim(CampusUrls.INFO))
    }
}
