package com.zhishi.schedule.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Invalidates in-flight reads and in-memory previews when a person changes or clears school login. */
object CampusSession {
    private val epoch = MutableStateFlow(0L)
    val changes = epoch.asStateFlow()
    private val logoutEpoch = MutableStateFlow(0L)
    val logouts = logoutEpoch.asStateFlow()
    fun signOut() { invalidate(); logoutEpoch.update { it + 1 } }
    fun invalidate() { epoch.update { it + 1 } }
    fun check(expected: Long) {
        check(epoch.value == expected) { "学校登录状态已更改，请重新读取" }
    }
}
