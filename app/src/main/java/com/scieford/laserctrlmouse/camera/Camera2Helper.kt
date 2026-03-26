package com.scieford.laserctrlmouse.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.scieford.laserctrlmouse.MainActivity
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import com.scieford.laserctrlmouse.settings.CameraInfo
import com.scieford.laserctrlmouse.settings.Resolution
import com.scieford.laserctrlmouse.settings.SettingsManager
import android.graphics.Rect
import com.scieford.laserctrlmouse.utils.LogManager

/**
 * 曝光模式枚举
 */
enum class ExposureMode {
    AUTO,       // 自动曝光
    MANUAL      // 手动曝光
}

/**
 * Camera2 帮助类 - 专门优化高速帧率
 */
class Camera2Helper(private val context: Context, private val settingsManager: SettingsManager) {
    companion object {
        private const val TAG = "Camera2Helper"
    }
    
    /**
     * 图像帧回调接口 - 用于onCaptureCompleted时的回调
     */
    interface FrameCallback {
        /**
         * 当捕获到新帧时回调
         * @param result 捕获结果
         * @param timestamp 时间戳
         * @return 是否处理成功
         */
        fun onFrameCaptured(): Boolean
    }
    
    /**
     * 图像数据回调接口 - 用于处理ImageReader获取的图像数据
     */
    interface ImageCallback {
        /**
         * 当获取到新图像时回调
         * @param image 图像数据
         * @param timestamp 时间戳
         * @return 是否处理成功
         */
        fun onImageAvailable(image: Image, timestamp: Long): Boolean
    }
    
    /**
     * 摄像头状态回调接口 - 用于通知摄像头状态变化
     */
    interface CameraStateCallback {
        /**
         * 摄像头完全启动并开始预览时回调
         */
        fun onCameraReady()
        
        /**
         * 摄像头关闭时回调
         */
        fun onCameraClosed()
        
        /**
         * 摄像头出错时回调
         * @param error 错误代码
         */
        fun onCameraError(error: Int)
    }
    
    // 摄像头管理器
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    
    // 后台线程
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // 摄像头资源
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // 当前摄像头配置 - 从设置中读取默认值，而不是硬编码为"0"
    private var currentCameraId: String
    
    // 当前曝光时间设置 (纳秒)
    private var currentExposureTimeNs = 1_000_000L // 默认1ms
    
    private var previewSize: Size
        get() {
            val resolution = MainActivity.getCameraResolution()
            return Size(resolution.first, resolution.second)
        }
        set(value) {
            // 不允许设置，始终从MainActivity获取
        }
    private var fpsRange: Range<Int>? = null
    
    // 传感器参数
    private var maxIso = 1600
    private var minExposureTime = 1000000L // 1ms
    private var maxExposureTime = 1000000000L // 1s
    
    // 帧率统计
    private var lastFrameTimestamp = 0L
    private var frameCount = 0
    private var lastFpsUpdateTime = 0L
    private var currentFps = 0.0
    
    // 帧率回调
    private var frameRateListener: ((Double) -> Unit)? = null
    
    // 全局执行标记，用于表示当前是否有一帧数据正在处理
    private val isFrameProcessing = AtomicBoolean(false)

    // 图像处理模式 - 是否使用激光点检测模式
    private var isLaserDetectMode = false

    // 最大图像处理器数量
    private val MAX_IMAGES = 2
    
    // 是否正在运行
    private var isRunning = false

    // GLSurfaceView引用，用于调试SurfaceTexture在正确的OpenGL线程中更新
    private var glSurfaceView: GLCameraSurfaceView? = null

    // 数字变焦相关属性
    private var maxDigitalZoom = 1.0f
    private var activeArraySize: Rect? = null
    private var currentZoomRatio = 1.0f
    private var zoomRatioListener: ((Float) -> Unit)? = null

    init {
        // 在初始化时从设置中读取摄像头ID和变焦比例
        try {
            val settings = settingsManager.getSettings()
            
            // 设置摄像头ID，如果设置中没有则使用"0"作为默认值
            currentCameraId = settings.selectedCameraId.ifEmpty { "0" }
            LogManager.d(TAG, "从设置中读取摄像头ID: $currentCameraId")
            
            // 设置变焦比例
            currentZoomRatio = settings.zoomRatio
            LogManager.d(TAG, "从设置中恢复变焦比例: $currentZoomRatio")
            
            // 设置曝光时间
            currentExposureTimeNs = (settings.exposureTimeMs * 1_000_000).toLong()
            LogManager.d(TAG, "从设置中读取曝光时间: ${settings.exposureTimeMs}ms (${currentExposureTimeNs}ns)")
            
        } catch (e: Exception) {
            LogManager.w(TAG, "从设置中读取摄像头参数失败，使用默认值: ${e.message}")
            currentCameraId = "0" // 默认摄像头ID
            currentZoomRatio = 1.0f // 默认变焦比例
            currentExposureTimeNs = 1_000_000L // 默认曝光时间1ms
        }
    }
    
    /**
     * 设置GLSurfaceView引用，用于调试SurfaceTexture
     */
    fun setGLSurfaceView(surfaceView: GLCameraSurfaceView?) {
        this.glSurfaceView = surfaceView
        LogManager.d(TAG, "设置GLSurfaceView引用: ${surfaceView != null}")
    }

    /**
     * 设置激光点检测模式
     */
    fun setLaserDetectMode(enabled: Boolean) {
        this.isLaserDetectMode = enabled
        LogManager.d(TAG, "设置激光点检测模式: $enabled")
    }
    
    /**
     * 设置帧率监听器
     */
    fun setFrameRateListener(listener: ((Double) -> Unit)?) {
        this.frameRateListener = listener
        LogManager.d(TAG, "设置帧率监听器: ${listener != null}")
        
        // 如果设置了监听器，立即回调当前帧率
        if (listener != null) {
            listener.invoke(currentFps)
            LogManager.d(TAG, "立即回调当前帧率: $currentFps fps")
            
            // 如果当前帧率为0，可能是刚刚重置，等待一段时间后再次尝试
            if (currentFps == 0.0) {
                LogManager.d(TAG, "当前帧率为0，可能是刚刚重置，等待帧率更新")
                // 这里不需要额外操作，updateFrameProcessingStats会在有新帧时自动更新
            }
        }
    }
    
    /**
     * 获取当前帧率
     */
    fun getCurrentFps(): Double {
        return currentFps
    }
    
    /**
     * 设置变焦比例监听器
     */
    fun setZoomRatioListener(listener: ((Float) -> Unit)?) {
        this.zoomRatioListener = listener
        LogManager.d(TAG, "设置变焦比例监听器: ${listener != null}")
    }
    
    /**
     * 获取最佳高速模式尺寸
     * 外部调用此方法来获取摄像头支持的最佳高速尺寸
     */
    fun getBestHighSpeedSize(): Size {
        return try {
            val bestMode = findBestHighSpeedMode()
            bestMode.second
        } catch (e: Exception) {
            LogManager.e(TAG, "获取最佳高速尺寸失败: ${e.message}")
            val resolution = MainActivity.getCameraResolution()
            Size(resolution.first, resolution.second) // 返回动态默认尺寸
        }
    }
    
    /**
     * 开始后台线程
     */
    private fun startBackgroundThread() {
        // 先检查是否已经存在线程
        if (backgroundThread?.isAlive == true) {
            LogManager.d(TAG, "后台线程已经运行，无需重新启动")
            return
        }
        
        try {
            // 停止任何可能仍在运行的旧线程
            stopBackgroundThread()
            
            // 创建并启动新线程
            backgroundThread = HandlerThread("CameraBackground").also { 
                it.start()
                backgroundHandler = Handler(it.looper)
                LogManager.d(TAG, "后台线程已启动")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "启动后台线程失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 停止后台线程
     */
    private fun stopBackgroundThread() {
        try {
            val thread = backgroundThread
            if (thread != null) {
                // 使用局部变量，防止在操作过程中被置空
                thread.quitSafely()
                thread.join(500) // 最多等待500ms
                LogManager.d(TAG, "后台线程已停止")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "停止线程异常: ${e.message}")
            e.printStackTrace()
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }
    

    /**
     * 计算和更新当前帧率
     */
    private fun updateFrameProcessingStats() {
        val currentTime = System.currentTimeMillis()
        
        // 初始化时间戳
        if (lastFpsUpdateTime == 0L) {
            lastFpsUpdateTime = currentTime
            frameCount = 0
            LogManager.d(TAG, "初始化帧率统计，监听器状态: ${frameRateListener != null}")
            return
        }
        
        frameCount++
        
        // 每秒更新一次FPS
        if (currentTime - lastFpsUpdateTime >= 1000) {
            // 计算当前FPS
            val elapsedSeconds = (currentTime - lastFpsUpdateTime) / 1000.0
            currentFps = frameCount / elapsedSeconds
            
            LogManager.d(TAG, "当前帧率: ${String.format("%.2f", currentFps)}fps (${frameCount}帧/${String.format("%.1f", elapsedSeconds)}秒)")
            
            // 调用监听器
            if (frameRateListener != null) {
                frameRateListener?.invoke(currentFps)
//                LogManager.d(TAG, "帧率监听器已调用，帧率: ${String.format("%.2f", currentFps)}fps")
            } else {
//                LogManager.w(TAG, "帧率监听器为null，无法更新UI")
            }
            
            // 重置计数器
            frameCount = 0
            lastFpsUpdateTime = currentTime
        }
    }
    

    /**
     * 找到设备支持的最佳高速模式（摄像头ID，固定分辨率，最优帧率）
     * 只使用全局设置的分辨率，不自动选择分辨率
     */
    private fun findBestHighSpeedMode(): Triple<String, Size, Range<Int>> {
        // 固定使用全局设置的分辨率
        val resolution = MainActivity.getCameraResolution()
        val targetSize = Size(resolution.first, resolution.second)
        var bestFpsRange = Range(30, 30) // 默认帧率
        var foundValidConfig = false
        
        try {
            // 直接使用全局设置的摄像头ID
            val cameraId = currentCameraId
            LogManager.d(TAG, "使用指定的摄像头ID: $cameraId")
            LogManager.d(TAG, "目标分辨率（固定）: ${targetSize.width}x${targetSize.height}")
            
            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when(facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    else -> "其他"
                }
                
                LogManager.d(TAG, "检查摄像头: $cameraId ($facingStr)")
                
                // 检查是否支持高速视频录制
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (capabilities == null || !capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                    LogManager.d(TAG, "摄像头 $cameraId 不支持高速视频模式，使用默认配置")
                    return Triple(cameraId, targetSize, bestFpsRange)
                }
                
                // 获取高速视频配置
                val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (configMap == null) {
                    LogManager.d(TAG, "摄像头 $cameraId 没有配置映射，使用默认配置")
                    return Triple(cameraId, targetSize, bestFpsRange)
                }
                
                // 获取支持的高速尺寸
                val highSpeedSizes = configMap.highSpeedVideoSizes
                if (highSpeedSizes.isEmpty()) {
                    LogManager.d(TAG, "摄像头 $cameraId 没有支持的高速尺寸，使用默认配置")
                    return Triple(cameraId, targetSize, bestFpsRange)
                }
                
                LogManager.d(TAG, "=== 摄像头 $cameraId 支持的所有高速配置 ===")
                
                // 输出所有分辨率及其支持的高速帧率
                for (size in highSpeedSizes) {
                    val ranges = configMap.getHighSpeedVideoFpsRangesFor(size)
                    val rangesStr = ranges.joinToString(", ") { "[${it.lower}, ${it.upper}]" }
                    LogManager.d(TAG, "分辨率 ${size.width}x${size.height}: $rangesStr")
                }
                
                LogManager.d(TAG, "===========================================")
                
                // 帧率优先级函数：按照 [60,60] > [60,120] > [120,120] > [240,240] 的优先级
                fun getFpsRangePriority(range: Range<Int>): Int {
                    return when {
                        range.lower >= 30 && range.upper >= 30 -> 1 // 30以上的帧率
                        range.lower == 60 && range.upper == 60 -> 2 // 最高优先级
                        range.lower >= 60 && range.upper >= 60 -> 3 // 其他60以上的帧率
                        range.lower == 60 && range.upper == 120 -> 4
                        range.lower == 120 && range.upper == 120 -> 5
                        range.lower == 240 && range.upper == 240 -> 6
                        else -> 7 // 最低优先级
                    }
                }
                
                // 检查目标分辨率是否被支持
                val targetSizeSupported = highSpeedSizes.any { 
                    it.width == targetSize.width && it.height == targetSize.height 
                }
                
                if (targetSizeSupported) {
                    // 目标分辨率被支持，获取其帧率范围
                    val ranges = configMap.getHighSpeedVideoFpsRangesFor(targetSize)
                    LogManager.d(TAG, "目标分辨率 ${targetSize.width}x${targetSize.height} 支持的帧率范围: ${ranges.joinToString()}")
                    
                    if (ranges.isNotEmpty()) {
                        // 按照帧率优先级排序，选择最佳帧率
                        val sortedRanges = ranges.sortedBy { getFpsRangePriority(it) }
                        bestFpsRange = sortedRanges.first()
                        foundValidConfig = true
                        
                        LogManager.d(TAG, "目标分辨率支持高速模式，选择帧率: ${bestFpsRange.lower}-${bestFpsRange.upper}fps, 优先级=${getFpsRangePriority(bestFpsRange)}")
                    } else {
                        LogManager.w(TAG, "目标分辨率 ${targetSize.width}x${targetSize.height} 不支持任何高速帧率")
                    }
                } else {
                    LogManager.w(TAG, "目标分辨率 ${targetSize.width}x${targetSize.height} 不在高速模式支持列表中")
                    LogManager.w(TAG, "支持的高速分辨率: ${highSpeedSizes.joinToString { "${it.width}x${it.height}" }}")
                    
                    // 目标分辨率不支持，但仍使用目标分辨率，只是不能使用高速模式
                    LogManager.d(TAG, "将使用普通模式，不启用高速捕获")
                }
                
            } catch (e: Exception) {
                LogManager.e(TAG, "检查摄像头 $cameraId 失败: ${e.message}")
                e.printStackTrace()
            }
            
            if (foundValidConfig) {
                LogManager.d(TAG, "最终配置: 摄像头=$currentCameraId, 分辨率=${targetSize.width}x${targetSize.height}, 帧率=${bestFpsRange.lower}-${bestFpsRange.upper}fps")
            } else {
                LogManager.e(TAG, "目标分辨率不支持高速模式，将使用普通模式，分辨率=${targetSize.width}x${targetSize.height}")
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "查找高速模式失败: ${e.message}")
            e.printStackTrace()
        }
        
        return Triple(currentCameraId, targetSize, bestFpsRange)
    }
    
    /**
     * 创建高速模式会话
     */
    private fun createHighSpeedSession(cameraDevice: CameraDevice, surface: Surface, mode: ExposureMode = ExposureMode.AUTO, stateCallback: CameraStateCallback? = null) {
        try {
            LogManager.d(TAG, "开始创建高速捕获会话, 帧率范围: $fpsRange, 曝光模式: $mode")
            if (fpsRange == null) {
                LogManager.e(TAG, "没有有效的帧率范围，回退到普通模式")
                createNormalSession(cameraDevice, surface, mode, stateCallback)
                return
            }
            
            // 确保处理器可用
            val handlerToUse = getValidHandler()
            
            // 如果在检测模式下，使用 surface 的数据
            if (isLaserDetectMode) {
                LogManager.d(TAG, "激光点检测模式已启用，")
                
            }

            /*
            // 声明调试相关变量
            var debugSurfaceTexture: SurfaceTexture? = null
            var debugSurface: Surface? = null
            var debugOpenGLThread: HandlerThread? = null
            var debugOpenGLHandler: Handler? = null
            
            // 使用 CountDownLatch 等待 OpenGL 上下文创建完成
            val openglCreationLatch = java.util.concurrent.CountDownLatch(1)
            
            try {
                // 创建独立的OpenGL线程用于高频率处理SurfaceTexture
                debugOpenGLThread = HandlerThread("DebugSurfaceTextureThread").apply {
                    start()
                }
                debugOpenGLHandler = Handler(debugOpenGLThread.looper)
                
                // 在独立线程中创建OpenGL上下文和SurfaceTexture
                debugOpenGLHandler.post {
                    try {
                        LogManager.d(TAG, "开始在独立线程中创建EGL上下文")
                        
                        // 获取EGL实例
                        val egl = EGLContext.getEGL() as EGL10
                        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
                        
                        if (!egl.eglInitialize(display, null)) {
                            LogManager.e(TAG, "无法初始化EGL显示")
                            openglCreationLatch.countDown()
                            return@post
                        }
                        
                        // 创建EGL配置
                        val configs = arrayOfNulls<EGLConfig>(1)
                        val numConfigs = IntArray(1)
                        val configAttribs = intArrayOf(
                            EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                            EGL10.EGL_RED_SIZE, 8,
                            EGL10.EGL_GREEN_SIZE, 8,
                            EGL10.EGL_BLUE_SIZE, 8,
                            EGL10.EGL_ALPHA_SIZE, 8,
                            EGL10.EGL_DEPTH_SIZE, 0,
                            EGL10.EGL_STENCIL_SIZE, 0,
                            EGL10.EGL_NONE
                        )
                        
                        if (!egl.eglChooseConfig(display, configAttribs, configs, configs.size, numConfigs)) {
                            LogManager.e(TAG, "无法选择EGL配置")
                            openglCreationLatch.countDown()
                            return@post
                        }
                        
                        // 创建EGL上下文
                        val contextAttribs = intArrayOf(
                            0x3098, 2, // EGL_CONTEXT_CLIENT_VERSION = 2
                            EGL10.EGL_NONE
                        )
                        val context = egl.eglCreateContext(
                            display, 
                            configs[0], 
                            EGL10.EGL_NO_CONTEXT, 
                            contextAttribs
                        )
                        
                        if (context === EGL10.EGL_NO_CONTEXT) {
                            LogManager.e(TAG, "无法创建EGL上下文，错误: ${egl.eglGetError()}")
                            openglCreationLatch.countDown()
                            return@post
                        }
                        
                        // 创建PBuffer表面
                        val surfaceAttribs = intArrayOf(
                            EGL10.EGL_WIDTH, 1,
                            EGL10.EGL_HEIGHT, 1,
                            EGL10.EGL_NONE
                        )
                        val surface = egl.eglCreatePbufferSurface(display, configs[0], surfaceAttribs)
                        
                        if (surface === EGL10.EGL_NO_SURFACE) {
                            LogManager.e(TAG, "无法创建EGL表面，错误: ${egl.eglGetError()}")
                            openglCreationLatch.countDown()
                            return@post
                        }
                        
                        // 使当前上下文生效
                        if (!egl.eglMakeCurrent(display, surface, surface, context)) {
                            LogManager.e(TAG, "无法设置当前EGL上下文，错误: ${egl.eglGetError()}")
                            openglCreationLatch.countDown()
                            return@post
                        }
                        
                        LogManager.d(TAG, "独立OpenGL上下文创建成功")
                        
                        // 现在可以安全地创建SurfaceTexture并调用OpenGL方法
                        val debugTextureId = IntArray(1)
                        GLES31.glGenTextures(1, debugTextureId, 0)
                        
                        if (debugTextureId[0] == 0) {
                            LogManager.e(TAG, "无法生成OpenGL纹理")
                            openglCreationLatch.countDown()
                            return@post
                        }
                        
                        debugSurfaceTexture = SurfaceTexture(debugTextureId[0]).apply {
                            // 设置缓冲区大小与预览尺寸一致
                            setDefaultBufferSize(previewSize.width, previewSize.height)
                            
                            // 设置帧可用回调 - 在当前OpenGL线程中处理
                            setOnFrameAvailableListener({ texture ->
                                // 直接在当前OpenGL上下文中更新纹理
                                try {
                                    texture.updateTexImage()
                                    // 只在每100帧输出一次日志，避免日志过多
                                    val frameNumber = System.currentTimeMillis() % 10000
                                    if (frameNumber < 100) { // 大约每10秒输出一次
                                        LogManager.d(TAG, "高频调试纹理更新成功: timestamp=${texture.timestamp}")
                                    }
                                } catch (e: Exception) {
                                    LogManager.e(TAG, "在独立OpenGL上下文中更新纹理失败: ${e.message}")
                                }
                            }, debugOpenGLHandler)
                        }
                        
                        // 创建Surface
                        debugSurface = Surface(debugSurfaceTexture)
                        
                        LogManager.d(TAG, "高频调试SurfaceTexture创建完成，纹理ID: ${debugTextureId[0]}")
                        
                        // 通知主线程OpenGL上下文创建完成
                        openglCreationLatch.countDown()
                        
                    } catch (e: Exception) {
                        LogManager.e(TAG, "创建独立OpenGL上下文失败: ${e.message}")
                        e.printStackTrace()
                        openglCreationLatch.countDown()
                    }
                }
                
            } catch (e: Exception) {
                LogManager.e(TAG, "初始化高频调试系统失败: ${e.message}")
                e.printStackTrace()
                openglCreationLatch.countDown()
            }


            // 等待OpenGL上下文创建完成后再添加Surface
            try {
                if (openglCreationLatch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    debugSurface?.let { surface ->
                        if (surface.isValid) {
                            outputSurfaces.add(surface)
                            LogManager.d(TAG, "已添加高频调试Surface到输出目标")
                        } else {
                            LogManager.w(TAG, "调试Surface无效，跳过添加")
                        }
                    } ?: LogManager.w(TAG, "调试Surface为null，跳过添加")
                } else {
                    LogManager.w(TAG, "等待OpenGL上下文创建超时，跳过调试Surface")
                }
            } catch (e: InterruptedException) {
                LogManager.e(TAG, "等待OpenGL上下文创建被中断: ${e.message}")
            }
             */

            // 创建高速会话配置
            val outputSurfaces = mutableListOf<Surface>(surface)


            // 创建输出配置列表
            val outputConfigurations = outputSurfaces.map { OutputConfiguration(it) }
            
            // 创建高速会话配置
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                outputConfigurations,
                Executors.newSingleThreadExecutor(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        
                        if (session !is CameraConstrainedHighSpeedCaptureSession) {
                            LogManager.e(TAG, "无法创建高速捕获会话，回退到普通模式")
                            createNormalSession(cameraDevice, surface, mode, stateCallback)
                            return
                        }
                        
                        try {
                            // 获取摄像头特性参数
                            val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
                            val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                            val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                            val availableApertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                            val availableFocalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            
                            // 输出调试信息
                            LogManager.d(TAG, "高速模式 SENSOR_INFO_ACTIVE_ARRAY_SIZE: $activeArraySize")
                            LogManager.d(TAG, "高速模式 SENSOR_INFO_PIXEL_ARRAY_SIZE: $pixelArraySize")
                            LogManager.d(TAG, "高速模式 LENS_INFO_AVAILABLE_APERTURES: ${availableApertures?.contentToString() ?: "null"}")
                            LogManager.d(TAG, "高速模式 LENS_INFO_AVAILABLE_FOCAL_LENGTHS: ${availableFocalLengths?.contentToString() ?: "null"}")
                            
                            // 再次确保处理器可用
                            val currentHandler = getValidHandler()
                            
                            // 创建高速预览请求 - 使用TEMPLATE_PREVIEW
                            val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            // 添加目标 surface
                            builder.addTarget(surface)

                            /*
                            // 添加调试Surface（如果可用）
                            debugSurface?.let { debugSrf ->
                                if (debugSrf.isValid) {
                                    builder.addTarget(debugSrf)
                                    LogManager.d(TAG, "已添加调试Surface到捕获请求")
                                }
                            }

                             */

                            // 明确禁用HDR相关功能，避免SMPTE 2094-40错误
                            try {
                                // 禁用HDR模式
                                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                
                                // 如果设备支持，明确禁用HDR
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                    try {
                                        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                                    } catch (e: Exception) {
                                        LogManager.w(TAG, "无法设置CONTROL_EFFECT_MODE: ${e.message}")
                                    }
                                }
                                
                                LogManager.d(TAG, "已禁用HDR和场景模式，避免SMPTE 2094-40错误")
                            } catch (e: Exception) {
                                LogManager.w(TAG, "设置HDR禁用失败（设备可能不支持）: ${e.message}")
                            }

                            // 根据曝光模式设置参数
                            if (mode == ExposureMode.MANUAL) {
                                // 手动曝光模式
                                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                
                                // 设置手动曝光时间 - 使用当前设置的曝光时间
                                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTimeNs)
                                
                                // 设置手动感光度 (ISO) - 从设置中获取
                                val iso = getCurrentIsoValue()
                                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                                
                                // 禁用视频防抖和光学防抖
                                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                
                                LogManager.d(TAG, "高速模式手动曝光设置: 曝光时间=${currentExposureTimeNs}ns (${getExposureTimeMs()}ms), ISO=$iso, 目标帧率: ${fpsRange!!.upper}fps, 已禁用视频和光学防抖")


                            } else {
                                // 自动曝光模式
                                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                                LogManager.d(TAG, "高速模式自动曝光设置已应用")
                            }

                            // 设置帧率范围 (使用最高帧率)
                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fpsRange!!.lower, fpsRange!!.upper))
//                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 120))

                            // 设置数字变焦
                            val cropRegion = calculateCropRegion()
                            if (cropRegion != null) {
                                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                                LogManager.d(TAG, "高速模式设置crop region: $cropRegion, 变焦比例: $currentZoomRatio")
                            }

                            // 创建高速请求列表
                            val requests = session.createHighSpeedRequestList(builder.build())
                            LogManager.d(TAG, "成功创建高速请求列表，请求数量: ${requests.size}")

                            if (requests.isEmpty()) {
                                LogManager.e(TAG, "高速请求列表为空！回退到普通模式")
                                createNormalSession(cameraDevice, surface, mode, stateCallback)
                                return
                            }
                            
                            try {
                                // 设置重复请求，确保使用有效的 Handler
                                val handlerForRequest = getValidHandler()
                                
                                session.setRepeatingBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                                        super.onCaptureStarted(session, request, timestamp, frameNumber)
                                        this.frameNumber = frameNumber
                                        
                                        // 每30帧输出一次日志，避免日志过多
                                        if (frameNumber % 30 == 0L) {
//                                            LogManager.d(TAG, "高速模式捕获帧: $frameNumber, 时间戳: $timestamp")
                                        }
                                        
                                        // 更新帧率统计
//                                        updateFrameProcessingStats()
                                    }
                                    
                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                        super.onCaptureCompleted(session, request, result)
//                                        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
//                                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
//                                        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: System.currentTimeMillis() * 1000000

                                        // 获取 SCALER_CROP_REGION
//                                        val cropRegion = result.get(CaptureResult.SCALER_CROP_REGION)
//                                        if (cropRegion != null) {
//                                            LogManager.d(TAG, "高速模式 SCALER_CROP_REGION: $cropRegion")
//                                        } else {
//                                            LogManager.d(TAG, "高速模式 SCALER_CROP_REGION 未设置")
//                                        }
                                        
                                        // 每帧都更新帧率统计
                                        updateFrameProcessingStats()
                                        
//                                        LogManager.d(TAG, "高速帧完成: 帧号=$frameNumber, 曝光时间=${exposureTime}ns, ISO=$iso")
//                                        if (frameNumber % 30 == 0L) {
//                                        }
                                    }
                                    
                                    private var frameNumber = 0L
                                    
                                    override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                                        super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
                                        LogManager.d(TAG, "高速模式捕获序列完成: 序列ID=$sequenceId, 帧号=$frameNumber")
                                        this.frameNumber = frameNumber
                                    }
                                }, handlerForRequest)
                                
                                // 标记为正在运行
                                isRunning = true
                                
                                // 重置帧率统计
                                lastFpsUpdateTime = 0L
                                frameCount = 0
                                currentFps = 0.0 // 重置当前帧率
                                LogManager.d(TAG, "高速模式帧率统计已重置")
                                
                                // 触发摄像头就绪回调
                                stateCallback?.onCameraReady()
                                
                                LogManager.d(TAG, "高速模式预览已成功启动, 帧率范围: ${fpsRange!!.lower}-${fpsRange!!.upper}fps, 曝光模式: $mode")
                            } catch (e: IllegalArgumentException) {
                                LogManager.e(TAG, "高速模式预览设置失败: ${e.message}")
                                e.printStackTrace()
                                
                                // 尝试使用主线程Handler作为备用方案
                                try {
                                    val mainHandler = Handler(Looper.getMainLooper())
                                    session.setRepeatingBurst(requests, null, mainHandler)
                                    // 标记为正在运行
                                    isRunning = true
                                    // 触发摄像头就绪回调
                                    stateCallback?.onCameraReady()
                                    LogManager.d(TAG, "使用主线程Handler成功设置高速模式")
                                } catch (e2: Exception) {
                                    LogManager.e(TAG, "备用方案也失败: ${e2.message}")
                                    e2.printStackTrace()
                                    createNormalSession(cameraDevice, surface, mode, stateCallback)
                                }
                            } catch (e: Exception) {
                                LogManager.e(TAG, "高速模式预览设置失败: ${e.message}")
                                e.printStackTrace()
                                createNormalSession(cameraDevice, surface, mode, stateCallback)
                            }
                        } catch (e: Exception) {
                            LogManager.e(TAG, "高速模式预览设置失败: ${e.message}")
                            e.printStackTrace()
                            createNormalSession(cameraDevice, surface, mode, stateCallback)
                        }
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        LogManager.e(TAG, "配置高速捕获会话失败，尝试使用普通会话")
                        createNormalSession(cameraDevice, surface, mode, stateCallback)
                    }
                }
            )
            
            try {
                // 尝试创建高速捕获会话
                cameraDevice.createCaptureSession(sessionConfig)
                LogManager.d(TAG, "已提交高速捕获会话创建请求")
            } catch (e: IllegalArgumentException) {
                LogManager.e(TAG, "创建高速会话失败，Surface 尺寸不兼容: ${e.message}")
                e.printStackTrace()
                createNormalSession(cameraDevice, surface, mode, stateCallback)
            } catch (e: Exception) {
                LogManager.e(TAG, "创建高速会话失败: ${e.message}")
                e.printStackTrace()
                createNormalSession(cameraDevice, surface, mode, stateCallback)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "准备高速会话失败: ${e.message}")
            e.printStackTrace()
            createNormalSession(cameraDevice, surface, mode, stateCallback)
        }
    }
    
    /**
     * 创建普通会话
     */
    private fun createNormalSession(cameraDevice: CameraDevice, surface: Surface, mode: ExposureMode = ExposureMode.AUTO, stateCallback: CameraStateCallback? = null) {
        try {
            LogManager.d(TAG, "创建普通预览会话 (高速模式未能启用), 曝光模式: $mode")
            
            // 如果在检测模式下
            if (isLaserDetectMode) {
            }
            
            // 确保处理器可用
            val handlerToUse = getValidHandler()

            // 创建输出Surface列表，同时包含预览Surface和ImageReader的Surface
            val outputSurfaces = mutableListOf<Surface>(surface)
            
            // 使用传统的会话创建方式以提高兼容性
            try {
                LogManager.d(TAG, "使用传统方式创建摄像头会话，Surface数量: ${outputSurfaces.size}")
                
                cameraDevice.createCaptureSession(
                    outputSurfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            
                            try {
                                LogManager.d(TAG, "普通会话配置成功，开始创建预览请求")
                                
                                // 再次确保处理器可用
                                val currentHandler = getValidHandler()
                                
                                // 创建预览请求
                                val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                builder.addTarget(surface)
                                
                                // 明确禁用HDR相关功能，避免SMPTE 2094-40错误
                                try {
                                    // 禁用HDR模式
                                    builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    
                                    // 如果设备支持，明确禁用HDR
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                        try {
                                            builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                                        } catch (e: Exception) {
                                            LogManager.w(TAG, "无法设置CONTROL_EFFECT_MODE: ${e.message}")
                                        }
                                    }
                                    
                                    LogManager.d(TAG, "普通模式已禁用HDR和场景模式，避免SMPTE 2094-40错误")
                                } catch (e: Exception) {
                                    LogManager.w(TAG, "普通模式设置HDR禁用失败（设备可能不支持）: ${e.message}")
                                }
                                
                                // 根据曝光模式设置参数
                                if (mode == ExposureMode.MANUAL) {
                                    // 手动曝光模式
                                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                    
                                    // 设置手动曝光时间 - 使用当前设置的曝光时间
                                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTimeNs)
                                    
                                    // 设置手动感光度 (ISO) - 从设置中获取
                                    val iso = getCurrentIsoValue()
                                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                                    
                                    LogManager.d(TAG, "普通模式手动曝光设置: 曝光时间=${currentExposureTimeNs}ns (${getExposureTimeMs()}ms), ISO=$iso")
                                } else {
                                    // 自动曝光模式
                                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                                    LogManager.d(TAG, "普通模式自动曝光设置已应用")
                                }
                                
                                // 获取设备支持的帧率范围
                                val fpsRanges = cameraManager.getCameraCharacteristics(currentCameraId)
                                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                
                                // 选择帧率范围 - 使用稳定的30fps
                                val selectedRange = fpsRanges?.find { it.lower == 30 && it.upper == 30 }
                                    ?: fpsRanges?.minByOrNull { Math.abs(30 - it.upper) }
                                    ?: Range(30, 30)
                                
                                LogManager.d(TAG, "普通模式使用帧率范围: ${selectedRange.lower}-${selectedRange.upper}fps")
                                
                                // 设置帧率范围
                                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedRange)
                                
                                // 应用数字变焦设置
                                val cropRegion = calculateCropRegion()
                                if (cropRegion != null) {
                                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                                    LogManager.d(TAG, "普通模式应用数字变焦: $cropRegion, 变焦比例: $currentZoomRatio")
                                }
                                
                                try {
                                    // 使用有效的Handler
                                    val handlerForRequest = getValidHandler()
                                    
                                    // 开始预览
                                    session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                                            super.onCaptureStarted(session, request, timestamp, frameNumber)
                                            // 每30帧输出一次日志
                                            if (frameNumber % 30 == 0L) {
                                                LogManager.d(TAG, "普通模式捕获帧: $frameNumber")
                                            }
                                        }
                                        
                                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                            super.onCaptureCompleted(session, request, result)
                                            // 每帧都更新帧率统计
                                            updateFrameProcessingStats()
                                        }
                                    }, handlerForRequest)
                                    
                                    // 标记为正在运行
                                    isRunning = true
                                    
                                    // 重置帧率统计
                                    lastFpsUpdateTime = 0L
                                    frameCount = 0
                                    currentFps = 0.0 // 重置当前帧率
                                    LogManager.d(TAG, "普通模式帧率统计已重置")
                                    
                                    // 触发摄像头就绪回调
                                    stateCallback?.onCameraReady()
                                    
                                    LogManager.d(TAG, "普通模式预览已成功启动, 曝光模式: $mode")
                                } catch (e: Exception) {
                                    LogManager.e(TAG, "设置普通预览请求失败: ${e.message}")
                                    e.printStackTrace()
                                }
                            } catch (e: Exception) {
                                LogManager.e(TAG, "普通会话配置失败: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                        
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            LogManager.e(TAG, "配置普通捕获会话失败")
                        }
                    },
                    backgroundHandler ?: Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                LogManager.e(TAG, "创建普通会话失败: ${e.message}")
                e.printStackTrace()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "创建普通会话失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 使用 Surface 打开摄像头
     */
    fun openCameraWithSurface(
        surface: Surface,
        width: Int,
        height: Int,
        useHighSpeed: Boolean = true,
        frameRateListener: ((Double) -> Unit)? = null,
        exposureMode: ExposureMode = ExposureMode.AUTO,
        stateCallback: CameraStateCallback? = null
    ) {
        // 只有当传入的frameRateListener不为null时才设置，避免覆盖现有监听器
        if (frameRateListener != null) {
            this.frameRateListener = frameRateListener
        }
        
        // 确保启动后台线程
        if (backgroundHandler == null) {
            startBackgroundThread()
        }
        
        // 在主线程上执行，避免线程问题
        Handler(Looper.getMainLooper()).post {
            openCameraWithSurfaceInternal(surface, width, height, useHighSpeed, exposureMode, stateCallback)
        }
    }
    
    /**
     * 使用 Surface 打开摄像头的内部实现
     */
    private fun openCameraWithSurfaceInternal(
        surface: Surface, 
        width: Int, 
        height: Int, 
        useHighSpeed: Boolean,
        exposureMode: ExposureMode = ExposureMode.AUTO,
        stateCallback: CameraStateCallback? = null
    ) {
        try {
            // 检查权限
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                LogManager.e(TAG, "没有摄像头权限")
                return
            }
            
            // 每次打开摄像头前确保后台线程是活跃的
            stopBackgroundThread() // 先清理可能有问题的线程
            startBackgroundThread() // 创建新的线程
            
            // 是否应该使用高速模式（可以在函数中修改此变量）
            var shouldUseHighSpeed = useHighSpeed // 恢复高速模式
            
            // 找到最佳高速模式
            val bestMode = if (shouldUseHighSpeed) findBestHighSpeedMode() else Triple("0", Size(width, height), Range(30, 30))
            
            previewSize = bestMode.second
            fpsRange = bestMode.third

            // 验证Surface尺寸与高速模式的兼容性
            if (shouldUseHighSpeed) {
                val surfaceSize = Size(width, height)
                
                val isCompatibleSize = previewSize.width == surfaceSize.width && previewSize.height == surfaceSize.height
                
                if (!isCompatibleSize) {
                    LogManager.w(TAG, "Surface尺寸 ${width}x${height} 与选定的预览尺寸 ${previewSize.width}x${previewSize.height} 不匹配")
                    LogManager.w(TAG, "将尝试使用高速模式，如果失败会自动降级到普通模式")
                    // 不立即禁用高速模式，让Camera2 API自行处理兼容性
                }
            }

            LogManager.d(TAG, "openCameraWithSurfaceInternal, highSpeed: ${shouldUseHighSpeed}, fpsRange: $fpsRange")
            
            // 保存surface引用用于变焦功能
            currentSurface = surface
            
            // 获取摄像头特性
            val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
            
            // 初始化数字变焦相关信息
            maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            // 限制最大变焦倍数为4倍，即使设备支持更高倍数
            maxDigitalZoom = minOf(maxDigitalZoom, 4.0f)
            activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            
            LogManager.d(TAG, "数字变焦初始化 - 设备最大变焦倍数: ${characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)}, 限制后最大变焦倍数: $maxDigitalZoom, 活动数组尺寸: $activeArraySize")
            
            // 获取支持的最大ISO值
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            maxIso = isoRange?.upper ?: 1600
            
            // 获取支持的曝光时间范围
            val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            minExposureTime = exposureTimeRange?.lower ?: 1000000 // 默认1ms
            maxExposureTime = exposureTimeRange?.upper ?: 1000000000 // 默认1秒
            
            LogManager.d(TAG, "打开摄像头: $currentCameraId, 预览尺寸: ${previewSize.width}x${previewSize.height}")
            LogManager.d(TAG, "ISO范围: ${isoRange?.lower}-${isoRange?.upper}, 曝光时间范围: ${minExposureTime}ns-${maxExposureTime}ns")
            LogManager.d(TAG, "帧率范围: ${fpsRange?.lower}-${fpsRange?.upper}fps, 高速模式: $shouldUseHighSpeed, 曝光模式: $exposureMode")
            
            // 检查后台线程是否仍然活跃
            if (backgroundThread?.isAlive != true || backgroundHandler == null) {
                LogManager.w(TAG, "后台线程不可用，重新启动线程")
                startBackgroundThread()
            }
            
            // 获取有效的 Handler
            val effectiveHandler = backgroundHandler ?: Handler(Looper.getMainLooper()).also {
                LogManager.w(TAG, "打开摄像头时使用主线程Handler作为备用")
            }
            
            // 打开摄像头
            cameraManager.openCamera(currentCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    
                    // 验证 Surface 是否有效
                    if (!surface.isValid) {
                        LogManager.e(TAG, "提供的 Surface 无效")
                        closeCamera()
                        return
                    }
                    
                    LogManager.d(TAG, "使用 Surface 打开摄像头: ${surface.isValid}")
                    
                    // 根据是否使用高速模式创建不同的会话
                    if (shouldUseHighSpeed && fpsRange != null) {
                        LogManager.d(TAG, "使用高速模式：camera id ${currentCameraId} 尺寸=${previewSize.width}x${previewSize.height}, 帧率=${fpsRange!!.lower}-${fpsRange!!.upper}fps")
                        createHighSpeedSession(camera, surface, exposureMode, stateCallback)
                    } else {
                        LogManager.d(TAG, "使用普通模式")
                        createNormalSession(camera, surface, exposureMode, stateCallback)
                    }
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    LogManager.d(TAG, "摄像头已断开连接")
                    closeCamera()
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    LogManager.e(TAG, "摄像头错误: $error")
                    // 触发摄像头错误回调
                    stateCallback?.onCameraError(error)
                    closeCamera()
                }
            }, effectiveHandler)
            
        } catch (e: Exception) {
            LogManager.e(TAG, "打开摄像头失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 是否正在运行
     */
    fun isRunning(): Boolean {
        return isRunning
    }
    
    /**
     * 关闭摄像头
     */
    fun closeCamera() {
        try {
            // 防止多线程同时关闭导致问题
            synchronized(this) {
                // 标记为不在运行
                isRunning = false
                
                captureSession?.close()
                captureSession = null
                
                cameraDevice?.close()
                cameraDevice = null
                
                // 关闭ImageReader
//                imageReader?.close()
//                imageReader = null
//                LogManager.d(TAG, "ImageReader已关闭")
            }
            
            // 触发摄像头关闭回调
//            currentStateCallback?.onCameraClosed()
            
            // 停止后台线程
            stopBackgroundThread()
            
            LogManager.d(TAG, "摄像头已关闭")
        } catch (e: Exception) {
            LogManager.e(TAG, "关闭摄像头失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 确保获取有效的 Handler
     * 
     * 如果后台线程 Handler 可用则使用它，否则使用主线程 Handler
     */
    private fun getValidHandler(): Handler {
        // 先检查后台 Handler 是否可用
        if (backgroundThread?.isAlive == true && backgroundHandler != null) {
            return backgroundHandler!!
        }
        
        // 如果不可用，尝试重新创建
        if (backgroundHandler == null || backgroundThread?.isAlive != true) {
            try {
                startBackgroundThread()
                if (backgroundHandler != null) {
                    return backgroundHandler!!
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "无法创建后台处理器: ${e.message}")
            }
        }
        
        // 返回主线程 Handler 作为备用
        return Handler(Looper.getMainLooper()).also {
            LogManager.w(TAG, "使用主线程Handler作为备用")
        }
    }

    /**
     * 获取并打印所有摄像头的详细信息
     */
    fun getAllCameraInfo() {
        LogManager.i(TAG, "=================== 开始获取所有摄像头信息 ===================")
        
        try {
            val cameraIdList = cameraManager.cameraIdList
            LogManager.i(TAG, "检测到摄像头数量: ${cameraIdList.size}")
            
            for ((index, cameraId) in cameraIdList.withIndex()) {
                LogManager.i(TAG, "")
                LogManager.i(TAG, "========== 摄像头 #${index + 1} (ID: $cameraId) ==========")
                
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    
                    // 1. 摄像头基本信息
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    val facingStr = when(facing) {
                        CameraCharacteristics.LENS_FACING_BACK -> "后置摄像头"
                        CameraCharacteristics.LENS_FACING_FRONT -> "前置摄像头"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "外置摄像头"
                        else -> "未知方向($facing)"
                    }
                    LogManager.i(TAG, "摄像头类型: $facingStr")
                    
                    // 2. 传感器信息
                    val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    
                    LogManager.i(TAG, "传感器物理尺寸: ${sensorSize?.width}mm x ${sensorSize?.height}mm")
                    LogManager.i(TAG, "像素阵列尺寸: ${pixelArraySize?.width} x ${pixelArraySize?.height}")
                    LogManager.i(TAG, "有效区域尺寸: ${activeArraySize?.width()} x ${activeArraySize?.height()}")
                    
                    // 3. 焦距信息
                    val availableFocalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    val hyperFocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
                    
                    LogManager.i(TAG, "可用焦距: ${availableFocalLengths?.contentToString() ?: "未知"}mm")
                    LogManager.i(TAG, "最小对焦距离: ${minFocusDistance ?: "未知"}屈光度")
                    LogManager.i(TAG, "超焦距: ${hyperFocalDistance ?: "未知"}屈光度")
                    
                    // 4. 光圈信息
                    val availableApertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    LogManager.i(TAG, "可用光圈值: ${availableApertures?.contentToString() ?: "未知"}")
                    
                    // 5. ISO和曝光范围
                    val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val exposureCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    val exposureCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    
                    LogManager.i(TAG, "ISO感光度范围: ${isoRange?.lower} - ${isoRange?.upper}")
                    LogManager.i(TAG, "曝光时间范围: ${exposureTimeRange?.lower}ns - ${exposureTimeRange?.upper}ns")
                    if (exposureTimeRange != null) {
                        val minMs = exposureTimeRange.lower / 1_000_000.0
                        val maxS = exposureTimeRange.upper / 1_000_000_000.0
                        LogManager.i(TAG, "曝光时间范围(可读): ${String.format("%.3f", minMs)}ms - ${String.format("%.1f", maxS)}s")
                    }
                    LogManager.i(TAG, "曝光补偿范围: ${exposureCompensationRange?.lower} - ${exposureCompensationRange?.upper}")
                    LogManager.i(TAG, "曝光补偿步长: $exposureCompensationStep")
                    
                    // 6. 支持的分辨率
                    val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    if (configMap != null) {
                        LogManager.i(TAG, "--- 支持的输出格式和尺寸 ---")
                        
                        // JPEG格式支持的尺寸
                        val jpegSizes = configMap.getOutputSizes(ImageFormat.JPEG)
                        LogManager.i(TAG, "JPEG格式支持的尺寸数量: ${jpegSizes?.size ?: 0}")
                        jpegSizes?.take(5)?.forEach { size ->
                            LogManager.i(TAG, "  JPEG: ${size.width}x${size.height}")
                        }
                        if (jpegSizes != null && jpegSizes.size > 5) {
                            LogManager.i(TAG, "  ... 还有${jpegSizes.size - 5}个JPEG尺寸")
                        }
                        
                        // YUV_420_888格式支持的尺寸
                        val yuvSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888)
                        LogManager.i(TAG, "YUV_420_888格式支持的尺寸数量: ${yuvSizes?.size ?: 0}")
                        yuvSizes?.take(5)?.forEach { size ->
                            LogManager.i(TAG, "  YUV: ${size.width}x${size.height}")
                        }
                        if (yuvSizes != null && yuvSizes.size > 5) {
                            LogManager.i(TAG, "  ... 还有${yuvSizes.size - 5}个YUV尺寸")
                        }
                        
                        // SurfaceTexture支持的尺寸
                        val surfaceTextureSizes = configMap.getOutputSizes(SurfaceTexture::class.java)
                        LogManager.i(TAG, "SurfaceTexture支持的尺寸数量: ${surfaceTextureSizes?.size ?: 0}")
                        surfaceTextureSizes?.take(5)?.forEach { size ->
                            LogManager.i(TAG, "  SurfaceTexture: ${size.width}x${size.height}")
                        }
                        if (surfaceTextureSizes != null && surfaceTextureSizes.size > 5) {
                            LogManager.i(TAG, "  ... 还有${surfaceTextureSizes.size - 5}个SurfaceTexture尺寸")
                        }
                    }
                    
                    // 7. 支持的帧率范围
                    val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    LogManager.i(TAG, "支持的帧率范围:")
                    fpsRanges?.forEach { range ->
                        LogManager.i(TAG, "  ${range.lower}fps - ${range.upper}fps")
                    }
                    
                    // 8. 高速视频支持
                    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val supportsHighSpeed = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true
                    LogManager.i(TAG, "支持高速视频: $supportsHighSpeed")
                    
                    if (supportsHighSpeed && configMap != null) {
                        LogManager.i(TAG, "--- 高速视频配置 ---")
                        val highSpeedSizes = configMap.highSpeedVideoSizes
                        LogManager.i(TAG, "高速视频支持的尺寸数量: ${highSpeedSizes?.size ?: 0}")
                        
                        highSpeedSizes?.forEach { size ->
                            val ranges = configMap.getHighSpeedVideoFpsRangesFor(size)
                            val rangesStr = ranges.joinToString(", ") { "[${it.lower}, ${it.upper}]" }
                            LogManager.i(TAG, "  ${size.width}x${size.height}: $rangesStr fps")
                        }
                    }
                    
                    // 9. 自动对焦支持
                    val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    val afModesStr = afModes?.joinToString(", ") { mode ->
                        when (mode) {
                            CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
                            CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
                            CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
                            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
                            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
                            CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
                            else -> "UNKNOWN($mode)"
                        }
                    }
                    LogManager.i(TAG, "支持的自动对焦模式: $afModesStr")
                    
                    // 10. 自动曝光支持
                    val aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                    val aeModesStr = aeModes?.joinToString(", ") { mode ->
                        when (mode) {
                            CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF"
                            CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
                            CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
                            CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
                            CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "ON_AUTO_FLASH_REDEYE"
                            else -> "UNKNOWN($mode)"
                        }
                    }
                    LogManager.i(TAG, "支持的自动曝光模式: $aeModesStr")
                    
                    // 11. 白平衡支持
                    val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                    val awbModesStr = awbModes?.joinToString(", ") { mode ->
                        when (mode) {
                            CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
                            CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
                            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
                            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
                            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
                            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
                            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
                            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
                            CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
                            else -> "UNKNOWN($mode)"
                        }
                    }
                    LogManager.i(TAG, "支持的白平衡模式: $awbModesStr")
                    
                    // 12. 防抖支持
                    val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    val oisModesStr = oisModes?.joinToString(", ") { mode ->
                        when (mode) {
                            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
                            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
                            else -> "UNKNOWN($mode)"
                        }
                    }
                    LogManager.i(TAG, "光学防抖支持: $oisModesStr")
                    
                    val videoStabilization = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    val videoStabilizationStr = videoStabilization?.joinToString(", ") { mode ->
                        when (mode) {
                            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
                            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON"
                            else -> "UNKNOWN($mode)"
                        }
                    }
                    LogManager.i(TAG, "视频防抖支持: $videoStabilizationStr")
                    
                    // 13. 其他能力
                    val allCapabilities = capabilities?.joinToString(", ") { capability ->
                        when (capability) {
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
                            else -> "UNKNOWN($capability)"
                        }
                    }
                    LogManager.i(TAG, "摄像头能力: $allCapabilities")
                    
                    // 14. 硬件等级
                    val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    val hardwareLevelStr = when (hardwareLevel) {
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                        else -> "UNKNOWN($hardwareLevel)"
                    }
                    LogManager.i(TAG, "硬件支持等级: $hardwareLevelStr")

                    // 15. 是否是逻辑摄像头
                    val physicalCameraIds = characteristics.physicalCameraIds
                    LogManager.i(TAG, "物理摄像头: $physicalCameraIds")

                } catch (e: Exception) {
                    LogManager.e(TAG, "获取摄像头 $cameraId 信息失败: ${e.message}")
                    e.printStackTrace()
                }
                
                LogManager.i(TAG, "================================================")
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "获取摄像头列表失败: ${e.message}")
            e.printStackTrace()
        }
        
        LogManager.i(TAG, "=================== 摄像头信息获取完成 ===================")
    }

    /**
     * 设置当前摄像头ID
     */
    fun setCurrentCameraId(cameraId: String) {
        currentCameraId = cameraId
        LogManager.d(TAG, "设置当前摄像头ID: $cameraId")
    }
    
    /**
     * 获取当前摄像头ID
     */
    fun getCurrentCameraId(): String {
        return currentCameraId
    }
    
    /**
     * 设置曝光时间（毫秒）
     */
    fun setExposureTimeMs(exposureTimeMs: Float) {
        currentExposureTimeNs = (exposureTimeMs * 1_000_000).toLong()
        LogManager.d(TAG, "设置曝光时间: ${exposureTimeMs}ms (${currentExposureTimeNs}ns)")
    }
    
    /**
     * 获取当前曝光时间（毫秒）
     */
    fun getExposureTimeMs(): Float {
        return currentExposureTimeNs / 1_000_000f
    }
    
    /**
     * 获取当前曝光时间（纳秒）
     */
    fun getExposureTimeNs(): Long {
        return currentExposureTimeNs
    }
    
    /**
     * 获取当前ISO值
     */
    fun getCurrentIsoValue(): Int {
        return try {
            val settings = settingsManager.getSettings()
            settings.isoValue
        } catch (e: Exception) {
            LogManager.w(TAG, "获取ISO设置失败，使用默认值: ${e.message}")
            400 // 默认ISO值
        }
    }

    /**
     * 获取摄像头详细信息列表，用于设置界面
     */
    fun getCameraInfoList(): List<CameraInfo> {
        val cameraInfoList = mutableListOf<CameraInfo>()
        
        try {
            val cameraIdList = cameraManager.cameraIdList
            LogManager.d(TAG, "开始获取摄像头详细信息列表，检测到摄像头数量: ${cameraIdList.size}")
            
            for (cameraId in cameraIdList) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    
                    // 获取摄像头基本信息
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    val facingStr = when(facing) {
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT" 
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN"
                    }
                    
                    val facingName = when(facing) {
                        CameraCharacteristics.LENS_FACING_BACK -> "后置摄像头"
                        CameraCharacteristics.LENS_FACING_FRONT -> "前置摄像头"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "外置摄像头"
                        else -> "未知摄像头"
                    }
                    
                    // 获取焦距信息
                    val availableFocalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val focalLengths = availableFocalLengths?.toList() ?: emptyList()
                    
                    // 检查支持的能力
                    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val supportsHighSpeed = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true
                    val supportsNormal = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
                    
                    // 获取支持的分辨率
                    val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val supportedResolutions = mutableListOf<Resolution>()
                    
                    if (configMap != null) {
                        val surfaceTextureSizes = configMap.getOutputSizes(SurfaceTexture::class.java)
                        
                        // 检查每个预定义分辨率是否被支持
                        Resolution.values().forEach { resolution ->
                            val isSupported = surfaceTextureSizes?.any { size ->
                                size.width == resolution.width && size.height == resolution.height
                            } == true
                            
                            if (isSupported) {
                                supportedResolutions.add(resolution)
                            }
                        }
                    }
                    
                    // 获取曝光时间范围
                    val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val minExposureTimeMs = (exposureTimeRange?.lower ?: 1000000L) / 1_000_000f // 转换为毫秒
                    val maxExposureTimeMs = (exposureTimeRange?.upper ?: 1000000000L) / 1_000_000f // 转换为毫秒
                    
                    // 获取ISO范围
                    val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val maxIso = isoRange?.upper ?: 1600
                    
                    // 获取硬件等级
                    val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    val hardwareLevelStr = when (hardwareLevel) {
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                        else -> "UNKNOWN"
                    }
                    
                    // 获取传感器物理尺寸
                    val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val physicalSize = if (sensorSize != null) {
                        Pair(sensorSize.width, sensorSize.height)
                    } else {
                        null
                    }
                    
                    // 创建CameraInfo对象
                    val cameraInfo = CameraInfo(
                        id = cameraId,
                        name = facingName,
                        facing = facingStr,
                        focalLengths = focalLengths,
                        supportsNormalFrameRate = supportsNormal,
                        supportsHighFrameRate = supportsHighSpeed,
                        supportedResolutions = supportedResolutions,
                        minExposureTimeMs = minExposureTimeMs,
                        maxExposureTimeMs = maxExposureTimeMs,
                        maxIso = maxIso,
                        hardwareLevel = hardwareLevelStr,
                        physicalSize = physicalSize
                    )
                    
                    cameraInfoList.add(cameraInfo)
                    LogManager.d(TAG, "摄像头 $cameraId 信息: ${cameraInfo.getDisplayName(context)}, ${cameraInfo.getDetailDescription(context)}")
                    
                } catch (e: Exception) {
                    LogManager.e(TAG, "获取摄像头 $cameraId 详细信息失败: ${e.message}")
                    e.printStackTrace()
                }
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "获取摄像头信息列表失败: ${e.message}")
            e.printStackTrace()
        }
        
        LogManager.d(TAG, "摄像头详细信息列表获取完成，总数: ${cameraInfoList.size}")
        return cameraInfoList
    }

    /**
     * 获取最大数字变焦倍数
     */
    fun getMaxDigitalZoom(): Float {
        return maxDigitalZoom
    }
    
    /**
     * 获取当前变焦比例
     */
    fun getCurrentZoomRatio(): Float {
        return currentZoomRatio
    }
    
    /**
     * 设置变焦比例
     * @param zoomRatio 变焦比例，1.0为正常，大于1.0为放大
     */
    fun setZoomRatio(zoomRatio: Float) {
        val clampedZoom = zoomRatio.coerceIn(1.0f, maxDigitalZoom)
        if (clampedZoom != currentZoomRatio) {
            currentZoomRatio = clampedZoom
            LogManager.d(TAG, "设置变焦比例: $currentZoomRatio")
            
            // 保存到设置中
            try {
                val currentSettings = settingsManager.getSettings()
                val updatedSettings = currentSettings.copy(zoomRatio = currentZoomRatio)
                settingsManager.saveSettings(updatedSettings)
                LogManager.d(TAG, "变焦比例已保存到设置: $currentZoomRatio")
            } catch (e: Exception) {
                LogManager.w(TAG, "保存变焦设置失败: ${e.message}")
            }
            
            // 通知监听器
            zoomRatioListener?.invoke(currentZoomRatio)
            
            // 如果摄像头正在运行，更新capture request
            updateZoomInCaptureRequest()
        }
    }
    
    /**
     * 根据当前变焦比例计算crop region
     */
    private fun calculateCropRegion(): Rect? {
        val activeArray = activeArraySize ?: return null
        
        if (currentZoomRatio <= 1.0f) {
            // 无变焦，返回完整的active array
            return activeArray
        }
        
        // 计算crop region的尺寸
        val cropWidth = (activeArray.width() / currentZoomRatio).toInt()
        val cropHeight = (activeArray.height() / currentZoomRatio).toInt()
        
        // 计算crop region的中心位置
        val centerX = activeArray.centerX()
        val centerY = activeArray.centerY()
        
        // 计算crop region的边界
        val left = centerX - cropWidth / 2
        val top = centerY - cropHeight / 2
        val right = left + cropWidth
        val bottom = top + cropHeight
        
        return Rect(left, top, right, bottom)
    }
    
    /**
     * 更新capture request中的变焦设置
     */
    private fun updateZoomInCaptureRequest() {
        try {
            val session = captureSession ?: return
            val device = cameraDevice ?: return
            
            LogManager.d(TAG, "开始更新变焦设置，当前变焦比例: $currentZoomRatio")
            
            // 获取当前使用的模板
            val template = if (session is CameraConstrainedHighSpeedCaptureSession) {
                CameraDevice.TEMPLATE_PREVIEW // 高速模式使用PREVIEW模板
            } else {
                CameraDevice.TEMPLATE_PREVIEW
            }
            
            val builder = device.createCaptureRequest(template)
            
            // 添加surface目标
            val surface = getCurrentSurface()
            if (surface != null && surface.isValid) {
                builder.addTarget(surface)
            } else {
                LogManager.w(TAG, "无效的surface，跳过变焦更新")
                return
            }
            
            // 设置crop region
            val cropRegion = calculateCropRegion()
            if (cropRegion != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                LogManager.d(TAG, "设置crop region: $cropRegion, 变焦比例: $currentZoomRatio")
            }
            
            // 应用基本的capture request设置（曝光、ISO等）
            applyCaptureRequestSettings(builder)
            
            // 获取有效的Handler
            val handler = getValidHandler()
            
            // 根据会话类型设置重复请求
            if (session is CameraConstrainedHighSpeedCaptureSession) {
                // 高速模式 - 只设置高速模式特有的参数
                LogManager.d(TAG, "高速模式变焦更新，设置帧率范围: $fpsRange")
                
                // 在高速模式下，必须设置帧率范围
                if (fpsRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    LogManager.d(TAG, "高速模式设置帧率范围: ${fpsRange!!.lower}-${fpsRange!!.upper}fps")
                }
                
                val builtRequest = builder.build()
                LogManager.d(TAG, "高速模式构建请求完成，开始创建高速请求列表")
                
                // 创建高速请求列表
                val requests = session.createHighSpeedRequestList(builtRequest)
                LogManager.d(TAG, "高速模式创建请求列表完成，请求数量: ${requests.size}")
                
                if (requests.isNotEmpty()) {
                    // 验证第一个请求中的crop region是否正确设置
                    val firstRequest = requests[0]
                    val requestCropRegion = firstRequest.get(CaptureRequest.SCALER_CROP_REGION)
                    LogManager.d(TAG, "高速模式请求列表中的crop region: $requestCropRegion")
                    
                    session.setRepeatingBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber)
                            // 仅在变焦更新后的前几帧输出日志
                            if (frameNumber % 60 == 0L) {
                                val currentCropRegion = request.get(CaptureRequest.SCALER_CROP_REGION)
                                LogManager.d(TAG, "高速模式变焦帧 $frameNumber，crop region: $currentCropRegion")
                            }
                        }
                        
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            super.onCaptureCompleted(session, request, result)
                            // 更新帧率统计
                            updateFrameProcessingStats()
                        }
                    }, handler)
                    
                    LogManager.d(TAG, "高速模式变焦更新完成，crop region: $cropRegion")
                } else {
                    LogManager.e(TAG, "高速模式创建请求列表为空，变焦更新失败")
                }
            } else {
                // 普通模式
                session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                        super.onCaptureStarted(session, request, timestamp, frameNumber)
                        // 仅在变焦更新后的前几帧输出日志
                        if (frameNumber % 30 == 0L) {
                            val currentCropRegion = request.get(CaptureRequest.SCALER_CROP_REGION)
                            LogManager.d(TAG, "普通模式变焦帧 $frameNumber，crop region: $currentCropRegion")
                        }
                    }
                    
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        super.onCaptureCompleted(session, request, result)
                        // 更新帧率统计
                        updateFrameProcessingStats()
                    }
                }, handler)
                
                LogManager.d(TAG, "普通模式变焦更新完成，crop region: $cropRegion")
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "更新变焦设置失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 获取当前使用的Surface
     */
    private fun getCurrentSurface(): android.view.Surface? {
        // 这里需要保存当前使用的surface引用
        // 在openCameraWithSurfaceInternal方法中设置
        return currentSurface
    }
    
    // 添加成员变量来保存当前surface
    private var currentSurface: android.view.Surface? = null
    
    /**
     * 应用capture request的基本设置（曝光、ISO等）
     */
    private fun applyCaptureRequestSettings(builder: CaptureRequest.Builder) {
        try {
            // 根据当前模式设置参数
            if (isLaserDetectMode) {
                // 激光检测模式 - 手动曝光
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTimeNs)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, getCurrentIsoValue())
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                // 在激光检测模式下禁用场景模式
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
            } else {
                // 普通模式 - 自动曝光
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                // 在普通模式下禁用场景模式
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
            }
            
        } catch (e: Exception) {
            LogManager.w(TAG, "应用capture request设置时出错: ${e.message}")
        }
    }
}