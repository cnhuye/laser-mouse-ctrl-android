package com.scieford.laserctrlmouse.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.scieford.laserctrlmouse.utils.LogManager

/**
 * 设置管理器
 * 负责设置数据的存储和读取
 */
class SettingsManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "LaserCtrlMouseSettings"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_FRAME_RATE_MODE = "frame_rate_mode"
        private const val KEY_SELECTED_CAMERA_ID = "selected_camera_id"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_EXPOSURE_TIME_MS = "exposure_time_ms"
        private const val KEY_ISO_VALUE = "iso_value"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_MIN_BRIGHTNESS = "min_brightness"
        private const val KEY_ZOOM_RATIO = "zoom_ratio"
        private const val KEY_USER_LEVEL = "user_level"
        private const val TAG = "SettingsManager"
        
        @Volatile
        private var INSTANCE: SettingsManager? = null
        
        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    // 安全地获取应用上下文，如果不可用则使用传入的context
                    val safeContext = try {
                        context.applicationContext ?: context
                    } catch (e: Exception) {
                        LogManager.w(TAG, "无法获取applicationContext，使用传入的context: ${e.message}")
                        context
                    }
                    SettingsManager(safeContext).also { INSTANCE = it }
                }
            }
        }
    }
    
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 获取当前设置
     */
    fun getSettings(): SettingsData {
        // 始终返回保存的值，不根据extFeatureEnabled强制修改
        // extFeatureEnabled只控制UI的显示/隐藏，不应该影响已保存的设置值
        val savedFrameRateMode = FrameRateMode.values().find { 
            it.code == sharedPrefs.getString(KEY_FRAME_RATE_MODE, FrameRateMode.NORMAL.code) 
        } ?: FrameRateMode.NORMAL
        
        return SettingsData(
            language = Language.values().find { it.code == sharedPrefs.getString(KEY_LANGUAGE, Language.ENGLISH.code) } ?: Language.ENGLISH,
            frameRateMode = savedFrameRateMode,
            selectedCameraId = sharedPrefs.getString(KEY_SELECTED_CAMERA_ID, "0") ?: "0",
            resolution = Resolution.values().find { "${it.width}x${it.height}" == sharedPrefs.getString(KEY_RESOLUTION, "640x480") } ?: Resolution.VGA_640x480,
            exposureTimeMs = sharedPrefs.getFloat(KEY_EXPOSURE_TIME_MS, 8.0f),
            isoValue = sharedPrefs.getInt(KEY_ISO_VALUE, 100),
            threshold = sharedPrefs.getFloat(KEY_THRESHOLD, 0.85f),
            minBrightness = sharedPrefs.getFloat(KEY_MIN_BRIGHTNESS, 0.7f),
            zoomRatio = sharedPrefs.getFloat(KEY_ZOOM_RATIO, 1.0f),
            userLevel = UserLevel.values().find { it.code == sharedPrefs.getString(KEY_USER_LEVEL, UserLevel.NORMAL.code) } ?: UserLevel.NORMAL
        )
    }
    
    /**
     * 保存设置
     */
    fun saveSettings(settings: SettingsData) {
        LogManager.d(TAG, "保存设置: $settings")
        sharedPrefs.edit().apply {
            putString(KEY_LANGUAGE, settings.language.code)
            putString(KEY_FRAME_RATE_MODE, settings.frameRateMode.code)
            putString(KEY_SELECTED_CAMERA_ID, settings.selectedCameraId)
            putString(KEY_RESOLUTION, "${settings.resolution.width}x${settings.resolution.height}")
            putFloat(KEY_EXPOSURE_TIME_MS, settings.exposureTimeMs)
            putInt(KEY_ISO_VALUE, settings.isoValue)
            putFloat(KEY_THRESHOLD, settings.threshold)
            putFloat(KEY_MIN_BRIGHTNESS, settings.minBrightness)
            putFloat(KEY_ZOOM_RATIO, settings.zoomRatio)
            putString(KEY_USER_LEVEL, settings.userLevel.code)
            apply()
        }
    }
    
    /**
     * 更新单个设置项
     */
    fun updateLanguage(language: Language) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(language = language))
    }
    
    fun updateFrameRateMode(mode: FrameRateMode) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(frameRateMode = mode))
    }
    
    fun updateSelectedCamera(cameraId: String) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(selectedCameraId = cameraId))
    }
    
    fun updateResolution(resolution: Resolution) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(resolution = resolution))
    }
    
    fun updateExposureTime(exposureTimeMs: Float) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(exposureTimeMs = exposureTimeMs))
    }
    
    fun updateIsoValue(isoValue: Int) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(isoValue = isoValue))
    }
    
    fun updateThreshold(threshold: Float) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(threshold = threshold))
    }
    
    fun updateMinBrightness(minBrightness: Float) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(minBrightness = minBrightness))
    }
    
    fun updateZoomRatio(zoomRatio: Float) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(zoomRatio = zoomRatio))
    }
    
    fun updateUserLevel(userLevel: UserLevel) {
        val currentSettings = getSettings()
        saveSettings(currentSettings.copy(userLevel = userLevel))
    }
    
    /**
     * 重置为默认设置
     */
    fun resetToDefaults() {
        saveSettings(SettingsData())
    }
    
    /**
     * 获取当前用户级别（便捷方法）
     */
    fun getCurrentUserLevel(): UserLevel {
        return getSettings().userLevel
    }
    
    /**
     * 检查是否为VIP用户（便捷方法）
     */
    fun isVipUser(): Boolean {
        return getCurrentUserLevel() == UserLevel.VIP
    }
    
    /**
     * 升级为VIP用户（便捷方法）
     */
    fun upgradeToVip() {
        updateUserLevel(UserLevel.VIP)
        LogManager.d(TAG, "用户已升级为VIP")
    }
} 