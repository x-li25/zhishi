package com.zhishi.schedule.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Duplicate onPageFinished callbacks are not a rejected password or another submission opportunity. */
class CampusAutofillAttempt {
    private val attempted = mutableSetOf<String>()
    private val submitted = mutableMapOf<String, Long>()
    fun claim(url: String): Boolean = CampusCredentialAutofill.supports(url) && attempted.add(url.substringBefore('?'))
    fun submitted(url: String, navigation: Long) { submitted[url.substringBefore('?')] = navigation }
    fun returnedToForm(url: String, navigation: Long): Boolean {
        if (!CampusUrls.allowedBrowser(url)) return false
        val target = url.toHttpUrlOrNull() ?: return false
        if (target.host != "id.tsinghua.edu.cn") return false
        return if (target.encodedPath == "/do/off/ui/auth/login/check") submitted.values.any { navigation > it }
        else CampusCredentialAutofill.supports(url) && submitted[url.substringBefore('?')]?.let { navigation > it } == true
    }
}
