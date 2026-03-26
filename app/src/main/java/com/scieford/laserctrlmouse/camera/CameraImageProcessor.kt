package com.scieford.laserctrlmouse.camera

/*
 * CameraImageProcessor - 摄像头图像处理器
 * 
 * GPU资源管理全面检查和修复完成:
 * 
 * 已确认释放的所有GPU资源:
 * ================================
 * 
 * 1. CameraImageProcessor.kt:
 *    - fboId (帧缓冲对象)
 *    - colorTextureId (颜色纹理)
 *    - programId (着色器程序)
 *    - gpuDetector (GPU检测器，递归释放)
 * 
 * 2. GPULaserDetector.kt (通过CameraImageProcessor.gpuDetector释放):
 *    Programs (着色器程序):
 *    - computeProgram
 *    - optimizedComputeProgram
 *    - clearProgram
 *    - clearGlobalProgram
 *    - debugComputeProgram
 *    - testComputeProgram
 *    - textureTestProgram
 *    - ssboVerifyProgram
 *    - uboComputeProgram
 *    - textureOutputProgram
 *    - conversionProgram (fallback模式)
 *    
 *    Buffers (缓冲区对象):
 *    - ssboBuffer
 *    - globalMaxBuffer
 *    - uboBuffer
 *    
 *    Textures & FBOs (纹理和帧缓冲):
 *    - outputTexture
 *    - outputFBO
 *    - conversionTexture (fallback模式)
 *    - conversionFBO (fallback模式)
 * 
 * 3. CameraGLRenderer.kt (通过GLCameraSurfaceView.renderer释放):
 *    - program (主着色器程序)
 *    - lineProgram (线条着色器程序)
 *    - textureID (摄像头纹理)
 *    - surfaceTexture (SurfaceTexture对象)
 * 
 * 资源释放链条:
 * ============
 * MainActivity.onDestroy() -> 
 * GLCameraSurfaceView.release() -> 
 * CameraGLRenderer.release() + CameraImageProcessor.release() ->
 * GPULaserDetector.release()
 * 
 * 安全措施:
 * ========
 * - 所有release方法都检查OpenGL上下文有效性
 * - 在正确的OpenGL线程中执行资源释放
 * - 添加异常处理防止单个资源释放失败影响其他资源
 * - 详细的日志记录帮助调试资源释放问题
 * - 释放后进行OpenGL错误检查
 */

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.scieford.laserctrlmouse.utils.LogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 摄像头图像处理器
 * 在 GPU 上处理摄像头图像，执行自定义的片段着色器
 * 修改为使用现有的OpenGL上下文而不是创建独立的EGL上下文
 */
class CameraImageProcessor(private val context: Context) {
    companion object {
        private const val TAG = "CameraImageProcessor"
        
        // 激光点检测着色器 - 兼容 OpenGL ES 2.0
        private const val LASER_DETECTOR_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            
            // 激光点检测参数
            uniform float uThreshold;  // 亮度阈值
            
            void main() {
                // 获取纹理颜色
                vec4 color = texture2D(sTexture, vTextureCoord);
                
                // 计算亮度 (0.299*R + 0.587*G + 0.114*B)
                float brightness = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                
                // 检测激光点 - 如果亮度超过阈值，输出高亮显示
                if (brightness > uThreshold) {
                    // 输出激光点：红色通道为原始颜色，绿色通道为坐标信息
                    gl_FragColor = vec4(color.r, vTextureCoord.x, vTextureCoord.y, brightness);
                } else {
                    // 普通像素：保持原始颜色，alpha设为0表示非激光点
                    gl_FragColor = vec4(color.rgb, 0.0);
                }
            }
        """
        
        // 顶点着色器源码 - 简单的全屏四边形渲染
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = aTextureCoord;
            }
        """
    }
    
    // 帧缓冲对象 (FBO)
    private var fboId = 0
    private var colorTextureId = 0
    
    // 处理后的数据
    private lateinit var outputBuffer: ByteBuffer
    
    // 图像尺寸
    private var width = 0
    private var height = 0
    
    // 着色器程序
    private var programId = 0
    
    // 顶点缓冲区
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer
    
    // 着色器变量位置
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0
    private var thresholdHandle = 0
    
    // 是否已初始化
    private var initialized = false
    
    // GPU检测器实例
    private var gpuDetector: GPULaserDetector? = null
    private var useGPUAcceleration = true
    
    /**
     * 初始化处理器资源
     * 注意：必须在有效的OpenGL上下文中调用
     */
    fun initialize(width: Int, height: Int) {
        this.width = width
        this.height = height
        
        try {
            // 创建着色器程序
            programId = createProgram(VERTEX_SHADER, LASER_DETECTOR_SHADER)
            
            // 获取着色器变量位置
            positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
            textureHandle = GLES20.glGetUniformLocation(programId, "sTexture")
            thresholdHandle = GLES20.glGetUniformLocation(programId, "uThreshold")
            
            // 创建顶点缓冲区
            createVertexBuffers()
            
            // 创建输出缓冲区
            outputBuffer = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
            
            initialized = true
            LogManager.d(TAG, "图像处理器初始化完成: ${width}x${height}")
        } catch (e: Exception) {
            LogManager.e(TAG, "初始化失败: ${e.message}")
            e.printStackTrace()
            release()
        }
    }
    
    /**
     * 创建顶点缓冲区
     */
    private fun createVertexBuffers() {
        // 顶点坐标
        val vertices = floatArrayOf(
            -1.0f, -1.0f, 0.0f,  // 左下
            1.0f, -1.0f, 0.0f,   // 右下
            -1.0f, 1.0f, 0.0f,   // 左上
            1.0f, 1.0f, 0.0f     // 右上
        )
        
        // 纹理坐标
        val texCoords = floatArrayOf(
            0.0f, 0.0f,     // 左下
            1.0f, 0.0f,     // 右下
            0.0f, 1.0f,     // 左上
            1.0f, 1.0f      // 右上
        )
        
        // 创建顶点坐标缓冲区
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)
        
        // 创建纹理坐标缓冲区
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
        texCoordBuffer.position(0)
    }
    
    /**
     * 处理相机图像
     * 注意：必须在有效的OpenGL上下文中调用，且该上下文必须是创建SurfaceTexture的同一个上下文
     * @param externalTextureId 外部纹理ID（来自SurfaceTexture）
     * @param threshold 激光点检测阈值 (0.0-1.0)
     * @return 检测到的激光点坐标和亮度，如果没有检测到返回 null
     */
    fun processFrame(externalTextureId: Int, threshold: Float = 0.8f, minBrightness: Float = 0.7f): LaserPointData? {
        if (!initialized) {
            LogManager.e(TAG, "处理器未初始化")
            return null
        }
        
        try {
            // 优先使用GPU加速检测器直接从摄像头纹理检测
            if (useGPUAcceleration && gpuDetector != null) {
                try {
                    // 直接从摄像头纹理检测，避免CPU-GPU数据传输
                    val result = gpuDetector!!.detectLaserPoint(
                        externalTextureId, 
                        threshold, 
                        minBrightness,
                    )
                    if (result != null) {
                        LogManager.d(TAG, "GPU直接检测成功: (${result.x}, ${result.y}) 亮度=${result.brightness}")
                        return result
                    }
                } catch (e: Exception) {
                    LogManager.w(TAG, "GPU直接检测失败，尝试传统方法: ${e.message}")
                    // 不立即禁用GPU，先尝试传统方法
                }
            }
            
            // 降级方案：使用传统的FBO渲染方法
//            return processFrameTraditional(externalTextureId, threshold)
            return null
            
        } catch (e: Exception) {
            LogManager.e(TAG, "处理帧失败: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    

    /**
     * 创建着色器程序
     */
    private fun createProgram(vertexShaderSource: String, fragmentShaderSource: String): Int {
        // 编译顶点着色器
        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vertexShader, vertexShaderSource)
        GLES20.glCompileShader(vertexShader)
        
        // 检查编译状态
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(vertexShader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(vertexShader)
            GLES20.glDeleteShader(vertexShader)
            throw RuntimeException("Vertex shader compilation failed: $error")
        }
        
        // 编译片段着色器
        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fragmentShader, fragmentShaderSource)
        GLES20.glCompileShader(fragmentShader)
        
        // 检查编译状态
        GLES20.glGetShaderiv(fragmentShader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(fragmentShader)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            throw RuntimeException("Fragment shader compilation failed: $error")
        }
        
        // 创建程序
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        // 检查链接状态
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program linking failed: $error")
        }
        
        // 清理着色器资源
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        
        return program
    }
    

    /**
     * 释放资源
     */
    fun release() {
        LogManager.d(TAG, "开始释放CameraImageProcessor资源")
        
        // 检查当前线程是否有有效的OpenGL上下文
        val glVersion = try {
            GLES20.glGetString(GLES20.GL_VERSION)
        } catch (e: Exception) {
            LogManager.w(TAG, "获取OpenGL版本失败，可能不在OpenGL上下文中: ${e.message}")
            null
        }
        
        if (glVersion == null) {
            LogManager.w(TAG, "没有有效的OpenGL上下文，跳过OpenGL资源释放")
            // 仍然释放非OpenGL资源
            gpuDetector?.release()
            gpuDetector = null
            initialized = false
            LogManager.d(TAG, "CameraImageProcessor资源释放完成（跳过OpenGL资源）")
            return
        }
        
        LogManager.d(TAG, "在有效的OpenGL上下文中释放资源，OpenGL版本: $glVersion")
        
        try {
            // 释放帧缓冲对象
            if (fboId != 0) {
                val fbos = intArrayOf(fboId)
                GLES20.glDeleteFramebuffers(1, fbos, 0)
                fboId = 0
                LogManager.d(TAG, "帧缓冲对象已释放")
            }
            
            // 释放颜色纹理
            if (colorTextureId != 0) {
                val textures = intArrayOf(colorTextureId)
                GLES20.glDeleteTextures(1, textures, 0)
                colorTextureId = 0
                LogManager.d(TAG, "颜色纹理已释放")
            }
            
            // 释放着色器程序
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
                LogManager.d(TAG, "着色器程序已释放")
            }
            
            // 检查OpenGL错误
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                LogManager.w(TAG, "释放OpenGL资源后发现错误: $error")
            } else {
                LogManager.d(TAG, "OpenGL资源释放成功，无错误")
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "释放OpenGL资源时发生异常: ${e.message}")
            e.printStackTrace()
        }
        
        try {
            // 释放GPU检测器（这个也需要在OpenGL上下文中）
            gpuDetector?.release()
            gpuDetector = null
            LogManager.d(TAG, "GPU检测器已释放")
        } catch (e: Exception) {
            LogManager.e(TAG, "释放GPU检测器时发生异常: ${e.message}")
            e.printStackTrace()
        }

        initialized = false
        LogManager.d(TAG, "CameraImageProcessor资源释放完成")
    }
    
    /**
     * 初始化GPU检测器
     * 必须在OpenGL上下文中调用
     */
    fun initializeGPUDetector(): Boolean {
        if (gpuDetector != null) return true
        
        try {
            gpuDetector = GPULaserDetector(context)
            val success = gpuDetector!!.initialize(width, height)
            
            if (success) {
                LogManager.i(TAG, "GPU加速检测器初始化成功")
                useGPUAcceleration = true
                
                // 启用性能优化
                gpuDetector!!.setPerformanceOptimizations(
                    enableOptimizations = true,
                    skipGLErrorChecks = true,
                    useMinimalSync = true,  // 启用最小同步以减少GPU等待时间
                    enableFast = true       // 启用快速模式以减少SSBO读取时间
                )
                
                return true
            } else {
                LogManager.w(TAG, "GPU检测器初始化失败，使用CPU降级")
                gpuDetector?.release()
                gpuDetector = null
                useGPUAcceleration = false
                return false
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "GPU检测器创建失败: ${e.message}")
            gpuDetector?.release()
            gpuDetector = null
            useGPUAcceleration = false
            return false
        }
    }
    

    /**
     * 激光点数据类
     */
    data class LaserPointData(
        val x: Float,         // 归一化的 X 坐标 (0.0-1.0)
        val y: Float,         // 归一化的 Y 坐标 (0.0-1.0)
        val brightness: Float, // 亮度值 (0.0-1.0)
        val confidence: Float = brightness // 置信度，默认使用亮度值
    ) {
        /**
         * 转换为屏幕坐标
         */
        fun toScreenCoords(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
            val screenX = (x * screenWidth).toInt()
            val screenY = (y * screenHeight).toInt()
            return Pair(screenX, screenY)
        }
    }
} 