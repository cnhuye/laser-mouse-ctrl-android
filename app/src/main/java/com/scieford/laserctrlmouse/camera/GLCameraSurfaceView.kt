package com.scieford.laserctrlmouse.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.View
import com.scieford.laserctrlmouse.camera.Camera2Helper
import com.scieford.laserctrlmouse.camera.ExposureMode
import com.scieford.laserctrlmouse.MainActivity
import com.scieford.laserctrlmouse.utils.LogManager

/**
 * OpenGL 相机预览 SurfaceView
 * 使用 OpenGL ES 在 GPU 上处理相机图像
 */
class GLCameraSurfaceView : GLSurfaceView {
    companion object {
        private const val TAG = "GLCameraSurfaceView"
    }

    private var renderer: CameraGLRenderer? = null
    private var camera2Helper: Camera2Helper? = null
    private var targetWidth: Int
        get() = MainActivity.getCameraWidth()
        set(value) {
            // 不允许设置，始终从MainActivity获取
        }
    private var targetHeight: Int
        get() = MainActivity.getCameraHeight() 
        set(value) {
            // 不允许设置，始终从MainActivity获取
        }
    private var frameCallback: ((Int) -> Unit)? = null
    
    // 添加状态管理
    private var isRendererReady = false
    private var pendingStartPreview = false
    private var pendingUseHighSpeed = true
    private var pendingExposureMode = ExposureMode.AUTO
    private var pendingStateCallback: Camera2Helper.CameraStateCallback? = null
    
    // 强制使用摄像头兼容的尺寸
    private var forceCompatibleSize = false
    private val compatibleWidth: Int
        get() = MainActivity.getCameraWidth()
    private val compatibleHeight: Int
        get() = MainActivity.getCameraHeight()
    
    // 用于存储摄像头Surface
    private var cameraSurface: Surface? = null
    
    // 图像处理器
    private var imageProcessor: CameraImageProcessor? = null

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(context)
    }

    private fun init(context: Context) {
        // 设置 OpenGL ES 版本
        setEGLContextClientVersion(2)

        // 创建 OpenGL 渲染器
        renderer = CameraGLRenderer(context)
        
        renderer?.setOnFrameAvailableListener(object : CameraGLRenderer.OnFrameAvailableListener {
            override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
                // 减少日志输出频率
                // 触发重绘（即使在连续模式下也确保及时更新）
                requestRender()
                // 回调给外部处理，传递渲染器的纹理ID
                queueEvent{
                    frameCallback?.invoke(renderer?.getTextureId() ?: 0)
                }
            }
        })

        // 设置渲染器
        setRenderer(renderer)
        
        // 设置为连续渲染模式，确保持续更新
        renderMode = RENDERMODE_CONTINUOUSLY
        
        // 添加渲染器就绪回调
        renderer?.setOnRendererReadyListener(object : CameraGLRenderer.OnRendererReadyListener {
            override fun onRendererReady() {
                LogManager.d(TAG, "渲染器已就绪")
                
                initImageProcessor()
                
                // 在OpenGL线程中创建摄像头专用的SurfaceTexture
                queueEvent {
                    createCameraSurface()
                }
                
                isRendererReady = true
                
                // 如果有待处理的 startPreview 调用，现在执行
                if (pendingStartPreview) {
                    pendingStartPreview = false
                    LogManager.d(TAG, "执行待处理的 startPreview 调用")
                    startPreviewInternal(pendingUseHighSpeed, pendingExposureMode, pendingStateCallback)
                }
            }
        })
    }

    fun initImageProcessor() {
        // 在OpenGL线程中初始化图像处理器
        queueEvent {
            try {
                LogManager.d(TAG, "【DEBUG】在OpenGL线程中初始化图像处理器")

                // 检查OpenGL上下文
                val glVersion = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION)
                val glRenderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
                LogManager.d(TAG, "【DEBUG】OpenGL上下文 - 版本: $glVersion, 渲染器: $glRenderer")

                imageProcessor?.initialize(targetWidth, targetHeight)
                LogManager.d(TAG, "图像处理器在OpenGL上下文中初始化完成 ${imageProcessor.hashCode()}")

                // 尝试初始化GPU检测器（现在有OpenGL状态保护）
                try {
                    LogManager.d(TAG, "【DEBUG】开始初始化GPU检测器")
                    val gpuInitialized = imageProcessor?.initializeGPUDetector() ?: false
                    if (gpuInitialized) {
                        LogManager.i(TAG, "GPU加速检测器初始化成功 - 目标性能 < 10ms")
                    } else {
                        LogManager.w(TAG, "GPU检测器不可用，将使用CPU模式")
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "GPU检测器初始化失败，继续使用摄像头显示: ${e.message}")
                    e.printStackTrace()
                }

            } catch (e: Exception) {
                LogManager.e(TAG, "图像处理器初始化失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 设置相机帮助类
     */
    fun setCamera2Helper(camera2Helper: Camera2Helper) {
        this.camera2Helper = camera2Helper
        // 同时设置GLSurfaceView引用到Camera2Helper，用于调试SurfaceTexture
        camera2Helper.setGLSurfaceView(this)
        LogManager.d(TAG, "设置相机帮助类: ${camera2Helper != null}")
    }

    /**
     * 设置目标分辨率
     * 注意：分辨率将始终从MainActivity动态获取，此方法仅用于兼容性
     */
    fun setTargetResolution(width: Int, height: Int) {
        val actualWidth = MainActivity.getCameraWidth()
        val actualHeight = MainActivity.getCameraHeight()
        LogManager.d(TAG, "setTargetResolution调用: 请求${width}x${height}, 实际使用: ${actualWidth}x${actualHeight}")
    }

    /**
     * 设置帧回调
     */
    fun setFrameCallback(callback: (Int) -> Unit) {
        frameCallback = callback
        LogManager.d(TAG, "设置帧回调: ${callback != null}")
    }

    /**
     * 设置图像处理器
     */
    fun setImageProcessor(processor: CameraImageProcessor) {
        imageProcessor = processor
        LogManager.d(TAG, "设置图像处理器: ${processor != null}")
    }

    /**
     * 获取渲染器实例（用于调试）
     */
    fun getRenderer(): CameraGLRenderer? {
        return renderer
    }

    /**
     * 设置屏幕区域边框
     * @param corners 屏幕区域的四个角点坐标（相对于摄像头预览）
     */
    fun setScreenBorder(corners: Array<org.opencv.core.Point>?) {
        // 在OpenGL线程中执行，确保线程安全
        queueEvent {
            renderer?.setScreenBorder(corners)
        }
        LogManager.d(TAG, "设置屏幕边框: ${corners != null}")
    }

    /**
     * 启用强制兼容尺寸模式
     * 这将强制GLSurfaceView使用摄像头支持的尺寸而不是布局尺寸
     * 分辨率从MainActivity动态获取
     */
    fun enableForceCompatibleSize(width: Int, height: Int) {
        forceCompatibleSize = true
        LogManager.d(TAG, "启用强制兼容尺寸: 请求${width}x${height}, 实际使用${compatibleWidth}x${compatibleHeight}")
        
        // 请求重新布局
        requestLayout()
    }
    
    /**
     * 禁用强制兼容尺寸模式
     */
    fun disableForceCompatibleSize() {
        forceCompatibleSize = false
        LogManager.d(TAG, "禁用强制兼容尺寸")
        requestLayout()
    }

    /**
     * 开始相机预览
     */
    fun startPreview(
        useHighSpeed: Boolean = true, 
        exposureMode: ExposureMode = ExposureMode.AUTO,
        stateCallback: Camera2Helper.CameraStateCallback? = null
    ) {
        LogManager.d(TAG, "startPreview 被调用: useHighSpeed=$useHighSpeed, exposureMode=$exposureMode, isRendererReady=$isRendererReady")
        
        // 启用自定义尺寸模式，确保使用我们的 onMeasure 逻辑
        enableForceCompatibleSize(targetWidth, targetHeight)
        LogManager.d(TAG, "启用自定义尺寸模式，摄像头分辨率: ${targetWidth}x${targetHeight}, useHighSpeed=$useHighSpeed")
        
        if (!isRendererReady) {
            // 渲染器还没就绪，保存参数等待就绪后执行
            LogManager.d(TAG, "渲染器未就绪，保存参数等待执行")
            pendingStartPreview = true
            pendingUseHighSpeed = useHighSpeed
            pendingExposureMode = exposureMode
            pendingStateCallback = stateCallback
            return
        }
        
        startPreviewInternal(useHighSpeed, exposureMode, stateCallback)
    }
    
    /**
     * 开始相机预览的内部实现
     */
    private fun startPreviewInternal(useHighSpeed: Boolean, exposureMode: ExposureMode, stateCallback: Camera2Helper.CameraStateCallback?) {
        // 在 OpenGL 线程中执行
        queueEvent {
            try {
                LogManager.d(TAG, "在 OpenGL 线程中开始相机预览")
                
                if (camera2Helper == null) {
                    LogManager.e(TAG, "Camera2Helper 未设置")
                    return@queueEvent
                }
                
                val cameraS = cameraSurface
                if (cameraS != null && cameraS.isValid) {
                    LogManager.d(TAG, "使用摄像头专用Surface，尺寸: ${targetWidth}x${targetHeight}")
                    
                    // 在主线程中打开相机
                    post {
                        try {
                            LogManager.d(TAG, "在主线程中打开相机")
                            
                            camera2Helper?.apply {
                                // 设置激光点检测模式
                                setLaserDetectMode(exposureMode == ExposureMode.MANUAL)
                                
                                // 不在这里设置帧率监听器，避免覆盖MainActivity中的设置
                                // MainActivity中已经通过LaunchedEffect设置了帧率监听器
                                
                                // 使用专用的摄像头Surface打开相机
                                openCameraWithSurface(
                                    surface = cameraS,
                                    width = targetWidth,
                                    height = targetHeight,
                                    useHighSpeed = useHighSpeed,
                                    frameRateListener = null, // 不设置，使用已有的监听器
                                    exposureMode = exposureMode,
                                    stateCallback = stateCallback
                                )
                            }
                            
                            LogManager.d(TAG, "相机预览已启动: 分辨率=${targetWidth}x${targetHeight}, 高速模式=$useHighSpeed, 曝光模式=$exposureMode")
                        } catch (e: Exception) {
                            LogManager.e(TAG, "在主线程中启动相机失败: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    LogManager.e(TAG, "摄像头Surface未创建或无效")
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "启动预览失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 停止相机预览
     */
    fun stopPreview() {
        LogManager.d(TAG, "停止相机预览")
        camera2Helper?.closeCamera()
    }

    /**
     * 释放资源
     */
    fun release() {
        LogManager.d(TAG, "释放GLCameraSurfaceView资源")
        stopPreview()
        
        // 在OpenGL线程中释放渲染器和摄像头相关资源
        queueEvent {
            try {
                LogManager.d(TAG, "在OpenGL线程中释放资源")
                
                // 释放渲染器资源（包括着色器、纹理等）
                renderer?.release()
                
                // 释放摄像头Surface
                cameraSurface?.release()
                cameraSurface = null
                
                LogManager.d(TAG, "GLSurfaceView相关资源已在OpenGL线程中释放")
            } catch (e: Exception) {
                LogManager.e(TAG, "在OpenGL线程中释放资源失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    override fun onDetachedFromWindow() {
        LogManager.d(TAG, "onDetachedFromWindow")
        release()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val specHeight = View.MeasureSpec.getSize(heightMeasureSpec)
        
        // 根据设备方向和摄像头分辨率动态计算目标宽高比
        val isPortrait = MainActivity.isPortrait()
        val cameraWidth = targetWidth.toFloat()
        val cameraHeight = targetHeight.toFloat()
        
        val targetAspectRatio = if (isPortrait) {
            // 竖屏时：摄像头内容旋转90度，宽高比变为 height/width
            cameraHeight / cameraWidth
        } else {
            // 横屏时：摄像头内容直接显示，宽高比为 width/height
            cameraWidth / cameraHeight
        }
        
        val viewAspectRatio = specWidth.toFloat() / specHeight.toFloat()
        
        val finalWidth: Int
        val finalHeight: Int
        
        // 根据目标宽高比计算最终尺寸
        if (viewAspectRatio > targetAspectRatio) {
            // 可用宽度更大，以高度为基准计算宽度
            finalHeight = specHeight
            finalWidth = (specHeight * targetAspectRatio).toInt()
        } else {
            // 可用高度更大，以宽度为基准计算高度
            finalWidth = specWidth
            finalHeight = (specWidth / targetAspectRatio).toInt()
        }
        
        LogManager.d(TAG, "根据摄像头分辨率动态调整显示比例:")
        LogManager.d(TAG, "  摄像头分辨率: ${cameraWidth.toInt()}x${cameraHeight.toInt()}")
        LogManager.d(TAG, "  设备方向: ${if(isPortrait) "竖屏" else "横屏"}")
        LogManager.d(TAG, "  目标比例: $targetAspectRatio")
        LogManager.d(TAG, "  规格尺寸: ${specWidth}x${specHeight}")
        LogManager.d(TAG, "  最终尺寸: ${finalWidth}x${finalHeight}")
        
        setMeasuredDimension(finalWidth, finalHeight)
    }

    /**
     * 创建摄像头Surface（使用渲染器的SurfaceTexture）
     */
    private fun createCameraSurface() {
        try {
            // 等待渲染器完全就绪
            Thread.sleep(100)
            
            // 从渲染器获取已创建的SurfaceTexture
            val surfaceTexture = renderer?.getSurfaceTexture()
            
            if (surfaceTexture != null) {
                // 设置正确的缓冲区大小
                surfaceTexture.setDefaultBufferSize(targetWidth, targetHeight)
                
                // 从SurfaceTexture创建Surface
                cameraSurface = Surface(surfaceTexture)
                
                LogManager.d(TAG, "摄像头Surface创建成功: ${cameraSurface?.isValid}, 尺寸: ${targetWidth}x${targetHeight}")
            } else {
                LogManager.e(TAG, "无法获取渲染器的SurfaceTexture")
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "创建摄像头Surface失败: ${e.message}")
            e.printStackTrace()
        }
    }
} 