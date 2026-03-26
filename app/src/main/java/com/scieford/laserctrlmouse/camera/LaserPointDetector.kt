package com.scieford.laserctrlmouse.camera

import android.graphics.Bitmap
import android.graphics.Point as AndroidPoint
import android.media.Image
import android.util.Log
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.calib3d.Calib3d
import java.io.File
import java.nio.ByteBuffer
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CaptureResult
import android.graphics.ImageFormat
import com.scieford.laserctrlmouse.utils.LogManager
import org.opencv.android.Utils
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.FlannBasedMatcher
import java.util.concurrent.atomic.AtomicInteger

/**
 * 激光点检测工具类
 */
class LaserPointDetector {
    // 特征检测相关
    private lateinit var featureDetector: SIFT
    private lateinit var templateKeypoints: MatOfKeyPoint
    private lateinit var templateDescriptors: Mat
    private lateinit var templateImage: Mat
    private lateinit var testImage: Mat
    private lateinit var testTemplateBitMap: Bitmap
    private var screenCorners: MatOfPoint2f? = null
    private var perspectiveMatrix: Mat? = null
    
    // 屏幕检测参数
    companion object {
        private const val TAG = "LaserPointDetector"
        private const val MIN_MATCH_COUNT = 100  // 最小匹配点数
        
        // 亮度阈值 - 超过此阈值的点视为激光点
        private const val BRIGHTNESS_THRESHOLD = 200
        
        // 激光点面积阈值 - 激光点的连通区域面积需要在此范围内
        private const val MIN_LASER_AREA = 5  // 最小5个像素
        private const val MAX_LASER_AREA = 500 // 最大500个像素
    }
    
    /**
     * 激光点检测结果监听器
     */
    interface LaserPointListener {
        /**
         * 当检测到激光点时回调
         * @param x 激光点X坐标
         * @param y 激光点Y坐标
         * @param brightness 亮度值
         * @param timestamp 时间戳
         */
        fun onLaserPointDetected(x: Int, y: Int, brightness: Int, timestamp: Long)
    }
    
    // 激光点监听器
    private var listener: LaserPointListener? = null
    
    // 上一次检测结果
    private var lastDetectedPoint: AndroidPoint? = null
    
    // 抖动过滤参数
    private val filterWindowSize = 3
    private val recentPoints = ArrayList<AndroidPoint>(filterWindowSize)
    
    // 统计计数器
    private val processedFrameCount = AtomicInteger(0)
    
    /**
     * 初始化ORB检测器和模板图像
     * @param templateImagePath 模板图像路径
     * @param screenWidth 实际屏幕宽度
     * @param screenHeight 实际屏幕高度
     */
    fun initScreenDetector(templateImagePath: String, screenWidth: Int, screenHeight: Int) {
        LogManager.d(TAG, "开始初始化ORB检测器和模板图像，模板路径: $templateImagePath")
        
        // 初始化ORB特征检测器
        LogManager.d(TAG, "创建ORB特征检测器...")
//        featureDetector = SIFT.create(500, 3, 0.04)
        featureDetector = SIFT.create()
//        featureDetector = ORB.create(500, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20)
        
        // 加载模板图像
        LogManager.d(TAG, "加载模板图像: $templateImagePath")
        val file = File(templateImagePath)
        if (!file.exists()) {
            LogManager.e(TAG, "模板图像文件不存在: $templateImagePath")
            throw IllegalArgumentException("模板图像文件不存在: $templateImagePath")
        }
        LogManager.d(TAG, "模板图像文件大小: ${file.length()} 字节")
        
        templateImage = Imgcodecs.imread(templateImagePath)
        if (templateImage.empty()) {
            LogManager.e(TAG, "无法加载模板图像，imread返回空Mat")
            throw IllegalArgumentException("无法加载模板图像: $templateImagePath")
        }
        
        LogManager.d(TAG, "模板图像加载成功，尺寸: ${templateImage.width()}x${templateImage.height()}, 通道数: ${templateImage.channels()}")
        Imgproc.cvtColor(templateImage, templateImage, Imgproc.COLOR_BGR2RGB) // 再转为RGB
        
        // 计算模板图像的特征点和描述子
        templateKeypoints = MatOfKeyPoint()
        templateDescriptors = Mat()
        featureDetector.detectAndCompute(templateImage, Mat(), templateKeypoints, templateDescriptors)
//        templateDescriptors.convertTo(templateDescriptors, CvType.CV_32F)
        
        LogManager.d(TAG, "模板图像特征点数量: ${templateKeypoints.toArray().size}")

        testTemplateBitMap = Bitmap.createBitmap(templateImage.width(), templateImage.height(), Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(templateImage, testTemplateBitMap)

        // 设置实际屏幕尺寸
        val (screenWidth, screenHeight) = com.scieford.laserctrlmouse.MainActivity.getRealScreenSize()
        val realScreenSize = Size(screenWidth.toDouble(), screenHeight.toDouble())
        LogManager.d(TAG, "设置实际屏幕尺寸: ${realScreenSize.width}x${realScreenSize.height}")

    }
    
    /**
     * 设置激光点监听器
     */
    fun setListener(listener: LaserPointListener?) {
        this.listener = listener
    }

    /**
     * 检查激光点检测器是否已正确初始化
     * @return true如果屏幕角点和透视矩阵都已设置，false否则
     */
    fun isInitialized(): Boolean {
        val hasCornersAndMatrix = screenCorners != null && perspectiveMatrix != null
        val matrixNotEmpty = if (perspectiveMatrix != null) !perspectiveMatrix!!.empty() else false
        val initialized = hasCornersAndMatrix && matrixNotEmpty
        
        if (!initialized) {
            LogManager.d(TAG, "激光点检测器初始化状态检查: screenCorners=${screenCorners != null}, perspectiveMatrix=${perspectiveMatrix != null}, matrixNotEmpty=$matrixNotEmpty")
        }
        return initialized
    }

    /**
     * 屏幕区域检测结果数据类
     */
    data class ScreenDetectionResult(
        val success: Boolean,
        val corners: Array<Point>? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ScreenDetectionResult

            if (success != other.success) return false
            if (corners != null) {
                if (other.corners == null) return false
                if (!corners.contentEquals(other.corners)) return false
            } else if (other.corners != null) return false

            return true
        }

        override fun hashCode(): Int {
            var result = success.hashCode()
            result = 31 * result + (corners?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * 检测图像中的屏幕区域（返回详细结果）
     * @param imageMat 图像Mat对象
     * @return ScreenDetectionResult 包含检测结果和屏幕角点坐标
     */
    fun detectScreenAreaWithResult(imageMat: Mat): ScreenDetectionResult {
        try {
            LogManager.d(TAG, "开始屏幕区域检测，图像尺寸: ${imageMat.width()}x${imageMat.height()}")
            // 将Mat对象转换为Bitmap
            val bitmap = Bitmap.createBitmap(imageMat.cols(), imageMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(imageMat, bitmap)
            LogManager.d(TAG, "成功将Mat转换为Bitmap，尺寸: ${bitmap.width}x${bitmap.height}")


            
            // 检测当前图像的特征点和描述子
            val kp2 = MatOfKeyPoint()
            val des2 = Mat()
            featureDetector.detectAndCompute(imageMat, Mat(), kp2, des2)

            if (kp2.empty() || des2.empty()) {
                LogManager.w(TAG, "当前图像未检测到特征点或描述子为空")
                return ScreenDetectionResult(false)
            }
            
            // 使用FLANN匹配器进行特征匹配
            val FLANN_INDEX_KDTREE = 1
            val indexParams = MatOfInt(FLANN_INDEX_KDTREE, 5) // trees = 5
            val searchParams = MatOfInt(0, 50) // checks = 50

//            val flannMatcher = BFMatcher.create(Core.NORM_L2, false) // 使用BFMatcher替代FLANN
            val flannMatcher = FlannBasedMatcher.create() // 使用BFMatcher替代FLANN
            val matches = ArrayList<MatOfDMatch>()
            
            try {
                flannMatcher.knnMatch(templateDescriptors, des2, matches, 2)
                LogManager.d(TAG, "特征匹配完成，原始匹配点数量: ${matches.size}")
            } catch (e: Exception) {
                LogManager.e(TAG, "特征匹配失败: ${e.message}")
                e.printStackTrace()
                return ScreenDetectionResult(false)
            }
            
            if (matches.isEmpty()) {
                LogManager.w(TAG, "未找到任何匹配点")
                return ScreenDetectionResult(false)
            }
            
            // 应用Lowe's ratio测试筛选好的匹配点
            val goodMatches = ArrayList<DMatch>()
            for (match in matches) {
                val matchArray = match.toArray()
                if (matchArray.size >= 2 && matchArray[0].distance < 0.75 * matchArray[1].distance) {
                    goodMatches.add(matchArray[0])
                }
            }
            
            LogManager.d(TAG, "应用Lowe's ratio测试后的匹配点数量: ${goodMatches.size}/$MIN_MATCH_COUNT")
            
            if (goodMatches.size > MIN_MATCH_COUNT) {
                // 提取匹配点的坐标
                val srcPoints = ArrayList<Point>()
                val dstPoints = ArrayList<Point>()
                
                val templateKeypointsArray = templateKeypoints.toArray()
                val sceneKeypointsArray = kp2.toArray()
                
                for (match in goodMatches) {
                    srcPoints.add(templateKeypointsArray[match.queryIdx].pt)
                    dstPoints.add(sceneKeypointsArray[match.trainIdx].pt)
                }
                
                val srcPointsMat = MatOfPoint2f()
                srcPointsMat.fromList(srcPoints)
                
                val dstPointsMat = MatOfPoint2f()
                dstPointsMat.fromList(dstPoints)
                
                // 计算单应性矩阵
                val mask = Mat()
                val M = Calib3d.findHomography(srcPointsMat, dstPointsMat, Calib3d.RANSAC, 5.0, mask)
                
                if (M == null || M.empty()) {
                    LogManager.w(TAG, "无法计算单应性矩阵，匹配点不足或质量差")
                    return ScreenDetectionResult(false)
                }
                
                // 计算模板图像四个角点在场景图像中的位置
                val h = templateImage.height()
                val w = templateImage.width()
                val pts = MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(0.0, h - 1.0),
                    Point(w - 1.0, h - 1.0),
                    Point(w - 1.0, 0.0)
                )
                
                // 进行透视变换
                screenCorners = MatOfPoint2f()
                Core.perspectiveTransform(pts, screenCorners, M)
                
                // 获取坐标转换矩阵
                val (screenWidth, screenHeight) = com.scieford.laserctrlmouse.MainActivity.getRealScreenSize()
                val realScreenSize = Size(screenWidth.toDouble(), screenHeight.toDouble())
                val realRect = MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(0.0, realScreenSize.height),
                    Point(realScreenSize.width, realScreenSize.height),
                    Point(realScreenSize.width, 0.0)
                )
                
                perspectiveMatrix = Imgproc.getPerspectiveTransform(screenCorners, realRect)
                
                // 获取变换后的角点坐标
                val cornersArray = screenCorners!!.toArray()
                LogManager.d(TAG, "变换后的屏幕角点坐标:")
                for (i in cornersArray.indices) {
                    LogManager.d(TAG, "角点$i: (${cornersArray[i].x}, ${cornersArray[i].y})")
                }
                
                LogManager.d(TAG, "检测到屏幕区域，透视变换矩阵计算成功")
                return ScreenDetectionResult(true, cornersArray)
            } else {
                LogManager.d(TAG, "未找到足够的匹配点: ${goodMatches.size}/$MIN_MATCH_COUNT")
                return ScreenDetectionResult(false)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "屏幕区域检测失败: ${e.message}")
            e.printStackTrace()
            return ScreenDetectionResult(false)
        }
    }

    /**
     * 检测图像中的屏幕区域（保持原有接口兼容性）
     * 参考Python版本的screen_tracker.py中的detect_screen_area方法实现
     * @param imageMat 图像Mat对象
     * @return 是否成功检测到屏幕区域
     */
    fun detectScreenArea(imageMat: Mat): Boolean {
        return detectScreenAreaWithResult(imageMat).success
    }
    
    // processImageData方法已移除，不再使用
    
    /**
     * 检测激光点
     * 参考Python版本的screen_tracker.py中的detect_laser_point方法实现
     * @param imageMat 图像Mat对象
     * @param timestamp 时间戳
     * @return 是否成功检测到激光点
     */
    fun detectLaserPoint(imageMat: Mat, timestamp: Long): Boolean {
        if (perspectiveMatrix == null) {
            LogManager.w(TAG, "透视变换矩阵未初始化，请先调用detectScreenArea")
            return false
        }
        
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 将RGB图像转换为HSV格式，以便更容易检测红色
            val time1 = System.currentTimeMillis()
            val hsvMat = Mat()
            Imgproc.cvtColor(imageMat, hsvMat, Imgproc.COLOR_RGB2BGR) // 先转为BGR
            Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_BGR2HSV) // 再转为HSV
            val time1_elapsed = System.currentTimeMillis() - time1
            LogManager.d(TAG, "步骤1 - 图像转HSV: ${time1_elapsed}ms")
            
            // 2. 定义红色的HSV范围（红色在HSV空间中有两个范围）
            val time2 = System.currentTimeMillis()
            val lowerRed1 = Scalar(0.0, 100.0, 20.0)
            val upperRed1 = Scalar(10.0, 255.0, 255.0)
            
            val lowerRed2 = Scalar(160.0, 100.0, 20.0)
            val upperRed2 = Scalar(179.0, 255.0, 255.0)
            val time2_elapsed = System.currentTimeMillis() - time2
            LogManager.d(TAG, "步骤2 - 定义HSV范围: ${time2_elapsed}ms")
            
            // 3. 创建掩码
            val time3 = System.currentTimeMillis()
            val lowerMask = Mat()
            val upperMask = Mat()
            Core.inRange(hsvMat, lowerRed1, upperRed1, lowerMask)
            Core.inRange(hsvMat, lowerRed2, upperRed2, upperMask)
            val time3_elapsed = System.currentTimeMillis() - time3
            LogManager.d(TAG, "步骤3 - 创建掩码: ${time3_elapsed}ms")
            
            // 4. 合并两个掩码
            val time4 = System.currentTimeMillis()
            val mask = Mat()
            Core.add(lowerMask, upperMask, mask)
            val time4_elapsed = System.currentTimeMillis() - time4
            LogManager.d(TAG, "步骤4 - 合并掩码: ${time4_elapsed}ms")
            
            // 5. 应用掩码提取红色区域
            val time5 = System.currentTimeMillis()
            val redRegion = Mat()
            Core.bitwise_and(hsvMat, hsvMat, redRegion, mask)
            val time5_elapsed = System.currentTimeMillis() - time5
            LogManager.d(TAG, "步骤5 - 提取红色区域: ${time5_elapsed}ms")
            
            // 6. 转换为灰度图并找到最亮点
            val time6 = System.currentTimeMillis()
            val gray = Mat()
            Imgproc.cvtColor(redRegion, gray, Imgproc.COLOR_BGR2GRAY)
            
            val minMaxLocResult = Core.minMaxLoc(gray)
            val maxVal = minMaxLocResult.maxVal
            val maxLoc = minMaxLocResult.maxLoc
            val time6_elapsed = System.currentTimeMillis() - time6
            LogManager.d(TAG, "步骤6 - 灰度图转换和查找最亮点: ${time6_elapsed}ms")
            
            // 7. 检查亮度是否超过阈值
            val time7 = System.currentTimeMillis()
            if (maxVal < BRIGHTNESS_THRESHOLD) {
                LogManager.d(TAG, "未检测到足够亮度的激光点，最大亮度: $maxVal")
                
                // 释放资源
                hsvMat.release()
                lowerMask.release()
                upperMask.release()
                mask.release()
                redRegion.release()
                gray.release()
                
                val time7_elapsed = System.currentTimeMillis() - time7
                LogManager.d(TAG, "步骤7 - 亮度检查(未通过): ${time7_elapsed}ms")
                return false
            }
            val time7_elapsed = System.currentTimeMillis() - time7
            LogManager.d(TAG, "步骤7 - 亮度检查: ${time7_elapsed}ms")
            
            // 8. 将检测到的点坐标转换为屏幕坐标
            val time8 = System.currentTimeMillis()
            val p = maxLoc
            val px = (perspectiveMatrix!!.get(0, 0)[0] * p.x + 
                     perspectiveMatrix!!.get(0, 1)[0] * p.y + 
                     perspectiveMatrix!!.get(0, 2)[0]) / 
                    (perspectiveMatrix!!.get(2, 0)[0] * p.x + 
                     perspectiveMatrix!!.get(2, 1)[0] * p.y + 
                     perspectiveMatrix!!.get(2, 2)[0])
            
            val py = (perspectiveMatrix!!.get(1, 0)[0] * p.x + 
                     perspectiveMatrix!!.get(1, 1)[0] * p.y + 
                     perspectiveMatrix!!.get(1, 2)[0]) / 
                    (perspectiveMatrix!!.get(2, 0)[0] * p.x + 
                     perspectiveMatrix!!.get(2, 1)[0] * p.y + 
                     perspectiveMatrix!!.get(2, 2)[0])
            
            // 坐标转换 - 直接使用py计算，不再颠倒y轴方向
            val screenX = px.toInt()
            val screenY = py.toInt()
            val time8_elapsed = System.currentTimeMillis() - time8
            LogManager.d(TAG, "步骤8 - 坐标转换: ${time8_elapsed}ms")
            
            // 9. 检查坐标是否在屏幕范围内
            val time9 = System.currentTimeMillis()
            val (screenWidth, screenHeight) = com.scieford.laserctrlmouse.MainActivity.getRealScreenSize()
            val realScreenSize = Size(screenWidth.toDouble(), screenHeight.toDouble())
            if (screenX < 0 || screenX > realScreenSize.width.toInt() || 
                screenY < 0 || screenY > realScreenSize.height.toInt()) {
                LogManager.d(TAG, "激光点超出屏幕范围: ($screenX, $screenY)")
                
                // 释放资源
                hsvMat.release()
                lowerMask.release()
                upperMask.release()
                mask.release()
                redRegion.release()
                gray.release()
                
                val time9_elapsed = System.currentTimeMillis() - time9
                LogManager.d(TAG, "步骤9 - 范围检查(未通过): ${time9_elapsed}ms")
                return false
            }
            val time9_elapsed = System.currentTimeMillis() - time9
            LogManager.d(TAG, "步骤9 - 范围检查: ${time9_elapsed}ms")
            
            // 10. 应用抖动过滤
            val time10 = System.currentTimeMillis()
            val filteredPoint = applyJitterFilter(AndroidPoint(screenX, screenY))
            val time10_elapsed = System.currentTimeMillis() - time10
            LogManager.d(TAG, "步骤10 - 抖动过滤: ${time10_elapsed}ms")
            
            // 11. 输出检测结果并通知监听器
            val time11 = System.currentTimeMillis()
            LogManager.d(TAG, "检测到激光点: 原始位置(${p.x}, ${p.y}), " +
                      "屏幕位置(${filteredPoint.x}, ${filteredPoint.y}), 亮度: $maxVal")
            
            // 保存检测结果
            lastDetectedPoint = filteredPoint
            
            // 通知监听器
            listener?.onLaserPointDetected(filteredPoint.x, filteredPoint.y, maxVal.toInt(), timestamp)
            val time11_elapsed = System.currentTimeMillis() - time11
            LogManager.d(TAG, "步骤11 - 结果通知: ${time11_elapsed}ms")
            
            // 12. 释放资源
            val time12 = System.currentTimeMillis()
            hsvMat.release()
            lowerMask.release()
            upperMask.release()
            mask.release()
            redRegion.release()
            gray.release()
            val time12_elapsed = System.currentTimeMillis() - time12
            LogManager.d(TAG, "步骤12 - 释放资源: ${time12_elapsed}ms")
            
            val processingTime = System.currentTimeMillis() - startTime
            LogManager.d(TAG, "激光点检测总处理时间: ${processingTime}ms")
            
            return true
        } catch (e: Exception) {
            LogManager.e(TAG, "激光点检测失败: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 应用抖动过滤 - 使用最近几个点的平均位置
     */
    private fun applyJitterFilter(newPoint: AndroidPoint): AndroidPoint {
        // 将新点添加到列表
        recentPoints.add(newPoint)
        
        // 保持队列大小
        if (recentPoints.size > filterWindowSize) {
            recentPoints.removeAt(0)
        }
        
        // 如果点数太少，直接返回当前点
        if (recentPoints.size < 2) {
            return newPoint
        }
        
        // 计算平均位置
        var sumX = 0
        var sumY = 0
        
        for (point in recentPoints) {
            sumX += point.x
            sumY += point.y
        }
        
        val avgX = sumX / recentPoints.size
        val avgY = sumY / recentPoints.size
        
        return AndroidPoint(avgX, avgY)
    }
    
    /**
     * 将图像坐标转换为屏幕坐标
     * @param x 图像中的x坐标
     * @param y 图像中的y坐标
     * @return 转换后的屏幕坐标，如果转换失败则返回null
     */
    fun convertToScreenCoordinates(x: Int, y: Int): AndroidPoint? {
        if (screenCorners == null || perspectiveMatrix == null) {
            LogManager.w(TAG, "屏幕角点或透视矩阵未初始化，无法进行坐标转换")
            return null
        }
        val (screenWidth, screenHeight) = com.scieford.laserctrlmouse.MainActivity.getRealScreenSize()
        val realScreenSize = Size(screenWidth.toDouble(), screenHeight.toDouble())
        
        try {
            // 获取四个屏幕角点，根据实际坐标值重新确定角点对应关系
            val corners = screenCorners!!.toArray()
            // 根据实际坐标分析：
            // 角点0: (1071, 144) → 右上角
            // 角点1: (1138, 656) → 右下角  
            // 角点2: (127, 642) → 左下角
            // 角点3: (211, 132) → 左上角
            val leftTop = corners[0]     // 右上 (1920, 0)
            val leftBottom = corners[1]  // 右下 (1920, 1080)
            val rightBottom = corners[2]   // 左下 (0, 1080)
            val rightTop = corners[3]      // 左上 (0, 0)
            

            // 应用变换
            val px = (perspectiveMatrix!!.get(0, 0)[0] * x +
                     perspectiveMatrix!!.get(0, 1)[0] * y +
                     perspectiveMatrix!!.get(0, 2)[0]) /
                    (perspectiveMatrix!!.get(2, 0)[0] * x +
                     perspectiveMatrix!!.get(2, 1)[0] * y +
                     perspectiveMatrix!!.get(2, 2)[0])

            val py = (perspectiveMatrix!!.get(1, 0)[0] * x +
                     perspectiveMatrix!!.get(1, 1)[0] * y +
                     perspectiveMatrix!!.get(1, 2)[0]) /
                    (perspectiveMatrix!!.get(2, 0)[0] * x +
                     perspectiveMatrix!!.get(2, 1)[0] * y +
                     perspectiveMatrix!!.get(2, 2)[0])
            

            // 转换为整数坐标
            val screenX = px.toInt()
            val screenY = py.toInt()
            
            LogManager.d(TAG, "GPU角点变换: 输入($x, $y) -> 屏幕坐标($screenX, $screenY), 实际屏幕尺寸：${realScreenSize.width} x ${realScreenSize.height} ")
            LogManager.d(TAG, "角点参考: 左上${leftTop}, 左下${leftBottom}, 右下${rightBottom}, 右上${rightTop}")
            
            // 检查坐标是否在屏幕范围内
            if (screenX < 0 || screenX > realScreenSize.width.toInt() || 
                screenY < 0 || screenY > realScreenSize.height.toInt()) {
                LogManager.d(TAG, "激光点超出屏幕范围: ($screenX, $screenY)")
                return null
            }
            
            return AndroidPoint(screenX, screenY)
        } catch (e: Exception) {
            LogManager.e(TAG, "坐标转换失败: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * 手动设置屏幕角点坐标（用于调试模式）
     * @param leftTop 左上角点
     * @param leftBottom 左下角点
     * @param rightBottom 右下角点
     * @param rightTop 右上角点
     */
    fun setScreenCorners(leftTop: Point, leftBottom: Point, rightBottom: Point, rightTop: Point) {
        val (screenWidth, screenHeight) = com.scieford.laserctrlmouse.MainActivity.getRealScreenSize()
        val realScreenSize = Size(screenWidth.toDouble(), screenHeight.toDouble())
        
        try {
            // 创建角点数组，按照OpenCV的要求顺序排列
            screenCorners = MatOfPoint2f(leftTop, leftBottom, rightBottom, rightTop)
            
            LogManager.d(TAG, "手动设置屏幕角点:")
            LogManager.d(TAG, "左上角: (${leftTop.x}, ${leftTop.y})")
            LogManager.d(TAG, "左下角: (${leftBottom.x}, ${leftBottom.y})")
            LogManager.d(TAG, "右下角: (${rightBottom.x}, ${rightBottom.y})")
            LogManager.d(TAG, "右上角: (${rightTop.x}, ${rightTop.y})")
            
            // 计算透视变换矩阵
            updatePerspectiveMatrix()
            
        } catch (e: Exception) {
            LogManager.e(TAG, "设置屏幕角点失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 更新透视变换矩阵
     * 当屏幕分辨率改变时需要调用此方法重新计算透视变换矩阵
     */
    fun updatePerspectiveMatrix() {
        if (screenCorners == null) {
            LogManager.w(TAG, "屏幕角点未设置，无法更新透视变换矩阵")
            return
        }
        
        val (screenWidth, screenHeight) = com.scieford.laserctrlmouse.MainActivity.getRealScreenSize()
        val realScreenSize = Size(screenWidth.toDouble(), screenHeight.toDouble())
        
        try {
            // 计算透视变换矩阵
            val realRect = MatOfPoint2f(
                Point(0.0, 0.0),                           // 左上角对应屏幕坐标(0,0)
                Point(0.0, realScreenSize.height),       // 左下角对应屏幕坐标(0,height)
                Point(realScreenSize.width, realScreenSize.height),   // 右下角对应屏幕坐标(width,height)
                Point(realScreenSize.width, 0.0)         // 右上角对应屏幕坐标(width,0)
            )
            
            perspectiveMatrix = Imgproc.getPerspectiveTransform(screenCorners, realRect)
            
            if (perspectiveMatrix != null && !perspectiveMatrix!!.empty()) {
                LogManager.d(TAG, "透视变换矩阵更新成功，新的屏幕尺寸: ${realScreenSize.width}x${realScreenSize.height}")
            } else {
                LogManager.e(TAG, "透视变换矩阵更新失败")
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "更新透视变换矩阵失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
}