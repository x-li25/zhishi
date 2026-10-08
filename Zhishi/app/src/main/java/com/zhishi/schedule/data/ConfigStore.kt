package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.GlmConfig
import com.zhishi.schedule.domain.SchedulePreferences
import java.time.LocalTime

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("zhishi_settings", Context.MODE_PRIVATE)
    private val secretStore = SecretStore()

    /** 解密失败时抛出 [IllegalStateException]，由调用方提示用户重新填写 API Key。 */
    fun loadGlmConfig(): GlmConfig = GlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL,
        apiKey = secretStore.decrypt(prefs.getString(KEY_API_KEY, "").orEmpty()),
        model = prefs.getString(KEY_MODEL, null) ?: DEFAULT_MODEL,
        responseJsonMode = prefs.getBoolean(KEY_JSON_MODE, true)
    )

    fun saveGlmConfig(config: GlmConfig) {
        val encryptedKey = secretStore.encrypt(config.apiKey.trim())
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(KEY_API_KEY, encryptedKey)
            .putString(KEY_MODEL, config.model.trim())
            .putBoolean(KEY_JSON_MODE, config.responseJsonMode)
            .apply()
    }

    fun loadSchedulePreferences(): SchedulePreferences = SchedulePreferences(
        dayStart = loadTime(KEY_DAY_START, LocalTime.of(7, 0)),
        dayEnd = loadTime(KEY_DAY_END, LocalTime.of(23, 0)),
        studyStart = loadTime(KEY_STUDY_START, LocalTime.of(8, 0)),
        studyEnd = loadTime(KEY_STUDY_END, LocalTime.of(22, 0)),
        lunchStart = loadTime(KEY_LUNCH_START, LocalTime.of(11, 30)),
        lunchEnd = loadTime(KEY_LUNCH_END, LocalTime.of(13, 30)),
        dinnerStart = loadTime(KEY_DINNER_START, LocalTime.of(17, 0)),
        dinnerEnd = loadTime(KEY_DINNER_END, LocalTime.of(19, 30)),
        minStudyMinutes = prefs.getInt(KEY_MIN_STUDY, 30),
        bufferMinutes = prefs.getInt(KEY_BUFFER, 10)
    )

    fun saveSchedulePreferences(value: SchedulePreferences) {
        prefs.edit()
            .putString(KEY_DAY_START, value.dayStart.toString())
            .putString(KEY_DAY_END, value.dayEnd.toString())
            .putString(KEY_STUDY_START, value.studyStart.toString())
            .putString(KEY_STUDY_END, value.studyEnd.toString())
            .putString(KEY_LUNCH_START, value.lunchStart.toString())
            .putString(KEY_LUNCH_END, value.lunchEnd.toString())
            .putString(KEY_DINNER_START, value.dinnerStart.toString())
            .putString(KEY_DINNER_END, value.dinnerEnd.toString())
            .putInt(KEY_MIN_STUDY, value.minStudyMinutes)
            .putInt(KEY_BUFFER, value.bufferMinutes)
            .apply()
    }

    private fun loadTime(key: String, fallback: LocalTime): LocalTime =
        runCatching { LocalTime.parse(prefs.getString(key, null) ?: fallback.toString()) }
            .getOrDefault(fallback)

    companion object {
        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL = "glm-5.3-flash"
        private const val KEY_BASE_URL = "glm_base_url"
        private const val KEY_API_KEY = "glm_api_key"
        private const val KEY_MODEL = "glm_model"
        private const val KEY_JSON_MODE = "glm_json_mode"
        private const val KEY_DAY_START = "day_start"
        private const val KEY_DAY_END = "day_end"
        private const val KEY_STUDY_START = "study_start"
        private const val KEY_STUDY_END = "study_end"
        private const val KEY_LUNCH_START = "lunch_start"
        private const val KEY_LUNCH_END = "lunch_end"
        private const val KEY_DINNER_START = "dinner_start"
        private const val KEY_DINNER_END = "dinner_end"
        private const val KEY_MIN_STUDY = "min_study"
        private const val KEY_BUFFER = "buffer"
    }
}
