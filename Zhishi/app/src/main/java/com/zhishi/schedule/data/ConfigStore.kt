package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.GlmConfig

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("zhishi_settings", Context.MODE_PRIVATE)
    private val secretStore = SecretStore()

    /** 解密失败时抛出 [IllegalStateException]，由调用方提示用户重新填写 API Key。 */
    fun loadGlmConfig(): GlmConfig = GlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL,
        apiKey = secretStore.decrypt(prefs.getString(KEY_API_KEY, "").orEmpty()),
        model = prefs.getString(KEY_MODEL, null) ?: DEFAULT_MODEL,
        responseJsonMode = prefs.getBoolean(KEY_JSON_MODE, true),
        // 图片已从独立 OCR 改为直接理解日程，升级后默认跟随已保存的对话模型。
        // 原来的 glm_vision_model 属于 OCR 设置，不自动用于新流程。
        visionModel = prefs.getString(KEY_IMAGE_MODEL, null).orEmpty()
    )

    fun saveGlmConfig(config: GlmConfig) {
        val encryptedKey = secretStore.encrypt(config.apiKey.trim())
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(KEY_API_KEY, encryptedKey)
            .putString(KEY_MODEL, config.model.trim())
            .putBoolean(KEY_JSON_MODE, config.responseJsonMode)
            .putString(KEY_IMAGE_MODEL, config.visionModel.trim())
            .apply()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL = "glm-5.3-flash"
        private const val KEY_BASE_URL = "glm_base_url"
        private const val KEY_API_KEY = "glm_api_key"
        private const val KEY_MODEL = "glm_model"
        private const val KEY_JSON_MODE = "glm_json_mode"
        private const val KEY_IMAGE_MODEL = "glm_image_schedule_model"
    }
}
