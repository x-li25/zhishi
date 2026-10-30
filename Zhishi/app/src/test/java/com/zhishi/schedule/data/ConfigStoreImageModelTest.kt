package com.zhishi.schedule.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigStoreImageModelTest {
    @Test
    fun `upgrade follows saved FlashX model instead of legacy OCR model without changing saved URL`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("zhishi_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("glm_model", "glm-5.3-flashx")
            .putString("glm_base_url", "https://provider.test/v1")
            .putString("glm_vision_model", "old-ocr-model").commit()
        val config = ConfigStore(context).loadGlmConfig()
        assertEquals("glm-5.3-flashx", config.model)
        assertEquals("glm-5.3-flashx", config.imageModel)
        assertEquals("https://provider.test/v1", config.baseUrl)
        assertEquals("", config.visionModel)
        assertEquals("old-ocr-model", prefs.getString("glm_vision_model", null))
    }

    @Test
    fun `explicit image model persists and can be reset to follow chat model`() {
        val context = RuntimeEnvironment.getApplication()
        val store = ConfigStore(context)
        val config = store.loadGlmConfig().copy(model = "glm-5.3-flashx", visionModel = "custom-vision")
        store.saveGlmConfig(config)
        assertEquals("custom-vision", ConfigStore(context).loadGlmConfig().imageModel)
        store.saveGlmConfig(config.copy(visionModel = ""))
        assertEquals("glm-5.3-flashx", ConfigStore(context).loadGlmConfig().imageModel)
    }
}
