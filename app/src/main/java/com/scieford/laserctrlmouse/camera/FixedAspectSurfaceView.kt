package com.scieford.laserctrlmouse.camera

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.scieford.laserctrlmouse.MainActivity
import com.scieford.laserctrlmouse.utils.LogManager

/**
 * 固定宽高比的 SurfaceView，用于高速摄像头模式
 * 会强制将尺寸保持为指定的宽高比，并确保 SurfaceHolder 使用精确的分辨率
 * 分辨率从MainActivity动态获取
 */
class FixedAspectSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {
    
    companion object {
        private const val TAG = "FixedAspectSurfaceView"
    }
    
    // 宽高参数 - 动态获取
    val targetWidth: Int
        get() = MainActivity.getCameraWidth()
    val targetHeight: Int
        get() = MainActivity.getCameraHeight()
    
    init {
        // 设置固定尺寸
        holder.setFixedSize(targetWidth, targetHeight)
        // 启用硬件加速  会造成界面 白屏，激光检测时无法获取帧数据
//        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }
    
    /**
     * 设置目标分辨率
     * 注意：分辨率将始终从MainActivity动态获取，此方法仅用于兼容性
     */
    fun setTargetResolution(width: Int, height: Int) {
        val actualWidth = MainActivity.getCameraWidth()
        val actualHeight = MainActivity.getCameraHeight()
        LogManager.d(TAG, "setTargetResolution调用: 请求${width}x${height}, 实际使用: ${actualWidth}x${actualHeight}")
        holder.setFixedSize(actualWidth, actualHeight)
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        
        // 计算宽高比
        val targetRatio = targetWidth.toFloat() / targetHeight
        
        // 根据可用空间和目标宽高比确定最终尺寸
        var finalWidth = widthSize
        var finalHeight = (finalWidth / targetRatio).toInt()
        
        // 如果计算的高度超出可用高度，从高度重新计算宽度
        if (finalHeight > heightSize) {
            finalHeight = heightSize
            finalWidth = (finalHeight * targetRatio).toInt()
        }
        
        LogManager.d(TAG, "测量尺寸: 可用=$widthSize x $heightSize, 计算=$finalWidth x $finalHeight")
        
        // 设置计算后的尺寸
        setMeasuredDimension(finalWidth, finalHeight)
        
        // 确保 SurfaceHolder 使用目标分辨率
        holder.setFixedSize(targetWidth, targetHeight)
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LogManager.d(TAG, "附加到窗口")
        holder.setFixedSize(targetWidth, targetHeight)
    }
    
    /**
     * 添加简化的 SurfaceHolder 回调接口
     */
    fun setCallback(
        onSurfaceCreated: (SurfaceHolder) -> Unit,
        onSurfaceChanged: (SurfaceHolder, Int, Int, Int) -> Unit = { _, _, _, _ -> },
        onSurfaceDestroyed: (SurfaceHolder) -> Unit = {}
    ) {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                LogManager.d(TAG, "Surface 已创建")
                // 再次确保固定尺寸
                holder.setFixedSize(targetWidth, targetHeight)
                onSurfaceCreated(holder)
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                LogManager.d(TAG, "Surface 尺寸已变更: $width x $height")
                // 如果尺寸不符合目标，重新设置
                if (width != targetWidth || height != targetHeight) {
                    LogManager.d(TAG, "重新设置尺寸为 ${targetWidth}x${targetHeight}")
                    holder.setFixedSize(targetWidth, targetHeight)
                }
                onSurfaceChanged(holder, format, width, height)
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                LogManager.d(TAG, "Surface 已销毁")
                onSurfaceDestroyed(holder)
            }
        })
    }
}