package com.scieford.laserctrlmouse

/*
 * MainActivity - 激光控制鼠标应用的主界面
 * 
 * 资源管理改进 (针对OpenGL错误1285):
 * 1. restartCamera() - 在OpenGL线程中安全释放GPU资源，避免上下文不一致
 * 2. CameraImageProcessor.release() - 添加OpenGL上下文检查，防止在错误上下文中释放资源
 * 3. onDestroy() - 确保所有GPU资源在正确的OpenGL上下文中释放
 * 4. 增加资源释放的延迟时间，确保前一个实例的资源完全释放
 * 
 * OpenGL错误1285（GL_OUT_OF_MEMORY）通常由以下原因引起：
 * - 在错误的OpenGL上下文中释放资源
 * - 资源没有完全释放就创建新实例
 * - GlobalMaxBuffer等新增缓冲区的内存泄漏
 */

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
// Lucide Icons
import com.composables.icons.lucide.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.key
import com.scieford.laserctrlmouse.camera.Camera2Helper
import com.scieford.laserctrlmouse.camera.LaserPointDetector
import com.scieford.laserctrlmouse.settings.SettingsManager
import com.scieford.laserctrlmouse.ui.theme.LaserCtrlMouseTheme
import com.scieford.laserctrlmouse.utils.EventBus
import com.scieford.laserctrlmouse.utils.LogManager
import com.scieford.laserctrlmouse.utils.VersionCheckService
import com.scieford.laserctrlmouse.utils.UpdateDialogHelper
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.os.Environment
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import androidx.core.app.ActivityCompat
import org.opencv.core.Core
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.Gravity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.Button
import com.scieford.laserctrlmouse.camera.ExposureMode
import com.scieford.laserctrlmouse.camera.GLCameraSurfaceView
import java.util.concurrent.atomic.AtomicBoolean
import com.scieford.laserctrlmouse.camera.CameraImageProcessor
import android.view.WindowManager
import android.content.res.Configuration
import android.os.Handler
import android.os.PowerManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.annotation.RequiresApi
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import com.scieford.laserctrlmouse.settings.SettingsActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.content.ContextWrapper
import android.os.LocaleList
import com.scieford.laserctrlmouse.network.NetworkClient
import java.lang.ref.WeakReference
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity(), Camera2Helper.FrameCallback, Camera2Helper.ImageCallback, LaserPointDetector.LaserPointListener {
    companion object {
        const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
        
        // 事件名称常量
        const val EVENT_LASER_POINT_DETECTED = "laser_point_detected"
        
        // 屏幕检测相关常量
        private const val SCREEN_DETECTION_THRESHOLD = 5 // 连续5次检测成功才进入激光点检测模式
        private const val TEMPLATE_IMAGE_URL = "https://laser.scieford.com/template.jpeg"
        
        // 扩展功能控制开关
        // 当设置为false时，帧率模式和用户级别设置将被隐藏
        @JvmStatic
        var extFeatureEnabled = false
        
        // 基础分辨率 用于预览和检测 - 会根据设备方向自动调整
        var BASE_WIDTH = 640
        var BASE_HEIGHT = 480
        
        // 帧处理线程相关常量
        private const val MAX_FPS = 60 // 最大帧率
        private const val MIN_FRAME_TIME = 1000 / MAX_FPS // 最小帧间隔（毫秒）
        
        // 性能统计
        private const val PERFORMANCE_LOG_INTERVAL = 5000 // 每5秒输出一次性能统计
        
        // 全局context引用，用于获取设备方向
        private var globalContext: android.content.Context? = null
        
        /**
         * 设置全局context
         */
        @JvmStatic
        fun setGlobalContext(context: android.content.Context) {
            globalContext = context
        }
        
        /**
         * 设置摄像头分辨率
         */
        @JvmStatic
        fun setCameraResolution(width: Int, height: Int) {
            BASE_WIDTH = width
            BASE_HEIGHT = height
            LogManager.d(TAG, "设置摄像头分辨率: ${width}x${height}")
        }
        
        /**
         * 获取统一的摄像头分辨率配置
         * 始终使用横屏分辨率，不再依赖设备方向
         */
        @JvmStatic
        fun getCameraResolution(): Pair<Int, Int> {
            // 始终使用横屏分辨率 640x480
            return Pair(BASE_WIDTH, BASE_HEIGHT) // 640x480
        }
        
        /**
         * 获取摄像头宽度
         */
        @JvmStatic
        fun getCameraWidth(): Int = BASE_WIDTH // 始终640
        
        /**
         * 获取摄像头高度  
         */
        @JvmStatic
        fun getCameraHeight(): Int = BASE_HEIGHT // 始终480
        
        /**
         * 获取屏幕宽度（用于激光点坐标转换）
         * 始终使用横屏模式的宽度
         */
        @JvmStatic
        fun getScreenWidth(): Int = BASE_WIDTH // 始终640
        
        /**
         * 获取屏幕高度（用于激光点坐标转换）
         * 始终使用横屏模式的高度
         */
        @JvmStatic
        fun getScreenHeight(): Int = BASE_HEIGHT // 始终480
        
        /**
         * 判断设备是否处于竖屏状态
         */
        @JvmStatic
        fun isPortrait(): Boolean {
            val context = globalContext ?: return true // 默认竖屏
            
            try {
                // 使用WindowManager获取实际的显示方向
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (windowManager != null) {
                    val rotation = windowManager.defaultDisplay.rotation
                    val isPortrait = when (rotation) {
                        android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_180 -> true // 竖屏
                        android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_270 -> false // 横屏
                        else -> true // 默认竖屏
                    }
                    
                    // 获取屏幕实际尺寸进行验证
                    val displayMetrics = context.resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels
                    val actuallyPortrait = screenHeight > screenWidth
                    
                    // 优先使用屏幕尺寸判断，因为更准确
                    return actuallyPortrait
                }
                
                // 备用方法：使用Configuration获取方向
                val orientation = context.resources.configuration.orientation
                return orientation == Configuration.ORIENTATION_PORTRAIT
                
            } catch (e: Exception) {
                LogManager.e(TAG, "获取设备方向失败: ${e.message}")
                return true // 默认竖屏
            }
        }
        
        /**
         * 获取当前的方向信息（用于调试）
         */
        @JvmStatic
        fun getCurrentOrientationInfo(): String {
            val context = globalContext ?: return "无Context"
            
            try {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val rotation = windowManager?.defaultDisplay?.rotation ?: -1
                val rotationName = when (rotation) {
                    android.view.Surface.ROTATION_0 -> "ROTATION_0(竖屏)"
                    android.view.Surface.ROTATION_90 -> "ROTATION_90(左转90°)"
                    android.view.Surface.ROTATION_180 -> "ROTATION_180(倒转)"
                    android.view.Surface.ROTATION_270 -> "ROTATION_270(右转90°)"
                    else -> "未知($rotation)"
                }
                
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                
                val configOrientation = context.resources.configuration.orientation
                val configName = when (configOrientation) {
                    Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
                    Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
                    else -> "UNDEFINED"
                }
                
                val isPortraitResult = isPortrait()
                val cameraResolution = getCameraResolution()
                
                return "旋转:$rotationName, 屏幕:${screenWidth}x${screenHeight}, 配置:$configName, 判断:${if(isPortraitResult) "竖屏" else "横屏"}, 摄像头:${cameraResolution.first}x${cameraResolution.second}"
            } catch (e: Exception) {
                return "获取失败: ${e.message}"
            }
        }
        
        // 服务端屏幕分辨率（用于激光点坐标映射）
        private var realScreenWidth = 1920
        private var realScreenHeight = 1080
        
        // 保存MainActivity实例的弱引用，用于更新透视变换矩阵
        private var mainActivityInstance: java.lang.ref.WeakReference<MainActivity>? = null
        
        @JvmStatic
        fun setMainActivityInstance(activity: MainActivity) {
            mainActivityInstance = java.lang.ref.WeakReference(activity)
        }
        
        @JvmStatic
        fun setRealScreenSize(width: Int, height: Int) {
            val oldWidth = realScreenWidth
            val oldHeight = realScreenHeight
            
            realScreenWidth = width
            realScreenHeight = height
            LogManager.d(TAG, "已更新服务端屏幕分辨率: ${width}x${height} (原分辨率: ${oldWidth}x${oldHeight})")
            
            // 如果分辨率发生了变化，需要更新透视变换矩阵
            if (oldWidth != width || oldHeight != height) {
                val activity = mainActivityInstance?.get()
                if (activity != null) {
                    try {
                        activity.laserPointDetector.updatePerspectiveMatrix()
                        LogManager.d(TAG, "已更新透视变换矩阵以适应新的屏幕分辨率")
                    } catch (e: Exception) {
                        LogManager.e(TAG, "更新透视变换矩阵失败: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    LogManager.w(TAG, "MainActivity实例不可用，无法更新透视变换矩阵")
                }
            }
        }
        
        @JvmStatic
        fun getRealScreenSize(): Pair<Int, Int> {
            return Pair(realScreenWidth, realScreenHeight)
        }
    }
    
    // 摄像头工具
    lateinit var camera2Helper: Camera2Helper
    
    // 激光点检测器
    lateinit var laserPointDetector: LaserPointDetector
    
    // 设置管理器
    lateinit var settingsManager: SettingsManager
    
    // 日志管理器
    private lateinit var logManager: LogManager
    
    // GPU 图像处理器 - 修改为公开访问
    val cameraImageProcessor: CameraImageProcessor? get() = _cameraImageProcessor
    var _cameraImageProcessor: CameraImageProcessor? = null

    // 标记可以运行 gpu 代码盏
    // 这个配置极其中要，要确保在摄像头关闭前设置为 false
    // 摄像头完全打开后 2s 左右 再设置为 true
    // 否则 gpu 代码运行会出现 x505, 1285 (内存溢出) 的错误
    var canRunGpuProcess = false

    // debug 功能启用
    private val debugFunctionEnabled = mutableStateOf(false)

    // 检测状态
    private val detectionState = mutableStateOf(DetectionState.IDLE)
    
    // 高速模式状态 - 在激光点检测阶段使用
    val useHighSpeed = mutableStateOf(false)

    // 激光检测方式选择：true=OpenGL方式（高性能），false=Surface方式（兼容性好）
    val useOpenGLDetection = true
    
    // 测试模式标记 - 在测试模式下，屏幕检测不会增加计数器
    private var isTestMode = false
    
    // 添加一个标记来跟踪是否有活跃的操作（测试、检测等）
    private var hasActiveOperation = false
    
    // 添加一个标记来跟踪用户是否真的想退出APP
    private var userWantsToExit = false
    
    // 屏幕检测计数器
    private var screenDetectionCounter = 0
    
    // 最近检测到的激光点坐标
    private val lastDetectedPointX = mutableStateOf(0)
    private val lastDetectedPointY = mutableStateOf(0)
    
    // 温度监控相关
    val batteryTemperature = mutableStateOf(0f)
    val cpuTemperature = mutableStateOf(0f)
    private var temperatureUpdateHandler: Handler? = null
    private var temperatureReceiver: BroadcastReceiver? = null
    
    // 帧处理线程
    private var frameProcessingThread: Thread? = null
    private var isFrameProcessingRunning = AtomicBoolean(false)
    private var lastFrameProcessTime = 0L
    
    // 性能统计
    private var totalFramesProcessed = 0
    private var totalProcessingTime = 0L
    private var lastPerformanceLogTime = 0L
    private var maxProcessingTime = 0L
    private var minProcessingTime = Long.MAX_VALUE
    
    // 检测状态枚举
    enum class DetectionState {
        IDLE,               // 空闲状态
        SCREEN_DETECTION,  // 屏幕区域检测阶段
        LASER_DETECTION    // 激光点检测阶段
    }

    /**
     * 切换日志状态以匹配检测状态
     */
    private fun switchLogState(detectionState: DetectionState) {
        try {
            val logState = when (detectionState) {
                DetectionState.IDLE -> LogManager.LogState.DEFAULT
                DetectionState.SCREEN_DETECTION -> LogManager.LogState.SCREEN_DETECT
                DetectionState.LASER_DETECTION -> LogManager.LogState.LASER_DETECT
            }
            
            logManager.switchLogState(logState)
            LogManager.i(TAG, "已切换日志状态为: $logState (检测状态: $detectionState)")
        } catch (e: Exception) {
            LogManager.e(TAG, "切换日志状态失败", e)
        }
    }

    fun cameraPrepared(): Boolean? {
        return camera2Helper?.isRunning()
    }

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            LogManager.d(TAG, "摄像头权限已授予")
            setupContent()
        } else {
            Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置全局context用于方向检测
        setGlobalContext(this)
        
        // 设置MainActivity实例引用，用于更新透视变换矩阵
        setMainActivityInstance(this)

        // 初始化LogManager
        logManager = LogManager.getInstance() ?: throw IllegalStateException("LogManager未初始化")
        LogManager.i(TAG, "MainActivity启动，LogManager已初始化")

        // 输出设备信息
        logDeviceInfo()

        // 在logDeviceInfo完成后立即进行版本检查
        performVersionCheck()

        // 阻止手机休眠，保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        LogManager.d(TAG, "已设置屏幕常亮，阻止手机休眠")

        // 记录初始方向信息
        LogManager.d(TAG, "应用启动时的方向信息: ${getCurrentOrientationInfo()}")

        // 初始化OpenCV
        if (!OpenCVLoader.initDebug()) {
            LogManager.e(TAG, "OpenCV初始化失败")
            Toast.makeText(this, getString(R.string.opencv_init_failed), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        LogManager.d(TAG, "OpenCV初始化成功")
        
        // 初始化设置管理器
        settingsManager = SettingsManager.getInstance(this)
        
        // 【修复】先初始化Camera工具，再应用启动设置
        // 这样确保applyStartupSettings可以正确设置摄像头参数
        camera2Helper = Camera2Helper(this, settingsManager)
        
        // 应用启动时的设置（语言等需要在UI创建前应用）
        applyStartupSettings()
        
        // 获取并打印所有摄像头的详细信息
        LogManager.d(TAG, "开始获取手机上所有摄像头的详细信息...")
        camera2Helper.getAllCameraInfo()
        
        // 创建激光点检测器实例，但不进行初始化
                        // 初始化将在受控端连接成功后进行
        laserPointDetector = LaserPointDetector()
        laserPointDetector.setListener(this)
        
        // 初始化 GPU 图像处理器
        _cameraImageProcessor = CameraImageProcessor(this)

        // 设置返回键处理 - 适用于Android 13及以上版本
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 使用统一的返回键处理逻辑
                handleBackPress()
            }
        })

        // 检查并请求权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupContent()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        
        // 添加网络连接状态监听
        setupNetworkStatusObserver()

        // 帧处理线程 只在屏幕区域检测时启动，其它时刻不启动
        // startFrameProcessingThread()
        
        // 启动温度监控
        startTemperatureMonitoring()
    }
    
    /**
     * 应用启动时的设置
     */
    private fun applyStartupSettings() {
        try {
            LogManager.d(TAG, "=== applyStartupSettings 开始 ===")
            
            // 获取当前设置
            val currentSettings = settingsManager.getSettings()
            LogManager.d(TAG, "启动时设置: $currentSettings")
            
            // 【移除】不再在这里处理语言设置，由Application全局统一处理
            // 语言设置已通过Application的ActivityLifecycleCallbacks自动处理
            
            // 应用摄像头相关设置（在UI创建前就应用，避免启动时使用错误的默认设置）
            setCameraResolution(currentSettings.resolution.width, currentSettings.resolution.height)
            useHighSpeed.value = (currentSettings.frameRateMode == com.scieford.laserctrlmouse.settings.FrameRateMode.HIGH_SPEED)
            
            // 【修复】应用摄像头ID设置 - 确保启动时就使用用户设置的摄像头
            camera2Helper.setCurrentCameraId(currentSettings.selectedCameraId)
            LogManager.d(TAG, "启动时应用摄像头ID设置: ${currentSettings.selectedCameraId}")
            
            // 应用曝光时间设置
            camera2Helper.setExposureTimeMs(currentSettings.exposureTimeMs)
            LogManager.d(TAG, "启动时应用曝光时间设置: ${currentSettings.exposureTimeMs}ms")
            
            LogManager.d(TAG, "启动时设置应用完成 - 分辨率: ${currentSettings.resolution.displayName}, 高速模式: ${useHighSpeed.value}, 摄像头ID: ${currentSettings.selectedCameraId}, 曝光时间: ${currentSettings.exposureTimeMs}ms")
            LogManager.d(TAG, "=== applyStartupSettings 结束 ===")
        } catch (e: Exception) {
            LogManager.e(TAG, "应用启动时设置失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 设置网络连接状态监听
     */
    private fun setupNetworkStatusObserver() {
        // 不直接设置网络监听器，避免覆盖ConnectActivity的监听器
        // 改用定期检查的方式来监控连接状态变化
        startConnectionStatusMonitoring()
    }
    
    // 连接状态监控相关
    private var connectionStatusHandler: Handler? = null
    private var lastKnownConnectionStatus = false
    
    /**
     * 启动连接状态监控
     * 定期检查连接状态，当状态变化时更新UI
     */
    private fun startConnectionStatusMonitoring() {
        connectionStatusHandler = Handler(mainLooper)
        lastKnownConnectionStatus = ConnectActivity.isServerConnected
        
        val statusCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    val currentStatus = ConnectActivity.isServerConnected
                    
                    // 检查连接状态是否发生变化
                    if (currentStatus != lastKnownConnectionStatus) {
                        LogManager.d(TAG, "检测到连接状态变化: $lastKnownConnectionStatus -> $currentStatus")
                        
                        if (!currentStatus && lastKnownConnectionStatus) {
                            // 从连接变为断开
                            LogManager.d(TAG, "检测到受控端断开连接")
                            handleServerDisconnected()
                        }
                        
                        lastKnownConnectionStatus = currentStatus
                    }
                    
                    // 继续监控
                    connectionStatusHandler?.postDelayed(this, 1000) // 每秒检查一次
                } catch (e: Exception) {
                    LogManager.e(TAG, "连接状态监控异常: ${e.message}")
                    // 发生异常时延长检查间隔
                    connectionStatusHandler?.postDelayed(this, 2000)
                }
            }
        }
        
        // 开始监控
        connectionStatusHandler?.post(statusCheckRunnable)
        LogManager.d(TAG, "已启动连接状态监控")
    }
    
    /**
     * 停止连接状态监控
     */
    private fun stopConnectionStatusMonitoring() {
        connectionStatusHandler?.removeCallbacksAndMessages(null)
        connectionStatusHandler = null
        LogManager.d(TAG, "已停止连接状态监控")
    }
    
    /**
     * 处理受控端断开连接
     */
    private fun handleServerDisconnected() {
        LogManager.d(TAG, "=== handleServerDisconnected() 开始 ===")
        
        // 如果当前不在空闲状态，重置到空闲状态
        if (detectionState.value != DetectionState.IDLE) {
            LogManager.d(TAG, "检测到受控端断开，当前检测状态为: ${detectionState.value}，重置到空闲状态")
            resetToIdleState()
        }
        
        // 显示提示消息
        Toast.makeText(this, getString(R.string.server_connection_lost), Toast.LENGTH_SHORT).show()
        
        LogManager.d(TAG, "=== handleServerDisconnected() 结束 ===")
    }
    
    private fun setupContent() {
        // 准备模板文件，但不初始化激光点检测器
        prepareTemplateFiles()
        
        // 配置所有组件使用统一的分辨率
        configureUniformResolution()
        
        // 【移除】语言状态检查，现在完全由Application统一管理
        // logCurrentLanguageStatus()
        
        setContent {
            LaserCtrlMouseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LaserDetectionScreen(
                        camera2Helper,
                        detectionState,
                        debugFunctionEnabled,
                        lastDetectedPointX,
                        lastDetectedPointY,
                        onStartDetection = { startDetection() },
                        onStartDebugTest = { startDebugTest() },
                        onStartScreenDetectionTest = { startScreenDetectionTest() },
                        onTestGpuResource = { testGpuResourceRecreation() },
                        onConnectServer = { openConnectActivity() },
                        onOpenSettings = { openSettingsActivity() },
                        onOpenHelp = { openHelpPage() },
                        onOpenSystemSettings = { openSystemActivity() },
                        isServerConnected = { ConnectActivity.isServerConnected },
                        getConnectedServerName = { ConnectActivity.connectedServer?.name ?: "" }
                    )
                }
            }
        }
        
        // 移除原来的按钮添加调用，现在所有按钮都在Compose中统一管理
        // addConnectButton()
        // addSettingsButton()
        
        // 【DEBUG】自动启动激光点检测，用于快速调试
        LogManager.d(TAG, "【DEBUG】自动启动激光点检测用于调试")
        
        // APP 启动后，直接进入激光点检测模式，用于DEBUG 延迟启动，确保UI已经初始化完成
        if(false) {
            startDebugTest()
        }
    }
    
    /**
     * 准备模板文件
     */
    private fun prepareTemplateFiles() {
        try {
            // 创建assets目录中的模板图像的临时文件
            val templateImageFile = File(cacheDir, "template.jpeg")
            // 从assets复制模板图像到缓存目录
            assets.open("template.jpeg").use { input ->
                FileOutputStream(templateImageFile).use { output ->
                    input.copyTo(output)
                }
            }
            LogManager.d(TAG, "模板图像已复制到: ${templateImageFile.absolutePath}")

        } catch (e: Exception) {
            LogManager.e(TAG, "准备模板文件失败: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.template_file_prepare_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 使用指定的屏幕尺寸初始化激光点检测器
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     */
    private fun initLaserPointDetector(screenWidth: Int, screenHeight: Int) {
        try {
            // 获取临时文件路径用于保存模板图像
            val cacheDir = cacheDir
            val templateFile = File(cacheDir, "template.jpeg")

            // 初始化检测器
            LogManager.d(TAG, "初始化激光点检测器(尺寸:${screenWidth}x${screenHeight})")
            laserPointDetector = LaserPointDetector()
            laserPointDetector.initScreenDetector(
                templateFile.absolutePath,
                screenWidth,
                screenHeight
            )
            
            // 设置检测回调
            laserPointDetector.setListener(this)
            
            LogManager.d(TAG, "激光点检测器初始化完成")
        } catch (e: Exception) {
            LogManager.e(TAG, "初始化激光点检测器失败: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.laser_detector_init_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 开始检测流程
     */
    private fun startDetection() {
        LogManager.d(TAG, "=== startDetection() 开始 ===")
        LogManager.d(TAG, "开始启动检测流程，当前状态: ${detectionState.value}")
        LogManager.d(TAG, "ConnectActivity.isServerConnected: ${ConnectActivity.isServerConnected}")
        
        // 检查是否已连接到受控端
        if (!ConnectActivity.isServerConnected) {
            LogManager.w(TAG, "受控端未连接，显示连接对话框")
            // 如果没有连接，询问用户是否连接
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.not_connected_title))
                .setMessage(getString(R.string.not_connected_message))
                .setPositiveButton(getString(R.string.connect)) { _, _ -> openConnectActivity() }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
            return
        }
        
        LogManager.d(TAG, "受控端已连接，继续检测流程")
        
        // 标记为有活跃操作
        hasActiveOperation = true
        userWantsToExit = false
        LogManager.d(TAG, "设置活跃操作标记: hasActiveOperation=$hasActiveOperation")
        
        // 重置检测计数器
        screenDetectionCounter = 0
        LogManager.d(TAG, "重置屏幕检测计数器为0")
        
        // 更新状态为屏幕检测阶段
        val oldState = detectionState.value
        detectionState.value = DetectionState.SCREEN_DETECTION
        LogManager.d(TAG, "状态已更新: $oldState -> ${detectionState.value}")

        // 【新增】切换日志状态到屏幕检测
        switchLogState(DetectionState.SCREEN_DETECTION)

        // 设置摄像头为屏幕检测模式（自动曝光）
        camera2Helper.setLaserDetectMode(false)
        LogManager.d(TAG, "设置摄像头为屏幕检测模式（自动曝光）")

        LogManager.d(TAG, "开始屏幕区域检测阶段")
        
        // 检查模板图像和特征点是否已正确初始化
        try {
            val templateKeypoints = laserPointDetector.javaClass.getDeclaredField("templateKeypoints")
            templateKeypoints.isAccessible = true
            val keypointsObj = templateKeypoints.get(laserPointDetector)
            if (keypointsObj != null) {
                LogManager.d(TAG, "模板特征点已初始化")
            } else {
                LogManager.w(TAG, "警告：模板特征点未初始化")
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "无法检查模板特征点状态: ${e.message}")
        }
        
        // 启动帧处理线程，用于屏幕区域检测
        LogManager.d(TAG, "启动帧处理线程用于屏幕区域检测")
        startFrameProcessingThread()
        
        // 重启摄像头
        LogManager.d(TAG, "准备重启摄像头以开始屏幕检测...")
        restartCamera()
        
        LogManager.d(TAG, "=== startDetection() 结束，最终状态: ${detectionState.value} ===")
    }
    
    /**
     * 重启摄像头 - 使用GLSurfaceView重新创建策略
     */
    private fun restartCamera() {
        LogManager.d(TAG, "=== restartCamera 开始（GLSurfaceView重新创建策略）===")
        
        // 【修复】添加重连状态检查，防止在不合适的时机重启摄像头
        if (isRecreatingGLSurfaceView.value) {
            LogManager.w(TAG, "GLSurfaceView正在重新创建中，跳过重启摄像头")
            return
        }
        
        canRunGpuProcess = false
        LogManager.d(TAG, "已禁用GPU处理")

        // 读取并应用最新设置
        applyCurrentSettings()

        // 【修复】增加当前状态检查，确保在正确的状态下进行重启
        LogManager.d(TAG, "重启摄像头时的状态检查:")
        LogManager.d(TAG, "  - 检测状态: ${detectionState.value}")
        LogManager.d(TAG, "  - 连接状态: ${ConnectActivity.isServerConnected}")
        LogManager.d(TAG, "  - 网络状态: ${ConnectActivity.getNetworkClient().getConnectionStatus()}")

        // 使用GLSurfaceView重新创建策略，参考CameraPreview.kt的逻辑
        LogManager.d(TAG, "使用GLSurfaceView重新创建策略重启摄像头")
        
        // 设置重建标记，禁用相关操作
        isRecreatingGLSurfaceView.value = true
        LogManager.d(TAG, "设置GLSurfaceView重建标记")
        
        // 关闭当前的摄像头会话和设备
        LogManager.d(TAG, "关闭当前摄像头会话")
        camera2Helper.closeCamera()
        
        // 获取GLCameraSurfaceView引用
        val cameraPreview = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
        
        // 清除屏幕边框显示
        cameraPreview?.setScreenBorder(null)
        
        // 【修复】在OpenGL线程中安全释放当前GPU资源
        cameraPreview?.queueEvent {
            LogManager.d(TAG, "在OpenGL线程中安全释放GPU图像处理器资源")
            try {
                _cameraImageProcessor?.release()
                _cameraImageProcessor = null
                LogManager.d(TAG, "GPU图像处理器资源释放完成")
            } catch (e: Exception) {
                LogManager.e(TAG, "在OpenGL线程中释放GPU资源失败: ${e.message}")
                e.printStackTrace()
            }
        }
        
        // 【修复】等待一段时间确保OpenGL线程完成资源释放
        Handler(mainLooper).postDelayed({
            // 立即触发GLSurfaceView重新创建
            LogManager.d(TAG, "触发GLSurfaceView重新创建 - 递增key值: ${glSurfaceViewKey.value} -> ${glSurfaceViewKey.value + 1}")
            glSurfaceViewKey.value += 1
            
            // 延迟完成重建标记，等待新的GLSurfaceView完全初始化
            // 根据检测状态调整延迟时间，屏幕检测需要更长时间
            val delayTime = when (detectionState.value) {
                DetectionState.SCREEN_DETECTION -> 2500 // 屏幕检测需要更长时间
                DetectionState.LASER_DETECTION -> 2000  // 激光检测稍短但也需要足够时间
                else -> 1500 // 普通预览
            }
            
            Handler(mainLooper).postDelayed({
                LogManager.d(TAG, "GLSurfaceView重新创建完成，允许后续操作（延迟${delayTime}ms）")
                isRecreatingGLSurfaceView.value = false
                
                // 【修复】确保在重建完成后状态仍然正确
                LogManager.d(TAG, "重建完成后状态检查:")
                LogManager.d(TAG, "  - 检测状态: ${detectionState.value}")
                LogManager.d(TAG, "  - 连接状态: ${ConnectActivity.isServerConnected}")
                LogManager.d(TAG, "  - GPU图像处理器状态: ${if (_cameraImageProcessor != null) "已创建" else "null"}")
                
                // 【修复】如果GPU图像处理器仍然为null，再次尝试创建
                if (_cameraImageProcessor == null && detectionState.value == DetectionState.LASER_DETECTION) {
                    LogManager.w(TAG, "重建完成后GPU图像处理器仍为null，尝试手动重新创建")
                    val newCameraPreview = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
                    if (newCameraPreview != null) {
                        newCameraPreview.queueEvent {
                            try {
                                LogManager.d(TAG, "手动重新创建GPU图像处理器")
                                _cameraImageProcessor = CameraImageProcessor(this@MainActivity)
                                newCameraPreview.setImageProcessor(_cameraImageProcessor!!)
                                _cameraImageProcessor!!.initialize(getCameraWidth(), getCameraHeight())
                                _cameraImageProcessor!!.initializeGPUDetector()
                                LogManager.d(TAG, "手动重新创建GPU图像处理器完成")
                            } catch (e: Exception) {
                                LogManager.e(TAG, "手动重新创建GPU图像处理器失败: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                    }
                }
                
                // 【修复】不再需要重新设置帧率监听器，UI直接实时读取currentFps值
                try {
                    val currentFrameRate = camera2Helper.getCurrentFps()
                    LogManager.d(TAG, "当前Camera2Helper帧率: $currentFrameRate fps (UI将自动读取)")
                } catch (e: Exception) {
                    LogManager.e(TAG, "获取当前帧率失败: ${e.message}")
                    e.printStackTrace()
                }
                
            }, delayTime.toLong())
        }, 500) // 等待500ms确保OpenGL线程资源释放完成
        
        LogManager.d(TAG, "=== restartCamera 结束（等待GLSurfaceView重新创建）===")
    }

    /**
     * 读取并应用当前设置
     */
    private fun applyCurrentSettings() {
        try {
            LogManager.d(TAG, "=== applyCurrentSettings 开始 ===")
            
            // 获取当前设置
            val currentSettings = settingsManager.getSettings()
            LogManager.d(TAG, "当前设置: $currentSettings")
            
            // 应用分辨率设置
            setCameraResolution(currentSettings.resolution.width, currentSettings.resolution.height)
            LogManager.d(TAG, "应用分辨率设置: ${currentSettings.resolution.displayName}")
            
            // 应用摄像头ID设置
            camera2Helper.setCurrentCameraId(currentSettings.selectedCameraId)
            LogManager.d(TAG, "应用摄像头ID设置: ${currentSettings.selectedCameraId}")
            
            // 应用曝光时间设置
            camera2Helper.setExposureTimeMs(currentSettings.exposureTimeMs)
            LogManager.d(TAG, "应用曝光时间设置: ${currentSettings.exposureTimeMs}ms")
            
            // 应用帧率模式设置
            useHighSpeed.value = (currentSettings.frameRateMode == com.scieford.laserctrlmouse.settings.FrameRateMode.HIGH_SPEED)
            LogManager.d(TAG, "应用帧率模式设置: ${currentSettings.frameRateMode}, 高速模式: ${useHighSpeed.value}")
            
            LogManager.d(TAG, "=== applyCurrentSettings 结束 ===")
        } catch (e: Exception) {
            LogManager.e(TAG, "应用设置失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 【已移除】语言设置现在完全由Application统一管理
     * 这里保留方法签名以避免编译错误，但实际不进行任何操作
     */
    private fun applyLanguageImmediately(languageCode: String) {
        LogManager.d(TAG, "语言设置已由Application统一管理，MainActivity不再单独处理")
        // 不进行任何语言设置操作，完全依赖Application的全局管理
    }
    
    /**
     * 【已移除】语言设置现在完全由Application统一管理
     * 这里保留方法签名以避免编译错误，但实际不进行任何操作
     */
    private fun updateActivityLocaleForced(languageCode: String) {
        LogManager.d(TAG, "语言设置已由Application统一管理，MainActivity不再单独处理")
        // 不进行任何语言设置操作，完全依赖Application的全局管理
    }
    
    /**
     * 【已移除】语言设置现在完全由Application统一管理
     * 这里保留方法签名以避免编译错误，但实际不进行任何操作
     */
    private fun restartApplication() {
        LogManager.d(TAG, "语言设置已由Application统一管理，不需要重启应用")
        // 不进行任何操作，完全依赖Application的全局管理
    }

    /**
     * 【测试功能】重新创建GPU资源但不重启摄像头
     * 用于测试在不重启摄像头的情况下，重新创建GPU资源是否会避免OpenGL 1285错误
     */
    fun testGpuResourceRecreation() {
        LogManager.d(TAG, "=== testGpuResourceRecreation 开始 ===")
        
        // 1. 暂停GPU处理
        canRunGpuProcess = false
        LogManager.d(TAG, "【测试】已暂停GPU处理")
        
        // 2. 获取GLCameraSurfaceView引用
        val cameraPreview = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
        
        // 3. 在OpenGL线程中安全释放GPU资源（不关闭摄像头）
        cameraPreview?.queueEvent {
            LogManager.d(TAG, "【测试】在OpenGL线程中释放GPU图像处理器资源")
            try {
                // 在正确的OpenGL上下文中释放资源
                _cameraImageProcessor?.release()
                LogManager.d(TAG, "【测试】GPU图像处理器资源释放完成")
            } catch (e: Exception) {
                LogManager.e(TAG, "【测试】释放GPU图像处理器资源时发生错误: ${e.message}")
                e.printStackTrace()
            }
        }
        
        // 4. 等待一段时间确保资源释放完成，然后重新创建GPU资源
        Handler(mainLooper).postDelayed({
            try {
                LogManager.d(TAG, "【测试】开始重新创建GPU图像处理器")
                
                // 5. 重新创建 GPU 图像处理器
                _cameraImageProcessor = CameraImageProcessor(this)
                
                cameraPreview?.let { preview ->
                    LogManager.d(TAG, "【测试】通过GLCameraSurfaceView重新设置图像处理器")
                    
                    // 6. 在OpenGL线程中设置新的图像处理器
                    preview.queueEvent {
                        try {
                            LogManager.d(TAG, "【测试】在OpenGL线程中设置新的图像处理器")
                            _cameraImageProcessor?.let { processor ->
                                preview.setImageProcessor(processor)
                                LogManager.d(TAG, "【测试】已在OpenGL线程中设置新的图像处理器")
                            }
                        } catch (e: Exception) {
                            LogManager.e(TAG, "【测试】在OpenGL线程中设置图像处理器失败: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                    
                    // 7. 延迟重新初始化图像处理器，但不重启摄像头预览
                    Handler(mainLooper).postDelayed({
                        try {
                            // 在OpenGL线程中重新初始化图像处理器
                            preview.queueEvent {
                                try {
                                    LogManager.d(TAG, "【测试】在OpenGL线程中重新初始化图像处理器")
                                    _cameraImageProcessor?.let { processor ->
                                        preview.initImageProcessor()
                                        LogManager.d(TAG, "【测试】已在OpenGL线程中重新初始化图像处理器")
                                    }
                                } catch (e: Exception) {
                                    LogManager.e(TAG, "【测试】在OpenGL线程中初始化图像处理器失败: ${e.message}")
                                    e.printStackTrace()
                                }
                            }

                            LogManager.d(TAG, "【测试】GPU资源重新创建完成，检测状态: ${detectionState.value}")

                            // 8. 延迟重新启用GPU处理
                            Handler(mainLooper).postDelayed({
                                canRunGpuProcess = true
                                LogManager.d(TAG, "【测试】GPU处理已重新启用，当前检测状态: ${detectionState.value}")
                                
                                // 在主线程显示测试完成提示
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, getString(R.string.gpu_resource_recreated), Toast.LENGTH_SHORT).show()
                                }
                            }, 2000) // 延迟2秒重新启用GPU处理
                            
                        } catch (e: Exception) {
                            LogManager.e(TAG, "【测试】重新初始化图像处理器过程中发生错误: ${e.message}")
                            e.printStackTrace()
                        }
                    }, 100) // 延迟100ms重新初始化
                    
                } ?: run {
                    LogManager.e(TAG, "【测试】GLCameraSurfaceView未找到")
                }
                
            } catch (e: Exception) {
                LogManager.e(TAG, "【测试】重新创建GPU资源过程中发生错误: ${e.message}")
                e.printStackTrace()
            }
        }, 300) // 等待300ms确保资源释放完成
        
        LogManager.d(TAG, "=== testGpuResourceRecreation 结束 ===")
    }

    fun startDebugTest() {
        LogManager.d(TAG, "【DEBUG】开始测试")
        
        // 标记为有活跃操作和测试模式
        hasActiveOperation = true
        isTestMode = true
        userWantsToExit = false
        
        runOnUiThread {
            android.os.Handler(mainLooper).postDelayed({
                try {
                    LogManager.d(TAG, "【DEBUG】开始自动激光点检测流程")

                    // 初始化激光点检测器（使用默认屏幕尺寸）
                    initLaserPointDetector(getScreenWidth(), getScreenHeight())

                    // 手动设置屏幕角点坐标（调试模式）
                    val leftTop = org.opencv.core.Point(147.8297576904297, 124.25926971435547)
                    val leftBottom = org.opencv.core.Point(114.49881744384766, 1021.2262573242188)
                    val rightBottom = org.opencv.core.Point(1719.70751953125, 1009.8208618164062)
                    val rightTop = org.opencv.core.Point(1692.1458740234375, 145.2268829345703)
                    
                    laserPointDetector?.setScreenCorners(leftTop, leftBottom, rightBottom, rightTop)
                    LogManager.d(TAG, "【DEBUG】已设置屏幕角点坐标")

                    // 验证初始化状态
                    val isInitialized = laserPointDetector?.isInitialized() ?: false
                    LogManager.d(TAG, "【DEBUG】激光点检测器初始化状态: $isInitialized")

                    // 直接进入激光点检测模式，跳过屏幕检测
                    detectionState.value = DetectionState.LASER_DETECTION
                    LogManager.d(TAG, "【DEBUG】状态已设置为激光点检测模式")

                    // 【新增】切换日志状态到激光检测
                    switchLogState(DetectionState.LASER_DETECTION)

                    // 设置摄像头为激光检测模式（手动曝光）
                    camera2Helper.setLaserDetectMode(true)
                    LogManager.d(TAG, "【DEBUG】设置摄像头为激光检测模式（手动曝光）")

                    // 延迟启动摄像头，确保所有初始化完成
                    Handler(mainLooper).postDelayed({
                        LogManager.d(TAG, "【DEBUG】延迟启动摄像头预览")
                        restartCamera()
                    }, 200) // 额外延迟200ms确保初始化完成

                } catch (e: Exception) {
                    LogManager.e(TAG, "【DEBUG】自动启动激光点检测失败: ${e.message}")
                    e.printStackTrace()
                }
            }, 100) // 延迟 启动
        }
    }

    /**
     * 【DEBUG】进入屏幕检测测试模式
     */
    fun startScreenDetectionTest() {
        LogManager.d(TAG, "【DEBUG】开始屏幕检测测试")
        
        // 标记为有活跃操作和测试模式
        hasActiveOperation = true
        isTestMode = true
        userWantsToExit = false
        
        runOnUiThread {
            android.os.Handler(mainLooper).postDelayed({
                try {
                    LogManager.d(TAG, "【DEBUG】进入屏幕检测测试模式")

                    // 初始化激光点检测器（使用默认屏幕尺寸）
                    initLaserPointDetector(getScreenWidth(), getScreenHeight())

                    // 重置检测计数器
                    screenDetectionCounter = 0
                    
                    // 进入屏幕检测模式
                    detectionState.value = DetectionState.SCREEN_DETECTION
                    LogManager.d(TAG, "【DEBUG】状态已设置为屏幕检测测试模式")

                    // 【新增】切换日志状态到屏幕检测
                    switchLogState(DetectionState.SCREEN_DETECTION)

                    // 设置摄像头为屏幕检测模式（自动曝光）
                    camera2Helper.setLaserDetectMode(false)
                    LogManager.d(TAG, "【DEBUG】设置摄像头为屏幕检测模式（自动曝光）")

                    // 启动帧处理线程用于屏幕检测测试
                    LogManager.d(TAG, "【DEBUG】启动帧处理线程用于屏幕检测测试")
                    startFrameProcessingThread()

                    // 启动摄像头预览
                    restartCamera()

                } catch (e: Exception) {
                    LogManager.e(TAG, "【DEBUG】启动屏幕检测测试失败: ${e.message}")
                    e.printStackTrace()
                }
            }, 100) // 延迟启动
        }
    }


    override fun onResume() {
        super.onResume()
        LogManager.d(TAG, "Activity恢复")
        
        // 重新设置全局context，确保方向检测正常
        setGlobalContext(this)
        
        // 【移除】不再在这里处理语言设置，由Application全局统一处理
        // 语言设置已通过Application的ActivityLifecycleCallbacks自动处理
        
        // 重置退出确认标记，避免从其他界面返回后误退出
        userWantsToExit = false
        
        // 重新启动连接状态监控
        startConnectionStatusMonitoring()
        
        // 检查是否刚从ConnectActivity返回
        if (justReturnedFromConnect) {
            LogManager.d(TAG, "刚从ConnectActivity返回，等待checkConnectionStatusAfterReturn处理")
            return
        }
        
        // 如果摄像头在暂停前是运行的，需要重新启动
        if (cameraWasRunningBeforePause) {
            LogManager.d(TAG, "检测到摄像头在暂停前是运行的，准备重新启动")
            
            // 恢复检测状态
            detectionState.value = detectionStateBeforePause
            
            // 如果恢复的状态是屏幕检测，需要重新启动帧处理线程
            if (detectionStateBeforePause == DetectionState.SCREEN_DETECTION) {
                LogManager.d(TAG, "恢复屏幕检测状态，重新启动帧处理线程")
                startFrameProcessingThread()
            }
            
            // 延迟一点时间让界面稳定后再启动摄像头
            Handler(mainLooper).postDelayed({
                try {
                    LogManager.d(TAG, "Activity恢复后重新启动摄像头，状态: ${detectionState.value}")
                    restartCamera()
                    cameraWasRunningBeforePause = false // 重置标记
                } catch (e: Exception) {
                    LogManager.e(TAG, "Activity恢复后重新启动摄像头失败: ${e.message}")
                    e.printStackTrace()
                }
            }, 500) // 延迟500ms
        }
    }
    
    override fun onPause() {
        super.onPause()
        LogManager.d(TAG, "Activity暂停")
        
        // 停止连接状态监控
        stopConnectionStatusMonitoring()
        
        // 记录摄像头当前是否在运行
        cameraWasRunningBeforePause = camera2Helper.isRunning()
        
        // 保存当前的检测状态
        detectionStateBeforePause = detectionState.value
        
        LogManager.d(TAG, "暂停时摄像头状态: 运行中=$cameraWasRunningBeforePause, 检测状态=$detectionStateBeforePause")
        
        // 停止帧处理线程
        stopFrameProcessingThread()
        
        // 在暂停时关闭摄像头以释放资源
        if (cameraWasRunningBeforePause) {
            LogManager.d(TAG, "因Activity暂停而关闭摄像头")
            // 先禁用GPU处理
            canRunGpuProcess = false
            
            // 在OpenGL线程中安全释放GPU资源
            val cameraPreview = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
            if (cameraPreview != null && _cameraImageProcessor != null) {
                try {
                    cameraPreview.queueEvent {
                        LogManager.d(TAG, "在OpenGL线程中释放GPU图像处理器资源")
                        try {
                            _cameraImageProcessor?.release()
                            _cameraImageProcessor = null
                            LogManager.d(TAG, "GPU图像处理器资源释放完成")
                        } catch (e: Exception) {
                            LogManager.e(TAG, "在OpenGL线程中释放GPU图像处理器失败: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "释放OpenGL相关资源时发生错误: ${e.message}")
                    // 如果OpenGL线程不可用，直接释放
                    _cameraImageProcessor?.release()
                    _cameraImageProcessor = null
                }
            }
            
            // 关闭摄像头
            camera2Helper.closeCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LogManager.d(TAG, "Activity销毁，开始资源清理")
        
        // 重置语言重启标记
        isLanguageRestarting = false
        
        // 停止连接状态监控
        stopConnectionStatusMonitoring()
        
        // 销毁NetworkClient，释放网络资源
        try {
            val networkClient = ConnectActivity.getNetworkClient()
            networkClient.destroy()
            LogManager.d(TAG, "NetworkClient已销毁")
        } catch (e: Exception) {
            LogManager.e(TAG, "销毁NetworkClient时出错: ${e.message}")
        }
        
        // 清除屏幕常亮标志，恢复正常休眠行为
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        LogManager.d(TAG, "已清除屏幕常亮设置，恢复正常休眠")
        
        // 1. 首先禁用GPU处理
        canRunGpuProcess = false
        
        // 2. 关闭摄像头
        camera2Helper.closeCamera()

        // 3. 停止帧处理线程
        stopFrameProcessingThread()
        
        // 4. 停止温度监控
        stopTemperatureMonitoring()
        
        // 6. 在OpenGL线程中安全释放GPU图像处理器资源
        val cameraPreview = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
        if (cameraPreview != null && _cameraImageProcessor != null) {
            try {
                cameraPreview.queueEvent {
                    LogManager.d(TAG, "在OpenGL线程中释放GPU图像处理器资源")
                    try {
                        _cameraImageProcessor?.release()
                        _cameraImageProcessor = null
                        LogManager.d(TAG, "GPU图像处理器资源释放完成")
                    } catch (e: Exception) {
                        LogManager.e(TAG, "在OpenGL线程中释放GPU图像处理器失败: ${e.message}")
                    }
                }
                
                // 7. 释放GLCameraSurfaceView资源
                cameraPreview.release()
                LogManager.d(TAG, "GLCameraSurfaceView资源已释放")
                
            } catch (e: Exception) {
                LogManager.e(TAG, "释放OpenGL相关资源时发生错误: ${e.message}")
                // 如果OpenGL线程不可用，直接释放
                _cameraImageProcessor?.release()
                _cameraImageProcessor = null
            }
        } else {
            // 如果GLCameraSurfaceView不可用，直接释放GPU图像处理器
            _cameraImageProcessor?.release()
            _cameraImageProcessor = null
        }

        // 8. 释放Surface Bitmap资源
        surfaceBitmap?.recycle()
        surfaceBitmap = null

        // 9. 避免内存泄漏
        laserPointDetector.setListener(null)
        
        // 10. 清理日志文件（应用退出时）
        try {
            LogManager.i(TAG, "应用退出，开始清理日志文件")
            logManager.clearAllLogs()
            logManager.destroy()
        } catch (e: Exception) {
            // 这里仍使用Android Log，因为LogManager可能已经被销毁
            LogManager.e(TAG, "清理LogManager失败: ${e.message}", e)
        }
        
        LogManager.d(TAG, "Activity销毁完成，所有资源已释放")
    }
    
    /**
     * 启动帧处理线程
     * 该线程会以稳定的帧率调用onFrameCaptured方法
     */
    private fun startFrameProcessingThread() {
        // 如果线程已经在运行，先停止
        stopFrameProcessingThread()
        
        // 设置运行标志
        isFrameProcessingRunning.set(true)
        
        // 创建并启动新线程
        frameProcessingThread = Thread {
            LogManager.d(TAG, "帧处理线程已启动")
            lastFrameProcessTime = System.currentTimeMillis()
            
            while (isFrameProcessingRunning.get()) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = currentTime - lastFrameProcessTime
                    
                    // 计算需要等待的时间以保持稳定帧率
                    if (elapsedTime < MIN_FRAME_TIME) {
                        Thread.sleep(MIN_FRAME_TIME - elapsedTime)
                    }
                    
                    // 记录处理开始时间
                    lastFrameProcessTime = System.currentTimeMillis()
                    
                    // 执行帧处理
                    if (isFrameProcessingRunning.get() && detectionState.value != DetectionState.IDLE) {
                        // 启用onFrameCaptured调用，用于Surface方式的图像处理
                        onFrameCaptured()
                    }
                } catch (e: InterruptedException) {
                    LogManager.d(TAG, "帧处理线程被中断")
                    break
                } catch (e: Exception) {
                    LogManager.e(TAG, "帧处理线程异常: ${e.message}")
                    e.printStackTrace()
                }
            }
            
            LogManager.d(TAG, "帧处理线程已停止")
        }.apply {
            name = "FrameProcessingThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }
    
    /**
     * 停止帧处理线程
     */
    private fun stopFrameProcessingThread() {
        isFrameProcessingRunning.set(false)
        
        frameProcessingThread?.let { thread ->
            try {
                thread.interrupt()
                thread.join(500) // 等待最多500ms让线程结束
                LogManager.d(TAG, "帧处理线程已停止")
            } catch (e: InterruptedException) {
                LogManager.e(TAG, "停止帧处理线程被中断: ${e.message}")
                e.printStackTrace()
            }
            frameProcessingThread = null
        }
    }
    
    /**
     * 实现Camera2Helper.ImageCallback接口
     * 处理ImageReader获取的图像数据
     * 注意：此方法目前被禁用，改为使用Surface方式的onFrameCaptured
     */
    override fun onImageAvailable(image: Image, timestamp: Long): Boolean {
        // 直接关闭图像，不进行处理，避免与onFrameCaptured重复处理
        image.close()
        return false
        
        /*
        // 以下代码被注释掉，避免与onFrameCaptured重复处理
        val startTime = System.currentTimeMillis()
        LogManager.d(TAG, "--------onImageAvailable start------${startTime}---${timestamp}---------------")

        try {
            if (detectionState.value != DetectionState.IDLE) {
                LogManager.d(TAG, "收到图像回调：startTime=$startTime 时间戳=${timestamp}, 当前状态=${detectionState.value}")
            }
            
            // 根据当前状态处理图像数据
            when (detectionState.value) {
                DetectionState.SCREEN_DETECTION -> {
                    // 屏幕检测阶段处理...
                }
                DetectionState.LASER_DETECTION -> {
                    // 激光点检测阶段处理...
                }
                else -> {
                    LogManager.d(TAG, "当前处于空闲状态，忽略图像数据")
                    image.close()
                    return false
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "图像回调处理异常: ${e.message}")
            e.printStackTrace()
            image.close()
            return false
        } finally {
            val processingTime = System.currentTimeMillis() - startTime
            LogManager.d(TAG, "--------onImageAvailable end------图像处理总耗时: ${processingTime}ms ${startTime}---${timestamp}--------------" )
        }
        */
    }
    
    /**
     * 兼容旧版本Android的返回键处理
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        // 兼容旧版本Android的返回键处理
        handleBackPress()
    }
    

    
    /**
     * 实现Camera2Helper.FrameCallback接口
     * 处理Camera2 API的帧回调
     * 注意：此方法现在由单独的帧处理线程调用
     * 此方法是唯一的帧处理入口点，统一处理所有状态下的帧数据
     */
    override fun onFrameCaptured(): Boolean {
        val startTime = System.currentTimeMillis()
        try {
            // 获取当前状态的安全副本，避免处理过程中状态变更
            val currentState = detectionState.value
            
            // 根据当前状态处理帧数据
            when (currentState) {
                DetectionState.SCREEN_DETECTION -> {
                    // 屏幕检测阶段 - 获取图像并检测屏幕区域
                    val imageMat = getMatFromSurface()
                    
                    if (imageMat.empty()) {
                        LogManager.w(TAG, "获取图像失败，跳过此帧")
                        return false
                    }

                    // 检测屏幕区域（使用新的方法获取详细结果）
                    val detectStartTime = System.currentTimeMillis()
                    LogManager.d(TAG, "=========== start screen detection ============")
                    val detectionResult = laserPointDetector.detectScreenAreaWithResult(imageMat)
                    val detectTime = System.currentTimeMillis() - detectStartTime

                    if (detectTime > 100) { // 只记录耗时较长的检测
                        LogManager.d(TAG, "屏幕区域检测耗时: ${detectTime}ms")
                    }
                    
                    // 释放Mat对象，避免内存泄漏
                    imageMat.release()
                    
                    if (detectionResult.success) {
                        // 使用同步块更新计数器，避免并发问题
                        synchronized(this) {
                            // 在测试模式下不增加计数器，只进行检测和显示边框
                            if (!isTestMode) {
                                screenDetectionCounter++
                            }
                            LogManager.d(TAG, "屏幕区域检测成功: $screenDetectionCounter/$SCREEN_DETECTION_THRESHOLD (测试模式: $isTestMode)")
                            
                            // 如果检测结果包含角点坐标，在UI上绘制边框
                            detectionResult.corners?.let { corners ->
                                LogManager.d(TAG, "在SurfaceView上绘制屏幕区域边框")
                                val cameraPreview = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
                                cameraPreview?.setScreenBorder(corners)
                            }
                            
                            // 连续检测成功达到阈值，进入激光点检测阶段 (仅在非测试模式下)
                            if (!isTestMode && screenDetectionCounter >= SCREEN_DETECTION_THRESHOLD) {
                                LogManager.d(TAG, "屏幕区域检测完成，切换到激光点检测模式")
                                
                                // 停止帧处理线程，因为激光点检测使用OpenGL方式
                                LogManager.d(TAG, "停止帧处理线程，切换到OpenGL激光点检测")
                                stopFrameProcessingThread()
                                
                                // 通知受控端模板检测成功，退出图片显示
                                if (ConnectActivity.isServerConnected) {
                                    LogManager.d(TAG, "通知受控端模板检测成功")
                                    val networkClient = ConnectActivity.getNetworkClient()
                                    networkClient.noticeTemplateDetected()
                                }

                                // 及时改状态，不然会出错
                                canRunGpuProcess = false

                                // 切换到激光点检测阶段
                                detectionState.value = DetectionState.LASER_DETECTION
                                
                                // 【新增】切换日志状态到激光检测
                                switchLogState(DetectionState.LASER_DETECTION)
                                
                                // 设置摄像头为激光检测模式（手动曝光）
                                camera2Helper.setLaserDetectMode(true)
                                LogManager.d(TAG, "设置摄像头为激光检测模式（手动曝光）")
                                
                                // 在主线程上重启摄像头
                                runOnUiThread {
                                    LogManager.d(TAG, "准备重启摄像头以应用新设置...")
                                    restartCamera()
                                }
                            }
                        }
                    } else {
                        // 检测失败，重置计数器和边框
                        synchronized(this) {
                            screenDetectionCounter = 0
                            // 清除边框显示
                            val cameraPreview = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
                            cameraPreview?.setScreenBorder(null)
                        }
                    }
                    
                    return detectionResult.success
                }
                
                DetectionState.LASER_DETECTION -> {
                    // 根据配置选择检测方式
                    if (useOpenGLDetection) {
                        // 使用OpenGL方式检测，在 setFrameCallback 中处理，这里跳过
                        return false
                    } else {
                        // 使用Surface方式检测，在这里处理
                        try {
                            val imageMat = getMatFromSurface()
                            
                            if (imageMat.empty()) {
                                LogManager.w(TAG, "获取图像失败，跳过此帧")
                                return false
                            }

                            val detectStartTime = System.currentTimeMillis()
                            val paramResult = laserPointDetector.detectLaserPoint(imageMat, startTime)
                            val detectTime = System.currentTimeMillis() - detectStartTime
                            
                            if (detectTime > 50) { // 只记录耗时较长的检测
                                LogManager.d(TAG, "Surface激光点检测耗时: ${detectTime}ms")
                            }
                            
                            // 释放Mat资源
                            imageMat.release()
                            
                            return paramResult
                        } catch (e: Exception) {
                            LogManager.e(TAG, "Surface激光点检测异常: ${e.message}")
                            e.printStackTrace()
                            return false
                        }
                    }
                }
                
                else -> {
                    // 空闲状态，不进行任何处理
                    return false
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "帧回调处理异常: ${e.message}")
            e.printStackTrace()
            return false
        } finally {
            val processingTime = System.currentTimeMillis() - startTime
            if (processingTime > 100) { // 只记录处理时间较长的帧
                LogManager.d(TAG, "帧处理总耗时: ${processingTime}ms")
            }
            
            // 更新性能统计
            updatePerformanceStats(processingTime)
        }
    }
    
    /**
     * 更新性能统计信息
     */
    private fun updatePerformanceStats(processingTime: Long) {
        totalFramesProcessed++
        totalProcessingTime += processingTime
        
        if (processingTime > maxProcessingTime) {
            maxProcessingTime = processingTime
        }
        
        if (processingTime < minProcessingTime) {
            minProcessingTime = processingTime
        }
        
        val currentTime = System.currentTimeMillis()
        if (lastPerformanceLogTime == 0L) {
            lastPerformanceLogTime = currentTime
        }
        
        // 每隔一定时间输出性能统计
        if (currentTime - lastPerformanceLogTime >= PERFORMANCE_LOG_INTERVAL) {
            val avgProcessingTime = if (totalFramesProcessed > 0) totalProcessingTime / totalFramesProcessed else 0
            val actualFps = if (totalFramesProcessed > 0) totalFramesProcessed * 1000.0 / (currentTime - lastPerformanceLogTime) else 0.0
            
            LogManager.i(TAG, "=== 性能统计 (过去${PERFORMANCE_LOG_INTERVAL/1000}秒) ===")
            LogManager.i(TAG, "处理帧数: $totalFramesProcessed")
            LogManager.i(TAG, "实际帧率: ${String.format("%.1f", actualFps)} fps")
            LogManager.i(TAG, "平均处理时间: ${avgProcessingTime}ms")
            LogManager.i(TAG, "最大处理时间: ${maxProcessingTime}ms")
            LogManager.i(TAG, "最小处理时间: ${if (minProcessingTime == Long.MAX_VALUE) 0 else minProcessingTime}ms")
            LogManager.i(TAG, "当前状态: ${detectionState.value}")
            LogManager.i(TAG, "===============================")
            
            // 重置统计
            totalFramesProcessed = 0
            totalProcessingTime = 0
            lastPerformanceLogTime = currentTime
            maxProcessingTime = 0
            minProcessingTime = Long.MAX_VALUE
        }
    }
    
    /**
     * 从CaptureResult获取图像数据
     * 使用Surface的方式获取图像，而不是ImageReader
     */
    data class YuvImageData(
        val width: Int,
        val height: Int,
        val yuvBytes: ByteArray,
        val format: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as YuvImageData

            if (width != other.width) return false
            if (height != other.height) return false
            if (format != other.format) return false
            if (!yuvBytes.contentEquals(other.yuvBytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + format
            result = 31 * result + yuvBytes.contentHashCode()
            return result
        }
    }

    private var surfaceBitmap: Bitmap? = null
    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0
    
    fun initBitmap(width: Int, height: Int) {
        if (surfaceBitmap == null || lastSurfaceWidth != width || lastSurfaceHeight != height) {
            surfaceBitmap?.recycle() // 回收旧的 Bitmap
            surfaceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            lastSurfaceWidth = width
            lastSurfaceHeight = height
            LogManager.d(TAG, "创建新的Surface Bitmap: ${width}x${height}")
        }
    }

    private fun getImageFromSurface(): Mat? {
        try {
            val startTime = System.currentTimeMillis()
            // 获取当前活跃的SurfaceView
            val surfaceView = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
            
            if (surfaceView == null || !surfaceView.holder.surface.isValid) {
                LogManager.e(TAG, "无法获取有效的Surface")
                return null
            }
            
            // 获取Surface和尺寸
            val surface = surfaceView.holder.surface
            // 使用摄像头的实际分辨率，而不是SurfaceView的显示尺寸
            // SurfaceView的width/height是显示尺寸，Surface内部缓冲区是摄像头分辨率
            val width = getCameraWidth()  // 1920
            val height = getCameraHeight() // 1080

            // 获取设备旋转方向
            val rotation = windowManager.defaultDisplay.rotation
            val degrees = when (rotation) {
                android.view.Surface.ROTATION_0 -> 0
                android.view.Surface.ROTATION_90 -> 270
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 90
                else -> 0
            }

            // 优化：预先创建合适尺寸的Bitmap，减少重复创建
            initBitmap(width, height)
            val bitmap = surfaceBitmap ?: return null

            // 使用PixelCopy API从Surface复制像素数据到Bitmap
            val latch = java.util.concurrent.CountDownLatch(1)
            var copyResult = false

            android.view.PixelCopy.request(surface, bitmap, { result ->
                copyResult = (result == android.view.PixelCopy.SUCCESS)
                latch.countDown()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            
            // 等待复制完成，最多等待100ms（减少等待时间）
            if (!latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                LogManager.e(TAG, "PixelCopy超时")
                return null
            }
            
            if (!copyResult) {
                LogManager.e(TAG, "PixelCopy失败")
                return null
            }

            // 优化：直接在OpenCV中处理旋转，避免Bitmap旋转操作
            val rgbMat = Mat().apply {
                Utils.bitmapToMat(bitmap, this)
            }

            val rotatedMat = rgbMat
            /*
            // 在OpenCV中处理旋转，比Bitmap旋转更高效
            val rotatedMat = when (degrees) {
                90 -> {
                    val rotated = Mat()
                    Core.rotate(rgbMat, rotated, Core.ROTATE_90_CLOCKWISE)
                    rgbMat.release()
                    rotated
                }
                180 -> {
                    val rotated = Mat()
                    Core.rotate(rgbMat, rotated, Core.ROTATE_180)
                    rgbMat.release()
                    rotated
                }
                270 -> {
                    val rotated = Mat()
                    Core.rotate(rgbMat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                    rgbMat.release()
                    rotated
                }
                else -> rgbMat // 无需旋转
            }

             */

            val totalTime = System.currentTimeMillis() - startTime
            if (totalTime > 50) { // 只记录处理时间较长的帧
                LogManager.d(TAG, "getImageFromSurface总耗时: ${totalTime}ms")
            }

            return rotatedMat

        } catch (e: Exception) {
            LogManager.e(TAG, "从Surface获取图像数据失败: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * 实现LaserPointDetector.LaserPointListener接口
     * 当检测到激光点时回调
     */
    override fun onLaserPointDetected(x: Int, y: Int, brightness: Int, timestamp: Long) {
        // 更新UI显示
        lastDetectedPointX.value = x
        lastDetectedPointY.value = y
        
        // 通过事件总线发布激光点检测事件
        EventBus.publish(
            EVENT_LASER_POINT_DETECTED,
            "x" to x,
            "y" to y,
            "brightness" to brightness,
            "timestamp" to timestamp
        )
        
        // 如果已连接到受控端，发送光标移动通知
        if (ConnectActivity.isServerConnected) {
            val networkClient = ConnectActivity.getNetworkClient()
            networkClient.noticePointMove("1", "$x,$y")
        }
        
        // 输出调试信息
        LogManager.d(TAG, "发布激光点事件: ($x, $y), 亮度: $brightness")
    }

    /**
     * 重置检测状态
     * 将应用状态重置为空闲状态，关闭并重新打开摄像头
     */
    private fun resetToIdleState() {
        LogManager.d(TAG, "=== resetToIdleState() 开始 ===")
        LogManager.d(TAG, "重置到空闲状态，当前状态: ${detectionState.value}")
        LogManager.d(TAG, "当前标记 - hasActiveOperation: $hasActiveOperation, isTestMode: $isTestMode")
        
        // 清除所有操作标记
        hasActiveOperation = false
        isTestMode = false
        userWantsToExit = false
        LogManager.d(TAG, "已清除所有操作标记")
        
        // 停止帧处理线程
        LogManager.d(TAG, "重置状态时停止帧处理线程")
        stopFrameProcessingThread()
        
        // 重置状态为空闲
        val oldState = detectionState.value
        detectionState.value = DetectionState.IDLE
        LogManager.d(TAG, "状态已重置: $oldState -> ${detectionState.value}")
        
        // 【新增】切换日志状态到默认状态
        switchLogState(DetectionState.IDLE)
        
        // 设置摄像头为普通模式（自动曝光）
        camera2Helper.setLaserDetectMode(false)
        LogManager.d(TAG, "设置摄像头为普通模式（自动曝光）")
        
        // 重置检测计数器
        screenDetectionCounter = 0
        LogManager.d(TAG, "重置检测计数器为0")

        LogManager.d(TAG, "准备重启摄像头...")
        restartCamera()
        
        LogManager.d(TAG, "=== resetToIdleState() 结束，最终状态: ${detectionState.value} ===")
    }

    /**
     * 处理返回按键逻辑
     * 适用于新旧版本Android
     */
    private fun handleBackPress() {
        LogManager.d(TAG, "返回键按下，当前状态: ${detectionState.value}, 活跃操作: $hasActiveOperation, 测试模式: $isTestMode")
        
        // 优先级1：如果有活跃的操作（检测或测试），先停止这些操作
        if (hasActiveOperation || isTestMode || detectionState.value != DetectionState.IDLE) {
            LogManager.d(TAG, "检测到活跃操作，重置到空闲状态")
            resetToIdleState()
            return
        }
        
        // 优先级2：如果已经在空闲状态且没有活跃操作，第一次按返回键给出提示
        if (!userWantsToExit) {
            LogManager.d(TAG, "第一次按返回键，提示用户确认退出")
            userWantsToExit = true
            Toast.makeText(this, getString(R.string.press_back_again_to_exit), Toast.LENGTH_SHORT).show()
            
            // 3秒后重置退出标记
            Handler(mainLooper).postDelayed({
                userWantsToExit = false
                LogManager.d(TAG, "退出确认标记已重置")
            }, 3000)
            return
        }
        
        // 优先级3：用户确认要退出APP
        LogManager.d(TAG, "用户确认退出应用")
        finish()
    }


    
    private val CONNECT_ACTIVITY_REQUEST_CODE = 1001
    private val SETTINGS_ACTIVITY_REQUEST_CODE = 1002
    private val SYSTEM_ACTIVITY_REQUEST_CODE = 1003
    
    /**
     * 打开连接界面
     */
    private fun openConnectActivity() {
        // 检查当前是否已连接到受控端
        if (ConnectActivity.isServerConnected) {
            // 已连接状态，弹出确认断开连接的对话框
            val builder = AlertDialog.Builder(this)
            builder.setTitle(getString(R.string.disconnect_title))
            builder.setMessage(getString(R.string.disconnect_message))
            builder.setPositiveButton(getString(R.string.confirm)) { _, _ ->
                // 断开连接
                disconnectFromServer()
            }
            builder.setNegativeButton(getString(R.string.cancel), null)
            builder.show()
            return
        }
        
        // 点击连接按钮时，确保重置相关标记
        isTestMode = false
        hasActiveOperation = false
        userWantsToExit = false
        
        val intent = Intent(this, ConnectActivity::class.java)
        startActivityForResult(intent, CONNECT_ACTIVITY_REQUEST_CODE)
    }
    
    /**
     * 断开与受控端的连接
     */
    private fun disconnectFromServer() {
        LogManager.d(TAG, "用户主动断开受控端连接")
        
        try {
            // 获取网络客户端并断开连接
            val networkClient = ConnectActivity.getNetworkClient()
            networkClient.disconnect()
            
            // 重置连接状态
            ConnectActivity.isServerConnected = false
            ConnectActivity.connectedServer = null
            
            // 重置到空闲状态
            resetToIdleState()
            
            // 显示提示消息
            Toast.makeText(this, getString(R.string.disconnected_from_server), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogManager.e(TAG, "断开连接时发生错误: ${e.message}")
            Toast.makeText(this, getString(R.string.disconnect_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 处理连接界面返回结果
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == CONNECT_ACTIVITY_REQUEST_CODE) {
            LogManager.d(TAG, "连接界面返回，resultCode: $resultCode")
            
            // 设置标记，表示刚从ConnectActivity返回
            justReturnedFromConnect = true
            
            // 连接界面返回时，重置相关标记
            userWantsToExit = false
            
            // 【修复】由于连接状态可能在短时间内快速变化，使用延迟检查确保状态稳定
            Handler(mainLooper).postDelayed({
                checkConnectionStatusAfterReturn()
            }, 500) // 延迟500ms让连接状态稳定
        } else if (requestCode == SETTINGS_ACTIVITY_REQUEST_CODE) {
            LogManager.d(TAG, "设置界面返回，resultCode: $resultCode")
            
            // 重置相关标记
            userWantsToExit = false
            
            if (resultCode == RESULT_OK) {
                // 设置已更改，需要应用新设置
                LogManager.d(TAG, "设置已更改，开始应用新设置")
                
                // 【移除】语言设置检查，现在完全由Application统一管理
                LogManager.d(TAG, "语言设置由Application统一管理，MainActivity不再检查语言变化")
                
                // 显示提示消息
                Toast.makeText(this, getString(R.string.camera_restart_required), Toast.LENGTH_SHORT).show()
                
                // 如果当前正在运行摄像头，重启以应用新设置
                if (camera2Helper.isRunning()) {
                    LogManager.d(TAG, "摄像头正在运行，重启以应用新设置")
                    
                    // 延迟一点时间确保UI更新完成
                    Handler(mainLooper).postDelayed({
                        restartCamera()
                    }, 500)
                } else {
                    LogManager.d(TAG, "摄像头未运行，仅更新设置")
                    // 即使摄像头未运行，也应用设置以便下次启动时使用
                    applyCurrentSettings()
                }
            } else {
                LogManager.d(TAG, "设置界面取消或无更改")
            }
        } else if (requestCode == SYSTEM_ACTIVITY_REQUEST_CODE) {
            LogManager.d(TAG, "系统设置界面返回，resultCode: $resultCode")
            
            // 重置相关标记
            userWantsToExit = false
            
            // 系统设置界面返回时，检查用户级别是否有变化
            try {
                val currentSettings = settingsManager.getSettings()
                LogManager.d(TAG, "系统设置返回后，当前用户级别: ${currentSettings.userLevel}")
                
                // 可以在这里根据用户级别变化做一些处理
                // 比如刷新UI显示等
                
            } catch (e: Exception) {
                LogManager.e(TAG, "检查用户级别变化失败: ${e.message}")
            }
        }
    }
    
    /**
     * 【新增】检查从连接界面返回后的连接状态
     */
    private fun checkConnectionStatusAfterReturn() {
        // 检查实际的连接状态，而不依赖resultCode
        val actualConnectionStatus = ConnectActivity.isServerConnected
        val networkClient = ConnectActivity.getNetworkClient()
        val networkStatus = networkClient.getConnectionStatus()
        
        LogManager.d(TAG, "延迟检查连接状态 - isServerConnected: $actualConnectionStatus, networkStatus: $networkStatus")
        
        // 【修复】重启摄像头以恢复预览
        Handler(mainLooper).postDelayed({
            LogManager.d(TAG, "从连接界面返回，重启摄像头以恢复预览")
            restartCamera()
            
            // 重置标记
            justReturnedFromConnect = false
            
            // 检查连接状态并进行相应处理
            if (actualConnectionStatus && networkStatus == NetworkClient.STATUS_CONNECTED) {
                LogManager.d(TAG, "检测到受控端已连接，开始检测流程")
                
                // 连接成功，获取当前连接的受控端
                val server = ConnectActivity.connectedServer
                if (server != null) {
                    // 使用受控端屏幕尺寸初始化检测器
                    val serverScreenWidth = server.screenSize[0]
                    val serverScreenHeight = server.screenSize[1]
                    LogManager.d(TAG, "使用受控端屏幕尺寸初始化检测器: ${serverScreenWidth}x${serverScreenHeight}")
                    
                    // 更新屏幕检测器的尺寸信息
                    initLaserPointDetector(serverScreenWidth, serverScreenHeight)
                    
                    // 开始检测
                    LogManager.d(TAG, "调用startDetection()开始检测")
                    startDetection()
                    
                    // 显示提示消息
                    Toast.makeText(this, getString(R.string.connected_to_server_toast, server.name), Toast.LENGTH_SHORT).show()
                } else {
                    LogManager.w(TAG, "受控端连接状态为true但connectedServer为null")
                }
            } else {
                LogManager.d(TAG, "受控端未连接或状态不稳定 - isServerConnected: $actualConnectionStatus, networkStatus: $networkStatus")
                // 只有在确实没有连接的情况下才重置到空闲状态
                if (detectionState.value != DetectionState.IDLE) {
                    LogManager.d(TAG, "重置到空闲状态")
                    resetToIdleState()
                }
            }
        }, 500) // 延迟500ms让系统稳定
    }

    private fun getMatFromSurface(): Mat {
        val detectStartTime = System.currentTimeMillis()
        val rgbData = getImageFromSurface()
        if (rgbData != null) return rgbData
        return Mat()
    }
    

    /**
     * 配置所有组件使用统一的分辨率
     */
    private fun configureUniformResolution() {
        LogManager.d(TAG, "配置统一分辨率: ${getScreenWidth()}x${getScreenHeight()}")
        
        // 这里可以添加额外的分辨率配置逻辑
        // 比如根据设备性能动态调整分辨率等
    }

    /**
     * 启动温度监控
     */
    private fun startTemperatureMonitoring() {
        LogManager.d(TAG, "启动温度监控")
        
        // 创建温度更新处理器
        temperatureUpdateHandler = Handler(mainLooper)
        
        // 注册电池状态监听器
        temperatureReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    // 获取电池温度 (单位: 0.1°C)
                    val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                    batteryTemperature.value = temperature
                    LogManager.d(TAG, "电池温度更新: ${temperature}°C")
                }
            }
        }
        
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(temperatureReceiver, filter)
        
        // 启动定期温度检查
        startPeriodicTemperatureCheck()
    }
    
    /**
     * 停止温度监控
     */
    private fun stopTemperatureMonitoring() {
        LogManager.d(TAG, "停止温度监控")
        
        // 停止定期检查
        temperatureUpdateHandler?.removeCallbacksAndMessages(null)
        temperatureUpdateHandler = null
        
        // 注销广播接收器
        temperatureReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                LogManager.w(TAG, "注销温度监听器失败: ${e.message}")
            }
        }
        temperatureReceiver = null
    }
    
    /**
     * 启动定期温度检查
     */
    private fun startPeriodicTemperatureCheck() {
        temperatureUpdateHandler?.post(object : Runnable {
            override fun run() {
                try {
                    // 更新CPU温度（API 29+）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        updateCpuTemperature()
                    }
                    
                    // 检查温度是否过高
                    checkTemperatureWarning()
                    
                    // 每5秒检查一次
                    temperatureUpdateHandler?.postDelayed(this, 5000)
                } catch (e: Exception) {
                    LogManager.e(TAG, "温度检查异常: ${e.message}")
                    // 发生异常时延长检查间隔
                    temperatureUpdateHandler?.postDelayed(this, 10000)
                }
            }
        })
    }
    
    /**
     * 更新CPU温度（需要API 29+）
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun updateCpuTemperature() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val thermalStatus = powerManager.currentThermalStatus

            
            // 根据热状态估算温度
            val estimatedTemp = when (thermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> 35f
                PowerManager.THERMAL_STATUS_LIGHT -> 45f
                PowerManager.THERMAL_STATUS_MODERATE -> 55f
                PowerManager.THERMAL_STATUS_SEVERE -> 70f
                PowerManager.THERMAL_STATUS_CRITICAL -> 85f
                PowerManager.THERMAL_STATUS_EMERGENCY -> 95f
                PowerManager.THERMAL_STATUS_SHUTDOWN -> 100f
                else -> 40f
            }
            
            cpuTemperature.value = estimatedTemp
            LogManager.d(TAG, "CPU热状态: $thermalStatus, 估算温度: ${estimatedTemp}°C")
        } catch (e: Exception) {
            LogManager.w(TAG, "获取CPU温度失败: ${e.message}")
            // 如果获取失败，设置一个默认值
            cpuTemperature.value = 0f
        }
    }
    
    /**
     * 检查温度警告
     */
    private fun checkTemperatureWarning() {
        val batteryTemp = batteryTemperature.value
        val cpuTemp = cpuTemperature.value
        
        // 检查是否需要警告用户
        if (batteryTemp > 45f || cpuTemp > 60f) {
            val message = "设备温度较高 - 电池: ${batteryTemp}°C, CPU: ${cpuTemp}°C"
            LogManager.w(TAG, message)
            
            // 如果温度过高，可以考虑降低性能
            if (batteryTemp > 50f || cpuTemp > 70f) {
                LogManager.e(TAG, "设备温度过高，建议降低性能或停止应用")
                // 这里可以自动调整性能设置
                // sugggestPerformanceReduction()
            }
        }
    }

    /**
     * 处理配置变化（如屏幕旋转）
     */
    override fun attachBaseContext(newBase: Context?) {
        // 【修复】确保MainActivity始终使用正确的语言设置
        LogManager.d(TAG, "MainActivity attachBaseContext被调用")
        
        val contextWithCorrectLanguage = newBase?.let { context ->
            try {
                // 使用Application的全局语言管理方法
                LaserCtrlMouseApplication.applyLanguageForContext(context)
            } catch (e: Exception) {
                LogManager.e(TAG, "MainActivity attachBaseContext应用语言设置失败: ${e.message}")
                context // 如果失败，使用原始context
            }
        }
        
        super.attachBaseContext(contextWithCorrectLanguage ?: newBase)
        
        LogManager.d(TAG, "MainActivity attachBaseContext完成")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        LogManager.d(TAG, "配置变化检测到")
        LogManager.d(TAG, "新配置方向信息: ${getCurrentOrientationInfo()}")
        
        // 【修复】在配置变化时重新应用语言设置
        try {
            LogManager.d(TAG, "配置变化时重新应用语言设置")
            LaserCtrlMouseApplication.updateActivityLanguage(this)
            LogManager.d(TAG, "配置变化后语言设置已重新应用")
        } catch (e: Exception) {
            LogManager.e(TAG, "配置变化时重新应用语言设置失败: ${e.message}")
        }
        
        // 如果正在运行摄像头，需要重新配置
        if (camera2Helper.isRunning()) {
            LogManager.d(TAG, "检测到方向变化，重新配置摄像头和界面")
            
            // 延迟一点时间确保系统完成方向变化
            Handler(mainLooper).postDelayed({
                try {
                    // 更新全局context
                    setGlobalContext(this)
                    
                    // 重新启动摄像头以适应新的方向
                    restartCamera()
                    
                    LogManager.d(TAG, "摄像头重新配置完成，新的分辨率: ${getCameraResolution()}")
                } catch (e: Exception) {
                    LogManager.e(TAG, "处理方向变化失败: ${e.message}")
                    e.printStackTrace()
                }
            }, 300) // 延迟300ms让系统稳定
        } else {
            LogManager.d(TAG, "摄像头未运行，仅更新context")
            setGlobalContext(this)
        }
    }

    // 当前是否因暂停而关闭了摄像头
    private var cameraWasRunningBeforePause = false
    
    // 保存暂停前的检测状态
    private var detectionStateBeforePause = DetectionState.IDLE
    
    // 添加标记：是否刚从ConnectActivity返回，避免状态被错误恢复
    private var justReturnedFromConnect = false
    
    // 添加GLSurfaceView重新创建控制状态
    val glSurfaceViewKey = mutableStateOf(0)
    val isRecreatingGLSurfaceView = mutableStateOf(false)
    
    // 防止语言设置导致的重复重启
    private var isLanguageRestarting = false


    
    /**
     * 打开设置界面
     */
    private fun openSettingsActivity() {
        LogManager.d(TAG, "打开设置界面")
        
        // 点击设置按钮时，确保重置相关标记
        isTestMode = false
        hasActiveOperation = false
        userWantsToExit = false
        
        val intent = Intent(this, SettingsActivity::class.java)
        startActivityForResult(intent, SETTINGS_ACTIVITY_REQUEST_CODE)
    }

    /**
     * 打开帮助页面
     */
    private fun openHelpPage() {
        LogManager.d(TAG, "打开帮助页面")
        
        try {
            // 创建Intent打开浏览器
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://laser.scieford.com/tutorial"))
            
            // 检查是否有可用的浏览器应用
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                LogManager.d(TAG, "已启动浏览器打开帮助页面")
            } else {
                // 没有可用的浏览器，显示提示消息
                Toast.makeText(this, getString(R.string.no_browser_available), Toast.LENGTH_SHORT).show()
                LogManager.w(TAG, "没有找到可用的浏览器应用")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "打开帮助页面失败: ${e.message}")
            Toast.makeText(this, getString(R.string.open_help_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 【已简化】语言状态检查现在只记录信息，不进行任何干预
     */
    private fun logCurrentLanguageStatus() {
        try {
            LogManager.d(TAG, "=== 简化版语言状态检查 ===")
            
            // 获取当前系统语言
            val currentLanguage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                resources.configuration.locales[0].language
            } else {
                @Suppress("DEPRECATION")
                resources.configuration.locale.language
            }
            
            // 获取保存的语言设置
            val savedSettings = settingsManager.getSettings()
            
            LogManager.d(TAG, "当前系统语言: $currentLanguage")
            LogManager.d(TAG, "保存的语言设置: ${savedSettings.language}")
            LogManager.d(TAG, "语言设置由Application统一管理，MainActivity不干预")
            
            LogManager.d(TAG, "=== 语言状态检查完成 ===")
        } catch (e: Exception) {
            LogManager.e(TAG, "语言状态检查失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 【DEBUG】诊断激光点检测系统状态
     */
    fun diagnoseDetectionSystem() {
        LogManager.i(TAG, "=== 激光点检测系统诊断 ===")
        
        try {
            // 1. 检查检测状态
            LogManager.i(TAG, "1. 当前检测状态: ${detectionState.value}")
            
            // 2. 检查GPU处理标志
            LogManager.i(TAG, "2. canRunGpuProcess: $canRunGpuProcess")
            
            // 3. 检查网络连接状态
            LogManager.i(TAG, "3. 受控端连接状态: ${ConnectActivity.isServerConnected}")
            LogManager.i(TAG, "3. 连接的受控端: ${ConnectActivity.connectedServer?.name ?: "无"}")
            
            // 4. 检查激光点检测器
            val detectorInitialized = laserPointDetector.isInitialized()
            LogManager.i(TAG, "4. LaserPointDetector已初始化: $detectorInitialized")
            
            // 5. 详细检查GPU图像处理器状态
            val imageProcessor = cameraImageProcessor
            LogManager.i(TAG, "5. CameraImageProcessor状态: ${if (imageProcessor != null) "已创建" else "null"}")
            if (imageProcessor != null) {
                LogManager.i(TAG, "5. GPU图像处理器hashCode: ${imageProcessor.hashCode()}")
                LogManager.i(TAG, "5. GPU图像处理器实例: ${imageProcessor::class.java.simpleName}")
            } else {
                LogManager.w(TAG, "5. ❌ GPU图像处理器为null - 这是问题所在!")
                
                // 分析可能的原因
                LogManager.w(TAG, "5. 可能原因分析:")
                LogManager.w(TAG, "   - GLSurfaceView重建状态: ${isRecreatingGLSurfaceView.value}")
                LogManager.w(TAG, "   - GLSurfaceView key: ${glSurfaceViewKey.value}")
                LogManager.w(TAG, "   - 最近是否发生重建: 检查时间戳...")
                
                // 尝试获取GLSurfaceView状态
                val glSurfaceView = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
                LogManager.w(TAG, "   - GLCameraSurfaceView存在: ${glSurfaceView != null}")
                if (glSurfaceView != null) {
                    LogManager.w(TAG, "   - GLCameraSurfaceView hashCode: ${glSurfaceView.hashCode()}")
                }
            }
            
            // 6. 检查摄像头状态
            val cameraRunning = camera2Helper.isRunning()
            LogManager.i(TAG, "6. 摄像头运行状态: $cameraRunning")
            
            // 7. 检查屏幕尺寸配置
            val screenSize = MainActivity.getRealScreenSize()
            LogManager.i(TAG, "7. 服务端屏幕尺寸: ${screenSize.first}x${screenSize.second}")
            
            // 8. 检查摄像头分辨率
            val cameraRes = MainActivity.getCameraResolution()
            LogManager.i(TAG, "8. 摄像头分辨率: ${cameraRes.first}x${cameraRes.second}")
            
            // 9. 检查设置参数
            val settings = settingsManager.getSettings()
            LogManager.i(TAG, "9. 检测参数 - threshold: ${settings.threshold}, minBrightness: ${settings.minBrightness}")
            LogManager.i(TAG, "9. 曝光时间: ${settings.exposureTimeMs}ms")
            LogManager.i(TAG, "9. 帧率模式: ${settings.frameRateMode}")
            
            // 10. 检查GLSurfaceView状态
            val glSurfaceView = findViewById<GLCameraSurfaceView>(R.id.camera_preview)
            LogManager.i(TAG, "10. GLCameraSurfaceView状态: ${if (glSurfaceView != null) "已创建" else "null"}")
            LogManager.i(TAG, "10. GLSurfaceView重建标志: ${isRecreatingGLSurfaceView.value}")
            LogManager.i(TAG, "10. GLSurfaceView key值: ${glSurfaceViewKey.value}")
            
            // 11. 内存和OpenGL状态
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            LogManager.i(TAG, "11. 内存使用: ${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB")
            
            // 12. 尝试手动重新创建GPU图像处理器（如果为null且在激光检测模式）
            if (imageProcessor == null && detectionState.value == DetectionState.LASER_DETECTION) {
                LogManager.w(TAG, "12. 尝试手动重新创建GPU图像处理器...")
                if (glSurfaceView != null && !isRecreatingGLSurfaceView.value) {
                    glSurfaceView.queueEvent {
                        try {
                            LogManager.i(TAG, "12. 在OpenGL线程中手动重新创建GPU图像处理器")
                            _cameraImageProcessor = CameraImageProcessor(this@MainActivity)
                            val newProcessor = _cameraImageProcessor
                            if (newProcessor != null) {
                                glSurfaceView.setImageProcessor(newProcessor)
                                newProcessor.initialize(getCameraWidth(), getCameraHeight())
                                newProcessor.initializeGPUDetector()
                                LogManager.i(TAG, "12. ✅ 手动重新创建GPU图像处理器成功: ${newProcessor.hashCode()}")
                                
                                // 在主线程显示成功消息
                                runOnUiThread {
                                    android.widget.Toast.makeText(this@MainActivity, getString(R.string.gpu_image_processor_recreated), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                LogManager.e(TAG, "12. ❌ 手动重新创建GPU图像处理器失败，创建的对象为null")
                            }
                        } catch (e: Exception) {
                            LogManager.e(TAG, "12. ❌ 手动重新创建GPU图像处理器异常: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    LogManager.w(TAG, "12. 无法重新创建 - GLSurfaceView为null或正在重建中")
                }
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "诊断系统状态时出错: ${e.message}")
            e.printStackTrace()
        }
        
        LogManager.i(TAG, "=== 诊断完成 ===")
    }

    /**
     * 获取并输出设备信息
     */
    private fun logDeviceInfo() {
        try {
            LogManager.i(TAG, "=== 设备信息 ===")
            
            // 获取设备唯一ID (ANDROID_ID)
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            LogManager.i(TAG, "设备唯一ID (ANDROID_ID): $androidId")
            
            // 操作系统信息
            LogManager.i(TAG, "操作系统: Android")
            LogManager.i(TAG, "Android版本: ${Build.VERSION.RELEASE}")
            LogManager.i(TAG, "API级别: ${Build.VERSION.SDK_INT}")
            LogManager.i(TAG, "版本代号: ${Build.VERSION.CODENAME}")
            
            // 设备制造商和型号信息
            LogManager.i(TAG, "制造商: ${Build.MANUFACTURER}")
            LogManager.i(TAG, "品牌: ${Build.BRAND}")
            LogManager.i(TAG, "设备型号: ${Build.MODEL}")
            LogManager.i(TAG, "设备名称: ${Build.DEVICE}")
            LogManager.i(TAG, "产品名称: ${Build.PRODUCT}")
            
            // 硬件信息
            LogManager.i(TAG, "硬件平台: ${Build.HARDWARE}")
            LogManager.i(TAG, "CPU架构: ${Build.CPU_ABI}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                LogManager.i(TAG, "支持的CPU架构: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            }
            
            // 系统构建信息
            LogManager.i(TAG, "构建时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(Build.TIME))}")
            LogManager.i(TAG, "构建类型: ${Build.TYPE}")
            LogManager.i(TAG, "构建标签: ${Build.TAGS}")
            
            // 屏幕信息
            val displayMetrics = resources.displayMetrics
            LogManager.i(TAG, "屏幕分辨率: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
            LogManager.i(TAG, "屏幕密度: ${displayMetrics.density} (DPI: ${displayMetrics.densityDpi})")
            
            // 内存信息
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            
            LogManager.i(TAG, "应用最大内存: ${maxMemory / 1024 / 1024}MB")
            LogManager.i(TAG, "应用当前内存: ${usedMemory / 1024 / 1024}MB / ${totalMemory / 1024 / 1024}MB")
            
            LogManager.i(TAG, "==================")
            
        } catch (e: Exception) {
            LogManager.e(TAG, "获取设备信息失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 执行版本检查
     * 在设备信息输出完成后调用，检查是否有应用更新
     */
    private fun performVersionCheck() {
        try {
            LogManager.i(TAG, "=== 开始版本检查 ===")
            
            // 创建版本检查服务
            val versionCheckService = VersionCheckService(this)
            
            // 在协程中执行版本检查（异步执行，不阻塞UI）
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    LogManager.d(TAG, "正在检查版本更新...")
                    
                    // 执行版本检查
                    val versionResponse = versionCheckService.checkVersion()
                    
                    if (versionResponse != null) {
                        LogManager.i(TAG, "版本检查完成 - 最新版本: ${versionResponse.latestVersion}")
                        
                        // 检查是否有更新
                        if (versionResponse.hasUpdate) {
                            LogManager.i(TAG, "发现新版本: ${versionResponse.latestVersion}")
                            
                            // 显示更新对话框
                            UpdateDialogHelper.showUpdateDialog(
                                context = this@MainActivity,
                                currentVersion = getAppVersion(),
                                latestVersion = versionResponse.latestVersion,
                                downloadUrl = versionResponse.downloadUrl,
                                updateDescription = versionResponse.updateDescription,
                                forceUpdate = versionResponse.forceUpdate
                            )
                        } else {
                            LogManager.i(TAG, "当前版本已是最新版本")
                        }
                    } else {
                        LogManager.w(TAG, "版本检查失败或更新服务器无响应")
                    }
                    
                } catch (e: Exception) {
                    LogManager.e(TAG, "版本检查异常: ${e.message}", e)
                    // 版本检查失败不影响应用正常启动，只记录日志
                }
            }
            
            LogManager.d(TAG, "版本检查已启动（异步执行）")
            
        } catch (e: Exception) {
            LogManager.e(TAG, "启动版本检查失败: ${e.message}", e)
        }
    }
    
    /**
     * 获取当前应用版本号
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            LogManager.e(TAG, "获取应用版本号失败: ${e.message}")
            "unknown"
        }
    }

    /**
     * 打开系统设置界面
     */
    private fun openSystemActivity() {
        LogManager.d(TAG, "打开系统设置界面")
        
        // 点击系统设置按钮时，确保重置相关标记
        isTestMode = false
        hasActiveOperation = false
        userWantsToExit = false
        
        val intent = Intent(this, SystemActivity::class.java)
        startActivityForResult(intent, SYSTEM_ACTIVITY_REQUEST_CODE)
    }

}

@Composable
fun LaserDetectionScreen(
    camera2Helper: Camera2Helper,
    detectionState: androidx.compose.runtime.MutableState<MainActivity.DetectionState>,
    debugFunctionEnabled: androidx.compose.runtime.MutableState<Boolean>,
    lastDetectedPointX: androidx.compose.runtime.MutableState<Int> = mutableStateOf(0),
    lastDetectedPointY: androidx.compose.runtime.MutableState<Int> = mutableStateOf(0),
    onStartDetection: () -> Unit,
    onStartDebugTest: () -> Unit,
    onStartScreenDetectionTest: () -> Unit,
    onTestGpuResource: () -> Unit,
    onConnectServer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    isServerConnected: () -> Boolean,
    getConnectedServerName: () -> String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 实时帧率状态 - 定期从Camera2Helper读取
    var currentFps by remember { mutableStateOf(0.0) }
    
    // 定期更新帧率显示
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val realTimeFps = camera2Helper.getCurrentFps()
                if (realTimeFps != currentFps) {
                    currentFps = realTimeFps
                    LogManager.d("LaserDetectionScreen", "实时帧率更新: $realTimeFps fps")
                }
            } catch (e: Exception) {
                LogManager.e("LaserDetectionScreen", "获取实时帧率失败: ${e.message}")
            }
            kotlinx.coroutines.delay(500) // 每500ms更新一次帧率显示
        }
    }
    
    // 数字变焦相关状态
    var currentZoomRatio by remember { mutableStateOf(1.0f) }
    var maxZoomRatio by remember { mutableStateOf(1.0f) }
    
    // 当前摄像头信息状态
    var currentCameraInfo by remember { mutableStateOf("") }
    
    // 受控端连接状态
    var serverConnected by remember { mutableStateOf(false) }
    var connectedServerName by remember { mutableStateOf("") }
    
    // 定期更新受控端连接状态
    LaunchedEffect(Unit) {
        while (true) {
            serverConnected = isServerConnected()
            connectedServerName = getConnectedServerName()
            kotlinx.coroutines.delay(1000) // 每秒更新一次
        }
    }
    
    // 监听状态变化并记录日志
    val currentState by detectionState
    LaunchedEffect(currentState) {
        LogManager.d("LaserDetectionScreen", "UI状态变化: $currentState")
    }
    
    // 不再需要设置帧率回调，直接实时读取currentFps值
    // LaunchedEffect中移除帧率监听器设置
    
    // 设置变焦比例监听器
    LaunchedEffect(camera2Helper) {
        camera2Helper.setZoomRatioListener { zoomRatio ->
            currentZoomRatio = zoomRatio
        }
    }
    
    // 获取当前摄像头信息
    LaunchedEffect(camera2Helper) {
        // 定期更新摄像头信息，以确保在运行时切换摄像头时能够及时更新显示
        while (true) {
            try {
                // 获取当前摄像头ID
                val currentCameraId = camera2Helper.getCurrentCameraId()
                
                // 获取摄像头详细信息
                val cameraInfoList = camera2Helper.getCameraInfoList()
                val cameraInfo = cameraInfoList.find { it.id == currentCameraId }
                
                val newCameraInfo = if (cameraInfo != null) {
                    cameraInfo.getDisplayName(context)
                } else {
                    "ID${currentCameraId}: ${context.getString(R.string.camera_unknown)}"
                }
                
                // 只有在信息变化时才更新和打印日志
                if (newCameraInfo != currentCameraInfo) {
                    currentCameraInfo = newCameraInfo
                    LogManager.d("LaserDetectionScreen", "摄像头信息已更新: $currentCameraInfo")
                }
                
                // 每2秒检查一次摄像头信息变化
                kotlinx.coroutines.delay(2000)
            } catch (e: Exception) {
                LogManager.e("LaserDetectionScreen", "获取摄像头信息失败: ${e.message}")
                kotlinx.coroutines.delay(5000) // 出错时延长间隔
            }
        }
    }
    
    // 获取 MainActivity 的实例
    val mainActivity = LocalContext.current as MainActivity

    // 读取最近检测到的激光点坐标
    val pointX by lastDetectedPointX
    val pointY by lastDetectedPointY
    
    // 读取温度信息
    val batteryTemp by mainActivity.batteryTemperature
    val cpuTemp by mainActivity.cpuTemperature
    
    // 目标摄像头分辨率 - 使用统一配置
    val targetWidth = MainActivity.getScreenWidth()
    val targetHeight = MainActivity.getScreenHeight()
    
    // 监听生命周期事件
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when(event) {
                Lifecycle.Event.ON_RESUME -> {
                    LogManager.d("LaserDetectionScreen", "Compose界面恢复")
                    // 在Compose界面恢复时不需要特殊处理，MainActivity的onResume会处理摄像头重启
                }
                Lifecycle.Event.ON_PAUSE -> {
                    LogManager.d("LaserDetectionScreen", "Compose界面暂停")
                    // 在Compose界面暂停时不立即关闭摄像头，让MainActivity的onPause统一处理
                    // 这样避免了重复关闭摄像头的问题
                }
                Lifecycle.Event.ON_DESTROY -> {
                    LogManager.d("LaserDetectionScreen", "Compose界面销毁，关闭摄像头")
                    camera2Helper.closeCamera()
                }
                else -> {} // 忽略其他事件
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 在Dispose时也不需要关闭摄像头，让Activity统一管理
        }
    }
    
    // 使用Box布局，让摄像头预览填满整个屏幕，按钮浮动在右边
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 获取GLSurfaceView重新创建控制状态
        val glSurfaceViewKeyValue by mainActivity.glSurfaceViewKey
        val isRecreatingGLSurfaceValue by mainActivity.isRecreatingGLSurfaceView
        
        // 摄像头预览窗口 - 填满整个屏幕
        // 使用key()函数强制重新创建AndroidView
        // 每次glSurfaceViewKeyValue变化时，会重新创建AndroidView
        key(glSurfaceViewKeyValue) {
            AndroidView(
                factory = { ctx ->
                    LogManager.d("LaserDetectionScreen", "创建新的GLCameraSurfaceView，key: $glSurfaceViewKeyValue")
                    GLCameraSurfaceView(ctx).apply {
                        // 设置目标分辨率
                        setTargetResolution(targetWidth, targetHeight)
                        
                        // 设置ID以便后续查找
                        id = R.id.camera_preview
                        
                        // 设置相机帮助类
                        setCamera2Helper(camera2Helper)
                        
                        // 【修复】重新创建GPU图像处理器 - 增加更详细的日志和错误处理
                        try {
                            LogManager.d("LaserDetectionScreen", "开始创建GPU图像处理器，key: $glSurfaceViewKeyValue")
                            mainActivity._cameraImageProcessor = CameraImageProcessor(ctx)
                            LogManager.d("LaserDetectionScreen", "GPU图像处理器创建成功: ${mainActivity._cameraImageProcessor?.hashCode()}")
                            
                            // 设置图像处理器
                            val imageProcessor = mainActivity.cameraImageProcessor
                            if (imageProcessor != null) {
                                setImageProcessor(imageProcessor)
                                LogManager.d("LaserDetectionScreen", "图像处理器设置成功")
                            } else {
                                LogManager.e("LaserDetectionScreen", "创建的图像处理器为null")
                            }
                        } catch (e: Exception) {
                            LogManager.e("LaserDetectionScreen", "创建GPU图像处理器失败: ${e.message}")
                            e.printStackTrace()
                            // 即使创建失败，也继续摄像头预览
                        }
                        
                        // 设置帧回调，使用 GPU 处理器检测激光点
                        setFrameCallback { textureId ->
                            // 【调试】详细记录每个检测步骤的状态
                            try {
                                // 1. 检查GPU处理标志
                                if (mainActivity.canRunGpuProcess == false) {
                                    // 只偶尔记录这个信息，避免日志过多
                                    if (System.currentTimeMillis() % 5000 < 50) { // 每5秒记录一次
                                        LogManager.d("LaserDetectionGPU", "canRunGpuProcess=false，跳过GPU检测")
                                    }
                                    return@setFrameCallback
                                }

                                // 2. 检查检测状态
                                val currentDetectionState = detectionState.value
                                if (currentDetectionState != MainActivity.DetectionState.LASER_DETECTION) {
                                    // 只在状态刚改变时记录
                                    return@setFrameCallback
                                }

                                // 3. 检查激光点检测器初始化状态
                                val isDetectorInitialized = mainActivity.laserPointDetector.isInitialized()
                                if (!isDetectorInitialized) {
                                    LogManager.w("LaserDetectionGPU", "激光点检测器未初始化，跳过GPU检测")
                                    return@setFrameCallback
                                }

                                // 4. 检查GPU图像处理器状态 - 增加更详细的检查
                                val currentImageProcessor = mainActivity.cameraImageProcessor
                                if (currentImageProcessor == null) {
                                    // 每5秒记录一次，避免日志过多
                                    if (System.currentTimeMillis() % 5000 < 50) {
                                        LogManager.w("LaserDetectionGPU", "GPU图像处理器为null，跳过检测 (GLSurfaceView key: $glSurfaceViewKeyValue)")
                                        LogManager.w("LaserDetectionGPU", "可能原因: 1) 重建过程中 2) 初始化失败 3) 被意外释放")
                                        
                                        // 【修复】尝试重新创建GPU图像处理器
                                        try {
                                            LogManager.d("LaserDetectionGPU", "尝试在帧回调中重新创建GPU图像处理器")
                                            mainActivity._cameraImageProcessor = CameraImageProcessor(ctx)
                                            val newProcessor = mainActivity.cameraImageProcessor
                                            if (newProcessor != null) {
                                                setImageProcessor(newProcessor)
                                                // 在OpenGL线程中初始化
                                                newProcessor.initialize(MainActivity.getCameraWidth(), MainActivity.getCameraHeight())
                                                newProcessor.initializeGPUDetector()
                                                LogManager.d("LaserDetectionGPU", "GPU图像处理器重新创建成功")
                                            }
                                        } catch (e: Exception) {
                                            LogManager.e("LaserDetectionGPU", "重新创建GPU图像处理器失败: ${e.message}")
                                        }
                                    }
                                    return@setFrameCallback
                                }

                                // 5. 获取检测参数
                                val currentSettings = mainActivity.settingsManager.getSettings()
                                val threshold = currentSettings.threshold
                                val minBrightness = currentSettings.minBrightness
                                
                                // 6. 每隔一段时间记录检测参数
                                if (System.currentTimeMillis() % 10000 < 50) { // 每10秒记录一次参数
                                    LogManager.d("LaserDetectionGPU", "检测参数 - threshold: $threshold, minBrightness: $minBrightness, textureId: $textureId, processor: ${currentImageProcessor.hashCode()}")
                                }

                                // 7. 执行GPU检测
                                val detectionStartTime = System.currentTimeMillis()
                                val laserPoint = currentImageProcessor.processFrame(
                                    textureId, 
                                    threshold,
                                    minBrightness
                                )
                                val detectionTime = System.currentTimeMillis() - detectionStartTime

                                // 8. 处理检测结果
                                if (laserPoint != null) {
                                    LogManager.d("LaserDetectionGPU", "GPU检测成功 - 原始坐标: (${laserPoint.x}, ${laserPoint.y}), 亮度: ${laserPoint.brightness}, 置信度: ${laserPoint.confidence}, 耗时: ${detectionTime}ms")
                                    
                                    // 9. 坐标转换
                                    val screenCoords = laserPoint.toScreenCoords(
                                        MainActivity.getScreenWidth(),
                                        MainActivity.getScreenHeight()
                                    )
                                    LogManager.d("LaserDetectionGPU", "屏幕坐标转换 - 原始归一化坐标: (${laserPoint.x}, ${laserPoint.y}) -> 屏幕像素坐标: (${screenCoords.first}, ${screenCoords.second})")

                                    // 10. 透视变换
                                    val screenPoint = mainActivity.laserPointDetector.convertToScreenCoordinates(screenCoords.first, screenCoords.second)
                                    
                                    if (screenPoint != null) {
                                        LogManager.d("LaserDetectionGPU", "透视变换成功 - 屏幕像素: (${screenCoords.first}, ${screenCoords.second}) -> 目标坐标: (${screenPoint.x}, ${screenPoint.y})")
                                        
                                        // 11. 更新UI
                                        lastDetectedPointX.value = screenPoint.x
                                        lastDetectedPointY.value = screenPoint.y
                                        
                                        // 12. 发送到受控端
                                        if (ConnectActivity.isServerConnected) {
                                            val networkClient = ConnectActivity.getNetworkClient()
                                            networkClient.noticePointMove("1", "${screenPoint.x},${screenPoint.y}")
                                            LogManager.d("LaserDetectionGPU", "已发送激光点坐标到受控端: (${screenPoint.x}, ${screenPoint.y})")
                                        } else {
                                            LogManager.w("LaserDetectionGPU", "受控端未连接，无法发送激光点坐标")
                                        }
                                        
                                        LogManager.i("LaserDetectionGPU", "✓ 激光点检测完整流程成功: 最终坐标(${screenPoint.x}, ${screenPoint.y}), 总耗时: ${detectionTime}ms")
                                    } else {
                                        LogManager.w("LaserDetectionGPU", "透视变换失败 - 屏幕像素坐标: (${screenCoords.first}, ${screenCoords.second})")
                                        LogManager.w("LaserDetectionGPU", "可能原因: 1) 屏幕角点未正确设置 2) 坐标超出变换范围")
                                    }
                                } else {
                                    // 只偶尔记录未检测到的情况，避免日志过多
                                    if (System.currentTimeMillis() % 3000 < 50) { // 每3秒记录一次
                                        LogManager.d("LaserDetectionGPU", "GPU未检测到激光点，耗时: ${detectionTime}ms")
                                    }
                                }
                                
                            } catch (e: Exception) {
                                LogManager.e("LaserDetectionGPU", "GPU激光点检测异常: ${e.message}")
                                e.printStackTrace()
                            }
                        }

                        // 无论当前状态如何，都自动打开摄像头进行预览
                        // 读取当前设置以确定正确的参数
                        val settingsManager = SettingsManager.getInstance(ctx)
                        val currentSettings = settingsManager.getSettings()
                        
                        val isLaserDetectMode = detectionState.value == MainActivity.DetectionState.LASER_DETECTION
                        
                        // 根据设置确定是否使用高速帧率
                        val useHighSpeed = currentSettings.frameRateMode == com.scieford.laserctrlmouse.settings.FrameRateMode.HIGH_SPEED
                        mainActivity.useHighSpeed.value = useHighSpeed // 同步更新状态
                        
                        // 根据检测模式确定曝光模式
                        val exposureMode = if (isLaserDetectMode) {
                            ExposureMode.MANUAL
                        } else {
                            ExposureMode.AUTO
                        }
                        
                        LogManager.d("LaserDetectionScreen", "GLCameraSurfaceView启动参数 - 检测模式: $isLaserDetectMode, 高速帧率: $useHighSpeed, 曝光模式: $exposureMode, 摄像头ID: ${currentSettings.selectedCameraId}, 分辨率: ${currentSettings.resolution.displayName}, 曝光时间: ${currentSettings.exposureTimeMs}ms")

                        // 启动相机预览
                        post {
                            LogManager.d("LaserDetectionScreen", "延迟启动摄像头预览，key: $glSurfaceViewKeyValue")
                            startPreview(useHighSpeed, exposureMode, object : Camera2Helper.CameraStateCallback {
                                override fun onCameraReady() {
                                    LogManager.d("LaserDetectionScreen", "新GLSurfaceView的摄像头已就绪")
                                    
                                    // 获取最大变焦倍数并更新UI状态
                                    maxZoomRatio = camera2Helper.getMaxDigitalZoom()
                                    currentZoomRatio = camera2Helper.getCurrentZoomRatio()
                                    LogManager.d("LaserDetectionScreen", "摄像头就绪 - 最大变焦倍数: $maxZoomRatio, 当前变焦: $currentZoomRatio")
                                    
                                    // 延迟启用GPU处理
                                    Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        mainActivity.canRunGpuProcess = true
                                        LogManager.d("LaserDetectionScreen", "GPU处理已启用，GLSurfaceView key: $glSurfaceViewKeyValue")
                                    }, 1500)
                                }

                                override fun onCameraClosed() {
                                    LogManager.d("LaserDetectionScreen", "摄像头已关闭")
                                }

                                override fun onCameraError(error: Int) {
                                    LogManager.e("LaserDetectionScreen", "摄像头错误: $error")
                                }
                            })
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // 重建期间显示加载指示器
        if (isRecreatingGLSurfaceValue) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = androidx.compose.ui.graphics.Color.White
                )
                Text(
                    text = "Loading...",
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.padding(top = 48.dp)
                )
            }
        }
        
        // 状态信息浮层 - 左上角
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            // 显示当前状态
            Text(
                text = "${context.getString(R.string.current_status)}: ${getStateDisplayName(detectionState.value, context)}",
                color = MaterialTheme.colorScheme.onPrimary
            )
            
            // 显示当前摄像头信息
            if (currentCameraInfo.isNotEmpty()) {
                Text(
                    text = "${context.getString(R.string.current_camera)}: $currentCameraInfo",
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            
            // 显示帧率
             Text(
                 text = "${context.getString(R.string.current_fps)}: ${String.format("%.1f", currentFps)} fps",
                 color = MaterialTheme.colorScheme.onPrimary
             )
            
            // 显示当前方向信息
            Text(
                text = "${context.getString(R.string.orientation)}: ${if (MainActivity.isPortrait()) context.getString(R.string.portrait) else context.getString(R.string.landscape)}",
                color = MaterialTheme.colorScheme.onPrimary
            )
            
            // 显示摄像头分辨率
            val cameraRes = MainActivity.getCameraResolution()
            Text(
                text = "${context.getString(R.string.resolution)}: ${cameraRes.first}x${cameraRes.second}",
                color = MaterialTheme.colorScheme.onPrimary
            )
            
            // 显示温度信息
            Text(
                text = "${context.getString(R.string.battery_temperature)}: ${String.format("%.1f", batteryTemp)}°C",
                color = if (batteryTemp > 45f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimary
            )
            
            if (false && cpuTemp > 0f) {
                Text(
                    text = "${context.getString(R.string.cpu_temperature)}: ${String.format("%.1f", cpuTemp)}°C",
                    color = if (cpuTemp > 60f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimary
                )
            }
            
            // 如果在激光点检测模式且有坐标，显示坐标
            if (detectionState.value == MainActivity.DetectionState.LASER_DETECTION && (pointX > 0 || pointY > 0)) {
                Text(
                    text = "${context.getString(R.string.laser_point_coordinates)}: ($pointX, $pointY)",
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        
        // 数字变焦滑块 - 左下角
        if (maxZoomRatio > 1.0f) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .width(200.dp)
            ) {
                // 变焦标题和当前值
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(R.string.digital_zoom),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${String.format("%.1f", currentZoomRatio)}x",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // 变焦滑块
                Slider(
                    value = currentZoomRatio,
                    onValueChange = { newZoom ->
                        // 实时更新变焦比例
                        camera2Helper.setZoomRatio(newZoom)
                        LogManager.d("LaserDetectionScreen", "用户调整变焦: $newZoom")
                    },
                    valueRange = 1.0f..maxZoomRatio,
                    steps = if (maxZoomRatio > 2.0f) ((maxZoomRatio - 1.0f) * 10).toInt() else 10,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // 变焦范围显示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    Text(
                        text = context.getString(R.string.zoom_1x),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "${String.format("%.1f", maxZoomRatio)}x",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
        // 右边按钮区域 - 统一管理所有按钮，按顺序排列
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            // 设置按钮 - 图标
            androidx.compose.material3.IconButton(
                onClick = onOpenSettings,
                colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.6f),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier.size(48.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Lucide.Settings,
                    contentDescription = context.getString(R.string.settings),
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // 系统设置按钮 - 图标
            androidx.compose.material3.IconButton(
                onClick = onOpenSystemSettings,
                colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.6f),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier.size(48.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Lucide.Menu,
                    contentDescription = context.getString(R.string.system_settings),
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // 帮助按钮 - 图标
            androidx.compose.material3.IconButton(
                onClick = onOpenHelp,
                colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.6f),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier.size(48.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Lucide.CircleHelp,
                    contentDescription = context.getString(R.string.help),
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // 主按钮：连接受控端 - 放在图标按钮下面
            Button(
                onClick = onConnectServer,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (serverConnected) 
                        androidx.compose.ui.graphics.Color(0xFF4CAF50) // 绿色表示已连接
                    else 
                        androidx.compose.ui.graphics.Color(0xFF2196F3) // 蓝色表示未连接
                ),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .width(160.dp), // 主按钮更宽
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), // 更圆润的形状
                elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 2.dp
                )
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    // 连接状态图标
//                    androidx.compose.material3.Icon(
//                        imageVector = if (serverConnected)
//                            Lucide.Network
//                        else
//                            Lucide.Plus,
//                        contentDescription = null,
//                        tint = androidx.compose.ui.graphics.Color.White,
//                        modifier = Modifier.size(20.dp)
//                    )
//                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (serverConnected) {
                            if (connectedServerName.isNotEmpty()) 
                                context.getString(R.string.connected_with_name, connectedServerName) 
                            else 
                                context.getString(R.string.connected)
                        } else {
                            context.getString(R.string.connect_server)
                        },
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            // 【删除】原来的日志管理按钮区域已被移除
            // 用户级别为VIP或调试模式时才显示日志管理功能
            val context = LocalContext.current
            val settingsManager = SettingsManager.getInstance(context)
            val userLevel = settingsManager.getSettings().userLevel
            
            if (debugFunctionEnabled.value) {
                // 日志信息按钮 - 图标
                androidx.compose.material3.IconButton(
                    onClick = { 
                        com.scieford.laserctrlmouse.utils.showLogFilesInfo(context as android.content.Context)
                    },
                    colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF795548).copy(alpha = 0.9f),
                        contentColor = androidx.compose.ui.graphics.Color.White
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Lucide.FileText,
                        contentDescription = "日志信息",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // 日志上传按钮 - 图标
                var showUploadDialog by remember { mutableStateOf(false) }
                
                androidx.compose.material3.IconButton(
                    onClick = { showUploadDialog = true },
                    colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF8BC34A).copy(alpha = 0.9f),
                        contentColor = androidx.compose.ui.graphics.Color.White
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Lucide.Upload,
                        contentDescription = "上传日志",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // 日志上传对话框
                if (showUploadDialog) {
                    com.scieford.laserctrlmouse.utils.LogUploadDialog(
                        onDismiss = { showUploadDialog = false },
                        onUpload = { feedbackInfo, contactInfo ->
                            showUploadDialog = false
                            
                            // 执行日志上传
                            val logManager = LogManager.getInstance()
                            logManager?.uploadLogsToServer(
                                feedbackInfo = feedbackInfo,
                                contactInfo = contactInfo,
                                onSuccess = {
                                    com.scieford.laserctrlmouse.utils.showLogUploadResult(
                                        context, 
                                        true, 
                                        context.getString(R.string.log_upload_success)
                                    )
                                },
                                onError = { errorMsg ->
                                    com.scieford.laserctrlmouse.utils.showLogUploadResult(
                                        context, 
                                        false, 
                                        context.getString(R.string.log_upload_failed, errorMsg)
                                    )
                                }
                            )
                        }
                    )
                }
            }

            // 开始检测按钮（仅在空闲状态显示）
            if(debugFunctionEnabled.value) {
                if (detectionState.value == MainActivity.DetectionState.IDLE) {
                    /*
                Button(
                    onClick = onStartDetection,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Text(context.getString(R.string.start_detection))
                }
                */

                    // 调试测试按钮 - 图标
                    androidx.compose.material3.IconButton(
                        onClick = onStartDebugTest,
                        colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFFE91E63).copy(alpha = 0.9f),
                            contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Lucide.Bug,
                            contentDescription = context.getString(R.string.debug_test),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // 屏幕检测测试按钮 - 图标
                    androidx.compose.material3.IconButton(
                        onClick = onStartScreenDetectionTest,
                        colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF9C27B0).copy(alpha = 0.9f),
                            contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Lucide.MonitorSpeaker,
                            contentDescription = context.getString(R.string.screen_detection_test),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                } else {
                    // 显示当前检测状态的提示文本
                    Text(
                        text = getStateHintText(detectionState.value, context),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    // 只在激光检测模式时显示GPU测试按钮
                    if (detectionState.value == MainActivity.DetectionState.LASER_DETECTION) {
                        // GPU测试按钮 - 图标
                        androidx.compose.material3.IconButton(
                            onClick = onTestGpuResource,
                            colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                                containerColor = androidx.compose.ui.graphics.Color(0xFF00BCD4).copy(alpha = 0.9f),
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Lucide.Zap,
                                contentDescription = "测试GPU资源",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        // 诊断系统按钮 - 图标
                        androidx.compose.material3.IconButton(
                            onClick = { 
                                (context as MainActivity).diagnoseDetectionSystem()
                            },
                            colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                                containerColor = androidx.compose.ui.graphics.Color(0xFFE91E63).copy(alpha = 0.9f),
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Lucide.Stethoscope,
                                contentDescription = "诊断系统",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 获取状态显示名称
 */
private fun getStateDisplayName(state: MainActivity.DetectionState, context: Context): String {
    return when (state) {
        MainActivity.DetectionState.IDLE -> context.getString(R.string.status_camera_preview)
        MainActivity.DetectionState.SCREEN_DETECTION -> context.getString(R.string.status_screen_detection)
        MainActivity.DetectionState.LASER_DETECTION -> context.getString(R.string.status_laser_detection)
    }
}

/**
 * 获取状态提示文本
 */
private fun getStateHintText(state: MainActivity.DetectionState, context: Context): String {
    return when (state) {
        MainActivity.DetectionState.SCREEN_DETECTION -> context.getString(R.string.hint_screen_detection)
        MainActivity.DetectionState.LASER_DETECTION -> context.getString(R.string.hint_laser_detection)
        MainActivity.DetectionState.IDLE -> context.getString(R.string.hint_camera_ready)
        else -> ""
    }
}

