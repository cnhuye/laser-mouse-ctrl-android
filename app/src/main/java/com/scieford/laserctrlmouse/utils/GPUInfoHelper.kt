package com.scieford.laserctrlmouse.utils

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import javax.microedition.khronos.egl.EGL10

/**
 * GPU信息获取工具类
 */
object GPUInfoHelper {
    
    private const val TAG = "GPUInfoHelper"
    
    /**
     * 获取GPU信息
     * @return GPU信息字符串
     */
    fun getGPUInfo(): String {
        return try {
            // 尝试通过OpenGL ES获取GPU信息
            val gpuInfo = getOpenGLGPUInfo()
            if (gpuInfo.isNotEmpty()) {
                LogManager.d(TAG, "通过OpenGL ES获取GPU信息成功: $gpuInfo")
                gpuInfo
            } else {
                // 如果OpenGL方式失败，使用系统属性获取
                val fallbackInfo = getSystemPropertyGPUInfo()
                LogManager.d(TAG, "使用系统属性获取GPU信息: $fallbackInfo")
                fallbackInfo
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "获取GPU信息失败: ${e.message}", e)
            "Unknown GPU"
        }
    }
    
    /**
     * 通过OpenGL ES获取GPU信息
     */
    private fun getOpenGLGPUInfo(): String {
        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        
        try {
            // 获取默认显示
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                LogManager.w(TAG, "无法获取EGL显示")
                return ""
            }
            
            // 初始化EGL
            val eglVersion = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, eglVersion, 0, eglVersion, 1)) {
                LogManager.w(TAG, "EGL初始化失败")
                return ""
            }
            
            // 配置EGL
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0,
                    configs, 0, configs.size, numConfigs, 0)) {
                LogManager.w(TAG, "EGL配置选择失败")
                return ""
            }
            
            val config = configs[0] ?: return ""
            
            // 创建EGL上下文
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            
            eglContext = EGL14.eglCreateContext(eglDisplay, config,
                EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                LogManager.w(TAG, "EGL上下文创建失败")
                return ""
            }
            
            // 创建离屏表面
            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                LogManager.w(TAG, "EGL表面创建失败")
                return ""
            }
            
            // 激活上下文
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                LogManager.w(TAG, "EGL上下文激活失败")
                return ""
            }
            
            // 获取GPU信息
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown"
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown"
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "Unknown"
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            
            // 格式化GPU信息
            val gpuInfo = buildString {
                append("Vendor: $vendor")
                append(", Renderer: $renderer")
                append(", Version: $glVersion")
                
                // 添加一些有用的扩展信息（如果存在）
                val importantExtensions = listOf(
                    "GL_EXT_texture_compression_s3tc",
                    "GL_OES_texture_compression_astc",
                    "GL_EXT_texture_filter_anisotropic",
                    "GL_OES_vertex_array_object"
                )
                
                val supportedExtensions = importantExtensions.filter { 
                    extensions.contains(it) 
                }
                
                if (supportedExtensions.isNotEmpty()) {
                    append(", Extensions: ${supportedExtensions.joinToString(", ")}")
                }
            }
            
            return gpuInfo
            
        } catch (e: Exception) {
            LogManager.e(TAG, "OpenGL ES获取GPU信息异常: ${e.message}", e)
            return ""
        } finally {
            // 清理资源
            try {
                if (eglDisplay != null) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, 
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                }
                
                if (eglSurface != null && eglDisplay != null) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                
                if (eglContext != null && eglDisplay != null) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                
                if (eglDisplay != null) {
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "清理EGL资源异常: ${e.message}")
            }
        }
    }
    
    /**
     * 通过系统属性获取GPU信息（备用方案）
     */
    private fun getSystemPropertyGPUInfo(): String {
        return try {
            val properties = mutableListOf<String>()
            
            // 尝试读取一些常见的GPU相关属性
            val gpuProperties = listOf(
                "ro.hardware.vulkan", 
                "ro.hardware.egl",
                "ro.opengles.version",
                "ro.hardware",
                "ro.board.platform",
                "ro.chipname"
            )
            
            for (prop in gpuProperties) {
                try {
                    val value = getSystemProperty(prop)
                    if (value.isNotEmpty()) {
                        properties.add("$prop: $value")
                    }
                } catch (e: Exception) {
                    // 忽略单个属性读取失败
                }
            }
            
            if (properties.isNotEmpty()) {
                properties.joinToString(", ")
            } else {
                "System GPU info unavailable"
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "获取系统属性GPU信息失败: ${e.message}")
            "Unknown GPU (System Property Error)"
        }
    }
    
    /**
     * 获取系统属性值
     */
    private fun getSystemProperty(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            result
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * 获取简化的GPU信息（用于日志显示）
     */
    fun getSimpleGPUInfo(): String {
        val fullInfo = getGPUInfo()
        return try {
            // 尝试提取渲染器信息（通常是最有用的部分）
            val rendererRegex = Regex("Renderer:\\s*([^,]+)")
            val match = rendererRegex.find(fullInfo)
            match?.groupValues?.get(1)?.trim() ?: fullInfo.take(50) + "..."
        } catch (e: Exception) {
            fullInfo.take(30) + "..."
        }
    }
} 