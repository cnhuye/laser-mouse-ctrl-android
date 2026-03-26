package com.scieford.laserctrlmouse

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.scieford.laserctrlmouse.settings.Language
import com.scieford.laserctrlmouse.settings.SettingsManager
import com.scieford.laserctrlmouse.utils.LogManager
import java.util.Locale
import android.util.Log
import android.app.Application.ActivityLifecycleCallbacks

/**
 * 自定义Application类，用于处理全局设置
 * 特别是语言设置的初始化和LogManager的初始化
 */
class LaserCtrlMouseApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // 首先初始化LogManager
        LogManager.initialize(this)
        LogManager.i(TAG, "Application启动，LogManager已初始化")
        
        // 在Application启动时立即应用语言设置
        applyLanguageSettingsOnStartup()
        
        // 注册Activity生命周期回调，确保每个Activity都应用正确的语言设置
        registerActivityLifecycleCallbacks(ActivityLanguageCallback())
    }

    /**
     * 在应用启动时应用语言设置
     */
    private fun applyLanguageSettingsOnStartup() {
        try {
            LogManager.d(TAG, "=== Application启动时应用语言设置 ===")
            
            // 获取保存的语言设置
            val settingsManager = SettingsManager.getInstance(this)
            val currentSettings = settingsManager.getSettings()
            val targetLanguage = when (currentSettings.language) {
                Language.ENGLISH -> "en"
                Language.CHINESE -> "zh"
            }
            
            LogManager.d(TAG, "读取到的语言设置: ${currentSettings.language} -> $targetLanguage")
            
            // 强制应用语言设置，确保生效
            LogManager.d(TAG, "强制应用语言设置: $targetLanguage")
            
            // 设置默认Locale
            val locale = Locale(targetLanguage)
            Locale.setDefault(locale)
            
            // 使用AppCompatDelegate设置应用语言
            val localeList = LocaleListCompat.forLanguageTags(targetLanguage)
            AppCompatDelegate.setApplicationLocales(localeList)
            
            // 强制设置系统配置（确保立即生效）
            val configuration = Configuration(resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                configuration.setLocale(locale)
                configuration.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                configuration.locale = locale
            }
            
            // 强制更新Application的resources配置
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
            
            LogManager.d(TAG, "已设置ApplicationLocales、默认Locale和强制更新配置: $targetLanguage")
            
            LogManager.d(TAG, "=== Application语言设置应用完成 ===")
        } catch (e: Exception) {
            LogManager.e(TAG, "Application启动时应用语言设置失败: ${e.message}", e)
        }
    }

    override fun attachBaseContext(base: Context?) {
        // 【关键】在最早的时机初始化LogManager
        base?.let { LogManager.earlyInit(it) }
        
        LogManager.d(TAG, "Application attachBaseContext") // 现在可以正常使用
        
        // 在attachBaseContext时应用语言设置（这是最早的时机）
        val contextWithLocale = base?.let { context ->
            try {
                LogManager.d(TAG, "尝试在attachBaseContext中应用语言设置")
                
                // 尝试获取设置，如果失败则使用默认语言（英文）
                val targetLanguage = try {
                    val settingsManager = SettingsManager.getInstance(context)
                    val currentSettings = settingsManager.getSettings()
                    val language = when (currentSettings.language) {
                        Language.ENGLISH -> "en"
                        Language.CHINESE -> "zh"
                    }
                    LogManager.d(TAG, "attachBaseContext - 成功读取语言设置: $language")
                    language
                } catch (e: Exception) {
                    LogManager.w(TAG, "attachBaseContext - 无法读取语言设置，使用默认英文: ${e.message}")
                    "en" // 默认使用英文
                }
                
                LogManager.d(TAG, "attachBaseContext - 目标语言: $targetLanguage")
                
                // 创建新的locale
                val locale = Locale(targetLanguage)
                Locale.setDefault(locale)
                
                // 更新Configuration
                val configuration = Configuration(context.resources.configuration)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    configuration.setLocale(locale)
                    configuration.setLocales(LocaleList(locale))
                } else {
                    @Suppress("DEPRECATION")
                    configuration.locale = locale
                }
                
                // 创建带有新配置的Context
                val newContext = context.createConfigurationContext(configuration)
                LogManager.d(TAG, "attachBaseContext - 已创建带有语言设置的Context: $targetLanguage")
                
                newContext
                
            } catch (e: Exception) {
                LogManager.e(TAG, "attachBaseContext中应用语言设置失败: ${e.message}", e)
                LogManager.d(TAG, "attachBaseContext - 使用原始context")
                context
            }
        } ?: base
        
        super.attachBaseContext(contextWithLocale)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LogManager.d(TAG, "Application配置变化: ${newConfig.locale}")
    }

    override fun onTerminate() {
        super.onTerminate()
        LogManager.i(TAG, "Application即将终止，清理日志文件")
        
        try {
            // 清理所有日志文件
            LogManager.getInstance()?.clearAllLogs()
            
            // 销毁LogManager
            LogManager.getInstance()?.destroy()
        } catch (e: Exception) {
            // 这里使用原生Log，因为LogManager可能已经被销毁
            Log.e(TAG, "清理日志文件失败: ${e.message}", e)
        }
    }
    
    /**
     * Activity生命周期回调，确保每个Activity都应用正确的语言设置
     */
    private class ActivityLanguageCallback : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
            LogManager.d(TAG, "Activity创建: ${activity::class.java.simpleName}")
            // 在Activity创建时确保语言设置正确
            updateActivityLanguage(activity)
        }
        
        override fun onActivityStarted(activity: android.app.Activity) {
            // 在Activity启动时再次检查语言设置
            updateActivityLanguage(activity)
        }
        
        override fun onActivityResumed(activity: android.app.Activity) {}
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivityStopped(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }
    
    /**
     * 全局语言设置工具
     */
    companion object {
        private const val TAG = "LaserCtrlMouseApp"
        
        /**
         * 获取目标语言代码
         */
        fun getTargetLanguageCode(context: Context): String {
            return try {
                val settingsManager = SettingsManager.getInstance(context)
                val currentSettings = settingsManager.getSettings()
                when (currentSettings.language) {
                    Language.ENGLISH -> "en"
                    Language.CHINESE -> "zh"
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "获取语言设置失败，使用默认英文: ${e.message}")
                "en" // 默认英文
            }
        }
        
        /**
         * 为指定Context应用语言设置
         */
        fun applyLanguageForContext(context: Context): Context {
            return try {
                val targetLanguage = getTargetLanguageCode(context)
                LogManager.d(TAG, "为Context应用语言设置: $targetLanguage")
                
                val locale = Locale(targetLanguage)
                Locale.setDefault(locale)
                
                val configuration = Configuration(context.resources.configuration)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    configuration.setLocale(locale)
                    configuration.setLocales(LocaleList(locale))
                } else {
                    @Suppress("DEPRECATION")
                    configuration.locale = locale
                }
                
                context.createConfigurationContext(configuration)
            } catch (e: Exception) {
                LogManager.e(TAG, "为Context应用语言设置失败: ${e.message}")
                context
            }
        }
        
        /**
         * 强制更新Activity的语言设置（用于配置变化时）
         */
        fun updateActivityLanguage(activity: android.app.Activity) {
            try {
                val targetLanguage = getTargetLanguageCode(activity)
                LogManager.d(TAG, "更新Activity语言设置: $targetLanguage")
                
                val locale = Locale(targetLanguage)
                Locale.setDefault(locale)
                
                val configuration = Configuration(activity.resources.configuration)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    configuration.setLocale(locale)
                    configuration.setLocales(LocaleList(locale))
                } else {
                    @Suppress("DEPRECATION")
                    configuration.locale = locale
                }
                
                // 强制更新Activity的resources配置
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                
                LogManager.d(TAG, "Activity语言设置更新完成: $targetLanguage")
            } catch (e: Exception) {
                LogManager.e(TAG, "更新Activity语言设置失败: ${e.message}")
            }
        }
    }
} 