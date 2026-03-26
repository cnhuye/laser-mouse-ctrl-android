package com.scieford.laserctrlmouse.camera
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import android.util.Log
import com.scieford.laserctrlmouse.utils.LogManager
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EGL图像转换器 - 用于从Surface获取图像数据
 * 
 * 性能优化建议：
 * 1. 使用 readPixelsToMatFast() - 先读取RGBA再转RGB，确保兼容性
 * 2. 使用 readPixelsToMatUltraFast() - 降采样，大幅减少数据量
 * 3. 使用 readPixelsToMatWithSkip() - 跳帧处理，降低处理频率
 * 4. 考虑使用Camera2的ImageReader替代EGL方式获得更好性能
 * 
 * 对于5ms以下的性能要求，建议：
 * - 使用 readPixelsToMatUltraFast(0.25f) 进行4倍降采样
 * - 使用 readPixelsToMatWithSkip(4) 每5帧处理一次
 * - 考虑在后台线程异步处理
 */

class EGLImageConverter {

    private var eGLDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eGLSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var width: Int = 0
    private var height: Int = 0
    private var pixelBuffer: ByteBuffer? = null

    fun init(surface: Surface, width: Int, height: Int) {
        this.width = width
        this.height = height

        // 释放之前的资源
        release()

        // 重新初始化 EGL 和像素缓冲区
        initEGL(surface)
        pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    fun initRGB(surface: Surface, width: Int, height: Int) {
        // 对于优化版本，直接调用标准初始化
        init(surface, width, height)
    }

    private fun initEGL(surface: Surface) {
        eGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed ${EGL14.eglGetError()}")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eGLDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed ${EGL14.eglGetError()}")
        }

        val configAttribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eGLDisplay, configAttribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw IllegalArgumentException("eglChooseConfig failed ${EGL14.eglGetError()}")
        }

        val contextAttribList = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eGLContext = EGL14.eglCreateContext(eGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribList, 0)
        if (eGLContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed ${EGL14.eglGetError()}")
        }

        val surfaceAttribList = intArrayOf(
            EGL14.EGL_NONE
        )
        eGLSurface = EGL14.eglCreateWindowSurface(eGLDisplay, configs[0], surface, surfaceAttribList, 0)
        if (eGLSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed ${EGL14.eglGetError()}")
        }

        if (!EGL14.eglMakeCurrent(eGLDisplay, eGLSurface, eGLSurface, eGLContext)) {
            throw RuntimeException("eglMakeCurrent failed ${EGL14.eglGetError()}")
        }
    }

    fun readPixelsToMat(): Mat? {
        // 检查 EGL 上下文是否有效
        if (eGLDisplay == EGL14.EGL_NO_DISPLAY || 
            eGLContext == EGL14.EGL_NO_CONTEXT || 
            eGLSurface == EGL14.EGL_NO_SURFACE) {
            LogManager.e("EGLImageConverter", "EGL 上下文未正确初始化")
            return null
        }
        
        if (width <= 0 || height <= 0) {
            LogManager.e("EGLImageConverter", "无效的尺寸：${width}x${height}")
            return null
        }
        
        pixelBuffer?.let { buffer ->
            buffer.rewind()
            
            // 确保 EGL 上下文当前
            if (!EGL14.eglMakeCurrent(eGLDisplay, eGLSurface, eGLSurface, eGLContext)) {
                LogManager.e("EGLImageConverter", "无法设置 EGL 上下文为当前")
                return null
            }
            
            // 读取像素数据
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            
            // 检查 OpenGL 错误
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                LogManager.e("EGLImageConverter", "glReadPixels 失败，错误代码：$error")
                return null
            }

            val mat = Mat(height, width, CvType.CV_8UC4)
            buffer.rewind()
            
            val expectedSize = width * height * 4
            val actualSize = buffer.remaining()
            
            LogManager.d("EGLImageConverter", "尺寸检查：${width}x${height}, 期望大小：$expectedSize, 实际大小：$actualSize")
            
            if (actualSize != expectedSize) {
                LogManager.e("EGLImageConverter", "数据大小不匹配：期望 $expectedSize，实际 $actualSize")
                return null
            }
            
            // 创建大小匹配的字节数组
            val byteArray = ByteArray(actualSize)
            buffer.get(byteArray)
            
            try {
                mat.put(0, 0, byteArray)
                LogManager.d("EGLImageConverter", "成功创建 Mat：${mat.width()}x${mat.height()}")
                return mat
            } catch (e: Exception) {
                LogManager.e("EGLImageConverter", "创建 Mat 失败：${e.message}")
                mat.release()
                return null
            }
        }
        
        LogManager.e("EGLImageConverter", "pixelBuffer 为 null")
        return null
    }

    // 优化版本：使用RGBA格式确保兼容性，然后转换为RGB
    fun readPixelsToMatFast(): Mat? {
        // 检查 EGL 上下文是否有效
        if (eGLDisplay == EGL14.EGL_NO_DISPLAY || 
            eGLContext == EGL14.EGL_NO_CONTEXT || 
            eGLSurface == EGL14.EGL_NO_SURFACE) {
            LogManager.e("EGLImageConverter", "EGL 上下文未正确初始化")
            return null
        }
        
        if (width <= 0 || height <= 0) {
            LogManager.e("EGLImageConverter", "无效的尺寸：${width}x${height}")
            return null
        }
        
        pixelBuffer?.let { buffer ->
            val startTime = System.currentTimeMillis()
            
            // 确保 EGL 上下文当前
            if (!EGL14.eglMakeCurrent(eGLDisplay, eGLSurface, eGLSurface, eGLContext)) {
                LogManager.e("EGLImageConverter", "无法设置 EGL 上下文为当前")
                return null
            }
            
            val time1 = System.currentTimeMillis()
            
            buffer.clear() // 清除位置和限制
            
            // 使用RGBA格式确保兼容性（GL_RGB在某些设备不稳定）
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            
            val time2 = System.currentTimeMillis()
            LogManager.d("EGLImageConverter", "glReadPixels 耗时: ${time2 - time1}ms")
            
            // 检查 OpenGL 错误
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                LogManager.e("EGLImageConverter", "glReadPixels 失败，错误代码：$error")
                return null
            }

            buffer.flip() // 准备读取
            
            val expectedSize = width * height * 4 // RGBA = 4 channels
            val actualSize = buffer.remaining()
            
            if (actualSize != expectedSize) {
                LogManager.e("EGLImageConverter", "数据大小不匹配：期望 $expectedSize，实际 $actualSize")
                return null
            }
            
            val time3 = System.currentTimeMillis()
            
            try {
                // 创建RGBA Mat
                val rgbaMat = Mat(height, width, CvType.CV_8UC4)
                val byteArray = ByteArray(actualSize)
                buffer.get(byteArray)
                rgbaMat.put(0, 0, byteArray)
                
                // 转换为RGB格式（减少25%内存使用）
                val rgbMat = Mat()
                Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
                rgbaMat.release() // 释放RGBA Mat
                
                val time4 = System.currentTimeMillis()
                LogManager.d("EGLImageConverter", "数据处理耗时: ${time4 - time3}ms")
                LogManager.d("EGLImageConverter", "快速方法总耗时: ${time4 - startTime}ms")
                
                return rgbMat
            } catch (e: Exception) {
                LogManager.e("EGLImageConverter", "创建 Mat 失败：${e.message}")
                return null
            }
        }
        
        LogManager.e("EGLImageConverter", "pixelBuffer 为 null")
        return null
    }

    // 超高效版本：正确的降采样实现
    fun readPixelsToMatUltraFast(scaleFactor: Float = 0.5f): Mat? {
        // 检查 EGL 上下文是否有效
        if (eGLDisplay == EGL14.EGL_NO_DISPLAY || 
            eGLContext == EGL14.EGL_NO_CONTEXT || 
            eGLSurface == EGL14.EGL_NO_SURFACE) {
            return null
        }
        
        if (width <= 0 || height <= 0) {
            return null
        }
        
        pixelBuffer?.let { buffer ->
            val startTime = System.currentTimeMillis()
            
            // 确保 EGL 上下文当前
            if (!EGL14.eglMakeCurrent(eGLDisplay, eGLSurface, eGLSurface, eGLContext)) {
                LogManager.e("EGLImageConverter", "无法设置 EGL 上下文为当前")
                return null
            }
            
            buffer.clear()
            
            // 读取完整的RGBA像素数据
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            
            val time1 = System.currentTimeMillis()
            
            // 检查 OpenGL 错误
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                LogManager.e("EGLImageConverter", "glReadPixels 失败，错误代码：$error")
                return null
            }
            
            buffer.flip()
            
            try {
                val expectedSize = width * height * 4
                val actualSize = buffer.remaining()
                
                if (actualSize != expectedSize) {
                    LogManager.e("EGLImageConverter", "数据大小不匹配：期望 $expectedSize，实际 $actualSize")
                    return null
                }
                
                // 创建完整尺寸的RGBA Mat
                val fullMat = Mat(height, width, CvType.CV_8UC4)
                val byteArray = ByteArray(actualSize)
                buffer.get(byteArray)
                fullMat.put(0, 0, byteArray)
                
                val time2 = System.currentTimeMillis()
                
                // 计算缩放后的尺寸
                val scaledWidth = (width * scaleFactor).toInt()
                val scaledHeight = (height * scaleFactor).toInt()
                
                // 使用OpenCV进行高效的图像缩放
                val scaledMat = Mat()
                val size = org.opencv.core.Size(scaledWidth.toDouble(), scaledHeight.toDouble())
                Imgproc.resize(fullMat, scaledMat, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
                
                // 转换为RGB格式
                val rgbMat = Mat()
                Imgproc.cvtColor(scaledMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
                
                // 释放中间Mat
                fullMat.release()
                scaledMat.release()
                
                val time3 = System.currentTimeMillis()
                LogManager.d("EGLImageConverter", "超高效版本总耗时: ${time3 - startTime}ms, 尺寸: ${scaledWidth}x${scaledHeight}")
                LogManager.d("EGLImageConverter", "读取耗时: ${time1 - startTime}ms, 缩放处理耗时: ${time3 - time2}ms")
                
                return rgbMat
            } catch (e: Exception) {
                LogManager.e("EGLImageConverter", "超高效版本失败：${e.message}")
                e.printStackTrace()
                return null
            }
        }
        
        return null
    }

    // 跳帧处理版本：不是每帧都处理
    private var frameSkipCounter = 0
    fun readPixelsToMatWithSkip(skipFrames: Int = 2): Mat? {
        frameSkipCounter++
        if (frameSkipCounter <= skipFrames) {
            return null // 跳过这一帧
        }
        frameSkipCounter = 0
        return readPixelsToMatFast()
    }

    // 异步版本：在后台线程处理
    fun readPixelsToMatAsync(callback: (Mat?) -> Unit) {
        Thread {
            val mat = readPixelsToMatFast()
            callback(mat)
        }.start()
    }

    fun release() {
        EGL14.eglMakeCurrent(eGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eGLDisplay, eGLSurface)
        EGL14.eglDestroyContext(eGLDisplay, eGLContext)
        EGL14.eglTerminate(eGLDisplay)

        eGLDisplay = EGL14.EGL_NO_DISPLAY
        eGLContext = EGL14.EGL_NO_CONTEXT
        eGLSurface = EGL14.EGL_NO_SURFACE
        pixelBuffer = null
    }
}