package com.scieford.laserctrlmouse.settings

import android.content.Context
import com.scieford.laserctrlmouse.R

/**
 * 设置数据类
 */
data class SettingsData(
    val language: Language = Language.ENGLISH,
    val frameRateMode: FrameRateMode = FrameRateMode.NORMAL,
    val selectedCameraId: String = "0",
    val resolution: Resolution = Resolution.VGA_640x480,
    val exposureTimeMs: Float = 4.0f,
    val isoValue: Int = 400, // ISO感光度，默认100
    val zoomRatio: Float = 1.0f, // 数字变焦倍数，默认1.0x
    val threshold: Float = 0.85f, // 激光点检测阈值，默认0.85
    val minBrightness: Float = 0.7f, // 激光点最小亮度，默认0.7
    val userLevel: UserLevel = UserLevel.NORMAL // 用户级别，默认普通用户
)

/**
 * 语言枚举
 */
enum class Language(val code: String, val displayName: String) {
    ENGLISH("en", "English"),
    CHINESE("zh", "中文")
}

/**
 * 帧率模式枚举
 */
enum class FrameRateMode(val code: String) {
    NORMAL("normal"),
    HIGH_SPEED("high_speed")
}

/**
 * 分辨率枚举
 */
enum class Resolution(val width: Int, val height: Int, val displayName: String) {
    VGA_640x480(640, 480, "640x480"),
    HD_1280x720(1280, 720, "1280x720"),
    FHD_1920x1080(1920, 1080, "1920x1080")
}

/**
 * 用户级别枚举
 */
enum class UserLevel(val code: String, val displayName: String) {
    NORMAL("normal", "普通用户"),
    VIP("vip", "VIP用户");
    
    /**
     * 获取国际化显示名称
     */
    fun getDisplayName(context: Context): String {
        return when (this) {
            NORMAL -> context.getString(R.string.normal_user)
            VIP -> context.getString(R.string.vip_user)
        }
    }
}

/**
 * 摄像头信息数据类
 */
data class CameraInfo(
    val id: String,
    val name: String,
    val facing: String, // "BACK", "FRONT", "EXTERNAL"
    val focalLengths: List<Float>, // 焦距列表 (mm)
    val supportsNormalFrameRate: Boolean,
    val supportsHighFrameRate: Boolean,
    val supportedResolutions: List<Resolution>,
    val minExposureTimeMs: Float,
    val maxExposureTimeMs: Float,
    val maxIso: Int,
    val hardwareLevel: String, // "LIMITED", "FULL", "LEGACY", "LEVEL_3"
    val physicalSize: Pair<Float, Float>? // 传感器物理尺寸 (mm)
) {
    /**
     * 获取摄像头的显示名称，包含详细信息
     */
    fun getDisplayName(context: Context): String {
        val facingStr = when (facing) {
            "BACK" -> context.getString(R.string.camera_facing_back)
            "FRONT" -> context.getString(R.string.camera_facing_front)
            "EXTERNAL" -> context.getString(R.string.camera_facing_external)
            else -> context.getString(R.string.camera_facing_unknown)
        }
        
        // 使用字符串格式化来创建摄像头显示名称
        return context.getString(R.string.camera_id_format, facingStr, id)
    }
    
    /**
     * 获取摄像头的显示名称（兼容旧版本）
     */
    fun getDisplayName(): String {
        val facingStr = when (facing) {
            "BACK" -> "后置"
            "FRONT" -> "前置"
            "EXTERNAL" -> "外置"
            else -> "未知"
        }
        
        return "$facingStr 摄像头 $id"
    }
    
    /**
     * 根据焦距值获取镜头类型
     */
    private fun getFocalLengthType(focalLength: Float, context: Context?): String {
        return when {
            focalLength < 2.0f -> context?.getString(R.string.focal_length_ultra_wide) ?: "超广角"
            focalLength < 4.0f -> context?.getString(R.string.focal_length_wide) ?: "广角"
            focalLength < 7.0f -> context?.getString(R.string.focal_length_standard) ?: "标准"
            focalLength < 12.0f -> context?.getString(R.string.focal_length_telephoto) ?: "长焦"
            else -> context?.getString(R.string.focal_length_super_telephoto) ?: "超长焦"
        }
    }
    
    /**
     * 获取摄像头的详细描述信息
     */
    fun getDetailDescription(context: Context): String {
        val focalLengthStr = if (focalLengths.isNotEmpty()) {
            val focalLengthDetails = focalLengths.joinToString(", ") { focalLength ->
                val type = getFocalLengthType(focalLength, context)
                "$type ${focalLength}${context.getString(R.string.camera_focal_length_unit)}"
            }
            "${context.getString(R.string.camera_focal_length)}: $focalLengthDetails"
        } else {
            "${context.getString(R.string.camera_focal_length)}: ${context.getString(R.string.camera_unknown)}"
        }
        
        val speedSupport = when {
            supportsHighFrameRate -> context.getString(R.string.camera_support_high_speed)
            supportsNormalFrameRate -> context.getString(R.string.camera_support_normal_only)
            else -> context.getString(R.string.camera_support_none)
        }
        
        return "$focalLengthStr"
//        return "$focalLengthStr, $speedSupport, ${context.getString(R.string.camera_hardware_level)}: $hardwareLevel"
    }
    
    /**
     * 获取摄像头的详细描述信息（兼容旧版本）
     */
    fun getDetailDescription(): String {
        val focalLengthStr = if (focalLengths.isNotEmpty()) {
            val focalLengthDetails = focalLengths.joinToString(", ") { focalLength ->
                val type = getFocalLengthType(focalLength, null)
                "$type ${focalLength}mm"
            }
            "焦距: $focalLengthDetails"
        } else {
            "焦距: 未知"
        }
        
        val speedSupport = when {
            supportsHighFrameRate -> "支持高速帧率"
            supportsNormalFrameRate -> "仅支持普通帧率"
            else -> "不支持预览"
        }
        
        return "$focalLengthStr, $speedSupport, 等级: $hardwareLevel"
    }
    
    /**
     * 检查摄像头在指定帧率模式下是否可用
     */
    fun isAvailableForFrameRateMode(frameRateMode: FrameRateMode): Boolean {
        return when (frameRateMode) {
            FrameRateMode.NORMAL -> supportsNormalFrameRate
            FrameRateMode.HIGH_SPEED -> supportsHighFrameRate
        }
    }
} 