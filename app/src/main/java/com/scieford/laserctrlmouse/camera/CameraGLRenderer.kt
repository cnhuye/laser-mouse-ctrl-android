package com.scieford.laserctrlmouse.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.scieford.laserctrlmouse.MainActivity
import com.scieford.laserctrlmouse.utils.LogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 摄像头 OpenGL 渲染器
 * 使用 GL ES 在 GPU 上处理摄像头图像
 */
class CameraGLRenderer(context: Context) : GLSurfaceView.Renderer {
    companion object {
        private const val TAG = "CameraGLRenderer"

        // 顶点着色器代码
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            void main() {
              gl_Position = uMVPMatrix * aPosition;
              vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
            }
        """

        // 片段着色器代码，使用 GL_OES_EGL_image_external 扩展处理摄像头纹理
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
              gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        // 线条顶点着色器代码
        private const val LINE_VERTEX_SHADER = """
            attribute vec4 aPosition;
            uniform mat4 uMVPMatrix;
            void main() {
              gl_Position = uMVPMatrix * aPosition;
            }
        """

        // 线条片段着色器代码
        private const val LINE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
              gl_FragColor = uColor;
            }
        """

        // 顶点坐标，覆盖整个屏幕的矩形
        private val VERTEX_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f,  // 左下
            1.0f, -1.0f, 0.0f,   // 右下
            -1.0f, 1.0f, 0.0f,   // 左上
            1.0f, 1.0f, 0.0f     // 右上
        )
    }

    // 纹理坐标 - 会根据设备方向动态调整
    // 这些是基础坐标，会在运行时根据方向进行调整
    private val baseTextureCoords = floatArrayOf(
        0.0f, 1.0f,  // 左下
        0.0f, 0.0f,  // 左上
        1.0f, 1.0f,  // 右下
        1.0f, 0.0f   // 右上
    )

    private val vertexBuffer: FloatBuffer
    private var textureBuffer: FloatBuffer
    private var program = 0
    private var textureID = 0

    // 边框绘制相关
    private var lineProgram = 0
    private var lineBuffer: FloatBuffer? = null
    private var linePositionHandle = 0
    private var lineMvpMatrixHandle = 0
    private var lineColorHandle = 0
    private var screenCorners: Array<org.opencv.core.Point>? = null
    private var shouldDrawBorder = false

    private var surfaceTexture: SurfaceTexture? = null
    private var frameListener: OnFrameAvailableListener? = null
    private var rendererReadyListener: OnRendererReadyListener? = null

    // 变换矩阵
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private var positionHandle = 0
    private var textureCoordHandle = 0
    private var mvpMatrixHandle = 0
    private var stMatrixHandle = 0
    private var textureHandle = 0

    private var width = 0
    private var height = 0
    private var updateSurface = false

    private var frameCount = 0
    
    // 目标摄像头分辨率 - 动态获取
    private val targetCameraWidth: Int
        get() = MainActivity.getCameraWidth()
    private val targetCameraHeight: Int  
        get() = MainActivity.getCameraHeight()

    // 外部SurfaceTexture（用于独立的摄像头纹理）
    private var externalSurfaceTexture: SurfaceTexture? = null

    interface OnFrameAvailableListener {
        fun onFrameAvailable(surfaceTexture: SurfaceTexture)
    }
    
    interface OnRendererReadyListener {
        fun onRendererReady()
    }

    init {
        // 初始化顶点坐标缓冲区
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(VERTEX_COORDS)
                position(0)
            }

        // 初始化纹理坐标缓冲区
        textureBuffer = ByteBuffer.allocateDirect(baseTextureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(baseTextureCoords)
                position(0)
            }

        // 初始化变换矩阵
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.setIdentityM(stMatrix, 0)
    }

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        // 设置清屏颜色
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // 创建 OpenGL 程序
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            LogManager.e(TAG, "Failed to create program")
            return
        }

        // 创建线条着色器程序
        lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        if (lineProgram == 0) {
            LogManager.e(TAG, "Failed to create line program")
            return
        }

        // 获取着色器变量位置
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture")

        // 获取线条着色器变量位置
        linePositionHandle = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        lineMvpMatrixHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        lineColorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        // 创建摄像头纹理
        textureID = createExternalTexture()

        // 创建 SurfaceTexture 用于接收摄像头数据
        surfaceTexture = SurfaceTexture(textureID)

        surfaceTexture!!.apply {
            LogManager.d(TAG, "创建 SurfaceTexture，纹理ID: $textureID")
            
            // 立即设置缓冲区大小为目标摄像头分辨率
            setDefaultBufferSize(targetCameraWidth, targetCameraHeight)
            LogManager.d(TAG, "设置SurfaceTexture缓冲区大小: ${targetCameraWidth}x${targetCameraHeight}")

            // 明确设置SurfaceTexture为标准格式，避免HDR相关问题
            try {
                // 强制使用标准的RGB格式，避免HDR格式问题
                // 这可以防止系统尝试处理HDR元数据
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // API 26+ 可以设置数据空间
                    val method = SurfaceTexture::class.java.getMethod("setDataSpace", Int::class.java)
                    // 使用标准SRGB数据空间，避免HDR数据空间
                    method.invoke(this, 0) // ADATASPACE_SRGB = 0
                    LogManager.d(TAG, "已设置SurfaceTexture为标准SRGB数据空间")
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "无法设置SurfaceTexture数据空间（可能是设备不支持）: ${e.message}")
                // 这是可选的优化，失败不影响基本功能
            }

            // SurfaceTexture 的 setOnFrameAvailableListener 不随屏幕的刷新率限制
            // 会被摄像头的输出帧率控制执行
            setOnFrameAvailableListener { texture ->
//                LogManager.d(TAG, "onFrameAvailable")
                synchronized(this@CameraGLRenderer) {
                    updateSurface = true


                    // 帧回调，放在 onDrawFrame 里执行了
                    frameListener?.onFrameAvailable(texture)
                }
            }
        }
        
        // 通知渲染器已就绪
        LogManager.d(TAG, "OpenGL 渲染器初始化完成，通知外部")
        rendererReadyListener?.onRendererReady()
    }

    /**
     * 更新纹理坐标缓冲区（在方向变化时调用）
     */
    private fun updateTextureBuffer() {
        val coords = getTextureCoords()
        textureBuffer.clear()
        textureBuffer.put(coords)
        textureBuffer.position(0)
        
        val orientation = if (MainActivity.isPortrait()) "竖屏" else "横屏"
        LogManager.d(TAG, "纹理坐标已更新，当前方向: $orientation")
        LogManager.d(TAG, "使用的纹理坐标: [${coords[0]}, ${coords[1]}], [${coords[2]}, ${coords[3]}], [${coords[4]}, ${coords[5]}], [${coords[6]}, ${coords[7]}]")
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        // 设置视口大小
        GLES20.glViewport(0, 0, width, height)
        this.width = width
        this.height = height
        
        // 更新纹理坐标以适应当前方向
        updateTextureBuffer()
        
        // 计算宽高比调整
        val viewAspectRatio = width.toFloat() / height.toFloat()
        
        // 根据设备方向和摄像头实际分辨率计算有效宽高比
        val isPortrait = MainActivity.isPortrait()
        val cameraWidth = targetCameraWidth.toFloat()
        val cameraHeight = targetCameraHeight.toFloat()
        
        val effectiveCameraAspectRatio = if (isPortrait) {
            // 竖屏时，摄像头内容被旋转90度，有效比例是 height/width
            cameraHeight / cameraWidth
        } else {
            // 横屏时，摄像头内容直接显示，有效比例是 width/height
            cameraWidth / cameraHeight
        }
        
        LogManager.d(TAG, "Surface尺寸变化:")
        LogManager.d(TAG, "  视图尺寸: ${width}x${height}, 宽高比: $viewAspectRatio")
        LogManager.d(TAG, "  摄像头硬件分辨率: ${cameraWidth.toInt()}x${cameraHeight.toInt()}")
        LogManager.d(TAG, "  设备方向: ${if(isPortrait) "竖屏" else "横屏"}")
        LogManager.d(TAG, "  有效摄像头比例: $effectiveCameraAspectRatio")
        
        // 重置MVP矩阵
        Matrix.setIdentityM(mvpMatrix, 0)
        
        // 根据宽高比调整显示，确保摄像头内容不被拉伸
        if (Math.abs(viewAspectRatio - effectiveCameraAspectRatio) < 0.01f) {
            // 宽高比基本匹配，不需要缩放
            LogManager.d(TAG, "宽高比匹配，不需要缩放")
        } else if (viewAspectRatio > effectiveCameraAspectRatio) {
            // 视图更宽，需要在水平方向缩放
            val scale = effectiveCameraAspectRatio / viewAspectRatio
            Matrix.scaleM(mvpMatrix, 0, scale, 1.0f, 1.0f)
            LogManager.d(TAG, "水平缩放: $scale")
        } else {
            // 视图更高，需要在垂直方向缩放
            val scale = viewAspectRatio / effectiveCameraAspectRatio
            Matrix.scaleM(mvpMatrix, 0, 1.0f, scale, 1.0f)
            LogManager.d(TAG, "垂直缩放: $scale")
        }
    }
    private fun checkGLError(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            LogManager.e(TAG, "$operation: OpenGL错误 $error")
            throw RuntimeException("$operation: OpenGL错误 $error")
        }
    }

    override fun onDrawFrame(unused: GL10) {
        var textureUpdated = false

        checkGLError("onDrawFrame opengl error")
        synchronized(this) {
            // 优先使用外部SurfaceTexture，如果没有则使用内部的
            val activeSurfaceTexture = externalSurfaceTexture ?: surfaceTexture
            
            if (updateSurface && activeSurfaceTexture != null) {
                try {
                    activeSurfaceTexture.updateTexImage()
                    activeSurfaceTexture.getTransformMatrix(stMatrix)
                    updateSurface = false
                    textureUpdated = true
                } catch (e: Exception) {
                    LogManager.e(TAG, "更新 SurfaceTexture 失败: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        checkGLError("onDrawFrame opengl error")

        // 清屏
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        checkGLError("onDrawFrame opengl error")

        // 只有在有纹理数据时才绘制
        val activeSurfaceTexture = externalSurfaceTexture ?: surfaceTexture
        if (activeSurfaceTexture != null && textureID != 0) {
            // 使用着色器程序
            GLES20.glUseProgram(program)
        checkGLError("onDrawFrame opengl error")

            // 设置顶点坐标
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)
        checkGLError("onDrawFrame opengl error")

            // 设置纹理坐标
            GLES20.glVertexAttribPointer(textureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
            GLES20.glEnableVertexAttribArray(textureCoordHandle)
        checkGLError("onDrawFrame opengl error")

            // 设置变换矩阵
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
        checkGLError("onDrawFrame opengl error")

            // 设置纹理
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID)
            GLES20.glUniform1i(textureHandle, 0)
        checkGLError("onDrawFrame opengl error")

            // 绘制矩形
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGLError("onDrawFrame opengl error")

            // 禁用顶点数组
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(textureCoordHandle)

            checkGLError("onDrawFrame opengl error")

            // 减少日志输出频率
//            frameListener?.onFrameAvailable(activeSurfaceTexture)
        } else {
            // 减少日志输出频率，仅在错误时输出
            if (frameCount % 60 == 0) {
                LogManager.d(TAG, "SurfaceTexture 或纹理ID 无效，跳过绘制")
            }
        }

        // 绘制屏幕区域边框
        if (shouldDrawBorder && screenCorners != null) {
            drawScreenBorder()
        }

        frameCount++
    }

    /**
     * 创建外部纹理
     */
    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textureId
    }

    /**
     * 编译着色器
     */
    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                LogManager.e(TAG, "Could not compile shader $shaderType:")
                LogManager.e(TAG, GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }

    /**
     * 创建 OpenGL 程序
     */
    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }

        var program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, pixelShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                LogManager.e(TAG, "Could not link program: ")
                LogManager.e(TAG, GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
        return program
    }

    /**
     * 获取用于摄像头预览的 SurfaceTexture
     */
    fun getSurfaceTexture(): SurfaceTexture? {
        return surfaceTexture
    }

    /**
     * 设置帧可用监听器
     */
    fun setOnFrameAvailableListener(listener: OnFrameAvailableListener?) {
        this.frameListener = listener
    }

    /**
     * 设置渲染器就绪监听器
     */
    fun setOnRendererReadyListener(listener: OnRendererReadyListener?) {
        this.rendererReadyListener = listener
    }


    /**
     * 绑定外部摄像头纹理进行渲染
     * 用于渲染来自独立SurfaceTexture的摄像头帧
     */
    fun bindExternalCameraTexture(externalTextureId: Int) {
        try {
            // 使用外部纹理ID替换内部纹理
            if (externalTextureId != 0) {
                textureID = externalTextureId
                LogManager.d(TAG, "绑定外部摄像头纹理: $externalTextureId")
            } else {
                LogManager.w(TAG, "外部纹理ID无效: $externalTextureId")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "绑定外部纹理失败: ${e.message}")
        }
    }
    
    /**
     * 设置外部SurfaceTexture用于纹理更新
     */
    fun setExternalSurfaceTexture(externalTexture: SurfaceTexture?) {
        synchronized(this) {
            externalSurfaceTexture = externalTexture
            if (externalTexture != null) {
                LogManager.d(TAG, "设置外部SurfaceTexture成功")
                // 设置帧可用监听器
                externalTexture.setOnFrameAvailableListener { texture ->
                    synchronized(this) {
                        updateSurface = true
                    }
                    frameListener?.onFrameAvailable(texture) 
                }
            } else {
                LogManager.d(TAG, "清除外部SurfaceTexture")
            }
        }
    }
    
    /**
     * 获取当前使用的纹理ID
     * 用于图像处理器等需要直接访问纹理的组件
     */
    fun getTextureId(): Int {
        return textureID
    }

    /**
     * 设置屏幕区域边框
     * @param corners 屏幕区域的四个角点坐标（相对于摄像头预览）
     *                顺序：左上角，左下角，右下角，右上角
     */
    fun setScreenBorder(corners: Array<org.opencv.core.Point>?) {
        screenCorners = corners
        shouldDrawBorder = corners != null
        
        if (corners != null) {
            // 创建线条顶点缓冲区 - 5个点形成闭合边框（4个角点 + 回到第一个点）
            val lineVertices = FloatArray(10) // 5个点，每个点2个坐标
            
            // 将OpenCV坐标转换为OpenGL坐标
            // corners顺序：[0]左上角，[1]左下角，[2]右下角，[3]右上角
            for (i in 0 until 4) {
                // 直接转换坐标，不应用额外旋转
                // OpenCV: 左上角(0,0)，x向右，y向下
                // OpenGL: 中心(0,0)，x向右，y向上，范围(-1,1)
                val x = (corners[i].x / targetCameraWidth * 2.0 - 1.0).toFloat()
                val y = (1.0 - corners[i].y / targetCameraHeight * 2.0).toFloat()
                
                lineVertices[i * 2] = x
                lineVertices[i * 2 + 1] = y
            }
            
            // 闭合边框 - 最后一个点回到第一个点
            lineVertices[8] = lineVertices[0]
            lineVertices[9] = lineVertices[1]
            
            // 创建顶点缓冲区
            lineBuffer = ByteBuffer.allocateDirect(lineVertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(lineVertices)
                    position(0)
                }
            
            LogManager.d(TAG, "设置屏幕边框，角点数量: ${corners.size}")
            // 输出调试信息
            val cornerNames = arrayOf("左上角", "左下角", "右下角", "右上角")
            for (i in 0 until 4) {
                LogManager.d(TAG, "${cornerNames[i]}: OpenCV(${corners[i].x}, ${corners[i].y}) -> OpenGL(${lineVertices[i*2]}, ${lineVertices[i*2+1]})")
            }
        } else {
            lineBuffer = null
            LogManager.d(TAG, "清除屏幕边框")
        }
    }

    /**
     * 绘制屏幕区域边框
     */
    private fun drawScreenBorder() {
        if (lineBuffer == null || lineProgram == 0) {
            return
        }

        try {
            // 启用线宽（如果支持）
            GLES20.glLineWidth(5.0f)
            
            // 使用线条着色器程序
            GLES20.glUseProgram(lineProgram)
            
            // 设置顶点坐标
            GLES20.glVertexAttribPointer(linePositionHandle, 2, GLES20.GL_FLOAT, false, 0, lineBuffer)
            GLES20.glEnableVertexAttribArray(linePositionHandle)
            
            // 设置变换矩阵
            GLES20.glUniformMatrix4fv(lineMvpMatrixHandle, 1, false, mvpMatrix, 0)
            
            // 设置红色
            GLES20.glUniform4f(lineColorHandle, 1.0f, 0.0f, 0.0f, 1.0f)
            
            // 绘制线条
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, 5)
            
            // 禁用顶点数组
            GLES20.glDisableVertexAttribArray(linePositionHandle)
            
            checkGLError("drawScreenBorder")
            
        } catch (e: Exception) {
            LogManager.e(TAG, "绘制屏幕边框失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 根据设备方向获取正确的纹理坐标
     * 确保摄像头内容在不同设备方向下正确显示而不变形
     */
    private fun getTextureCoords(): FloatArray {
        // 获取当前是否为竖屏
        val isPortrait = MainActivity.isPortrait()
        
        // OpenGL顶点顺序（TRIANGLE_STRIP）：左下、右下、左上、右上
        return if (isPortrait) {
            // 竖屏时：需要将摄像头输出逆时针旋转90度
            // 摄像头硬件输出1920x1080，在竖屏设备上需要旋转90度变成1080x1920的视觉效果
            floatArrayOf(
                0.0f, 0.0f,  
                1.0f, 0.0f, 
                0.0f, 1.0f, 
                1.0f, 1.0f
            )
        } else {
            // 横屏时：直接显示摄像头输出，不需要旋转
            // 摄像头硬件输出1920x1080，在横屏设备上直接显示
            floatArrayOf(
                0.0f, 1.0f, 
                0.0f, 0.0f, 
                1.0f, 1.0f, 
                1.0f, 0.0f
            )
        }
    }
    
    /**
     * 释放OpenGL资源
     * 必须在有效的OpenGL上下文中调用
     */
    fun release() {
        LogManager.d(TAG, "开始释放CameraGLRenderer资源")
        
        try {
            // 检查当前线程是否有有效的OpenGL上下文
            val glVersion = try {
                GLES20.glGetString(GLES20.GL_VERSION)
            } catch (e: Exception) {
                LogManager.w(TAG, "获取OpenGL版本失败，可能不在OpenGL上下文中: ${e.message}")
                null
            }
            
            if (glVersion == null) {
                LogManager.w(TAG, "没有有效的OpenGL上下文，跳过OpenGL资源释放")
                // 仍然释放SurfaceTexture
                surfaceTexture?.release()
                surfaceTexture = null
                externalSurfaceTexture = null
                LogManager.d(TAG, "CameraGLRenderer资源释放完成（跳过OpenGL资源）")
                return
            }
            
            LogManager.d(TAG, "在有效的OpenGL上下文中释放资源，OpenGL版本: $glVersion")
            
            // 释放着色器程序
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
                LogManager.d(TAG, "主着色器程序已释放")
            }
            
            if (lineProgram != 0) {
                GLES20.glDeleteProgram(lineProgram)
                lineProgram = 0
                LogManager.d(TAG, "线条着色器程序已释放")
            }
            
            // 释放纹理
            if (textureID != 0) {
                val textures = intArrayOf(textureID)
                GLES20.glDeleteTextures(1, textures, 0)
                textureID = 0
                LogManager.d(TAG, "摄像头纹理已释放")
            }
            
            // 检查OpenGL错误
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                LogManager.w(TAG, "释放CameraGLRenderer资源后发现OpenGL错误: $error")
            } else {
                LogManager.d(TAG, "CameraGLRenderer OpenGL资源释放成功，无错误")
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "释放CameraGLRenderer OpenGL资源时发生异常: ${e.message}")
            e.printStackTrace()
        }
        
        try {
            // 释放SurfaceTexture（不依赖OpenGL上下文）
            surfaceTexture?.release()
            surfaceTexture = null
            
            // 清除外部SurfaceTexture引用（不需要释放，由外部管理）
            externalSurfaceTexture = null
            
            // 清除监听器引用
            frameListener = null
            rendererReadyListener = null
            
            // 清除线条缓冲区
            lineBuffer = null
            screenCorners = null
            
            LogManager.d(TAG, "SurfaceTexture和引用已清理")
            
        } catch (e: Exception) {
            LogManager.e(TAG, "释放SurfaceTexture时发生异常: ${e.message}")
            e.printStackTrace()
        }
        
        LogManager.d(TAG, "CameraGLRenderer资源释放完成")
    }
} 