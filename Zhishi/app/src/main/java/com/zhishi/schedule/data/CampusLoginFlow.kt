package com.zhishi.schedule.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class CampusLoginTarget { HOMEWORK, TIMETABLE }

/** Browser URLs only nominate a candidate. Completion requires successful read-only API verification. */
class CampusLoginFlow(private val graduate: Boolean, first: CampusLoginTarget) {
    private val order = listOf(first, CampusLoginTarget.entries.first { it != first })
    private var index = 0
    private var cancelled = false
    private var verificationStarted = false
    private val visits = mutableMapOf<String, Int>()
    private var navigationCount = 0
    val finished: Boolean get() = index == order.size
    val target: CampusLoginTarget? get() = if (cancelled) null else order.getOrNull(index)
    fun cancel() { cancelled = true }
    fun entry(): String = if (target == CampusLoginTarget.HOMEWORK) CampusUrls.LEARN_AUTH else CampusUrls.INFO_AUTH

    /** A page nominates a single read-only check per stage, even if it finishes or redirects repeatedly. */
    fun beginVerification(url: String): CampusLoginTarget? {
        if (verificationStarted) return null
        return candidate(url)?.also { verificationStarted = true }
    }

    /** Ignore query/fragment churn when detecting a redirect loop. Only explicit user retry resets this. */
    fun navigationAllowed(url: String): Boolean {
        if (target == null || !CampusUrls.allowedBrowser(url)) return false
        val u = url.toHttpUrlOrNull() ?: return false
        val key = u.host + u.encodedPath
        visits[key] = (visits[key] ?: 0) + 1
        return ++navigationCount <= 40 && visits.getValue(key) <= 5
    }

    companion object {
        /** Only an actual authentication page may be exposed; INFO/LEARN pages remain covered. */
        fun canShowVerificationPage(url: String): Boolean {
            if (!CampusUrls.allowedBrowser(url)) return false
            val u = url.toHttpUrlOrNull() ?: return false
            return u.host in setOf("id.tsinghua.edu.cn", "oauth.tsinghua.edu.cn") ||
                (u.host == "webvpn.tsinghua.edu.cn" && (u.encodedPath == "/login" || u.encodedPath.startsWith("/login/")))
        }
    }

    fun candidate(url: String): CampusLoginTarget? {
        if (finished || cancelled || !CampusUrls.allowedBrowser(url)) return null
        val u = url.toHttpUrlOrNull() ?: return null
        // Authentication in the direct website is not proof of the VPN session used by the app.
        if (u.host != CampusUrls.VPN.toHttpUrl().host) return null
        val path = u.encodedPath.trimEnd('/')
        return when (target) {
            CampusLoginTarget.HOMEWORK -> target.takeIf {
                path == "${CampusUrls.LEARN.toHttpUrl().encodedPath}/f/wlxt/index/course/student" ||
                    path == "${CampusUrls.LEARN.toHttpUrl().encodedPath}/f/wlxt/index/course/student/index"
            }
            CampusLoginTarget.TIMETABLE -> target.takeIf {
                path == "${CampusUrls.INFO.toHttpUrl().encodedPath}/f/info/gxfw_fg/common/index" ||
                    path == CampusUrls.entry(graduate).toHttpUrl().encodedPath ||
                    path == "${CampusUrls.TEACH.toHttpUrl().encodedPath}/jxmh.do" ||
                    path == "${CampusUrls.TEACH.toHttpUrl().encodedPath}/portal3rd.do"
            }
            null -> null
        }
    }

    /** Returns true only on the transition completing both services. Duplicate/stale results are ignored. */
    fun verified(service: CampusLoginTarget): Boolean {
        if (finished || cancelled || target != service) return false
        index++
        verificationStarted = false
        visits.clear(); navigationCount = 0
        return finished
    }
}
