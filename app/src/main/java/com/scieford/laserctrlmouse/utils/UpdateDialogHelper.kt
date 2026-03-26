package com.scieford.laserctrlmouse.utils

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.TextView
import com.scieford.laserctrlmouse.R

/**
 * 更新提示对话框工具类
 */
object UpdateDialogHelper {
    
    private const val TAG = "UpdateDialogHelper"
    
    /**
     * 显示更新提示对话框
     * @param context 上下文
     * @param currentVersion 当前版本
     * @param latestVersion 最新版本
     * @param downloadUrl 下载地址
     * @param updateDescription 更新描述（可选）
     * @param forceUpdate 是否强制更新
     */
    fun showUpdateDialog(
        context: Context,
        currentVersion: String,
        latestVersion: String,
        downloadUrl: String,
        updateDescription: String? = null,
        forceUpdate: Boolean = false
    ) {
        try {
            LogManager.i(TAG, "显示更新对话框 - 当前版本: $currentVersion, 最新版本: $latestVersion, 强制更新: $forceUpdate")
            
            val dialogBuilder = AlertDialog.Builder(context)
            
            // 设置标题和消息
            val title = if (forceUpdate) {
                context.getString(R.string.force_update_title)
            } else {
                context.getString(R.string.update_available_title)
            }
            
            val message = buildString {
                append(context.getString(R.string.current_version_label))
                append(": ")
                append(currentVersion)
                append("\n")
                append(context.getString(R.string.latest_version_label))
                append(": ")
                append(latestVersion)
                
                if (!updateDescription.isNullOrEmpty()) {
                    append("\n\n")
                    append(context.getString(R.string.update_description_label))
                    append(":\n")
                    append(updateDescription)
                }
            }
            
            dialogBuilder.setTitle(title)
            dialogBuilder.setMessage(message)
            
            // 设置图标
            dialogBuilder.setIcon(android.R.drawable.ic_dialog_info)
            
            // 设置确定按钮
            dialogBuilder.setPositiveButton(
                if (forceUpdate) context.getString(R.string.update_now) else context.getString(R.string.download_update)
            ) { dialog, _ ->
                LogManager.d(TAG, "用户确认更新，开始下载")
                openDownloadUrl(context, downloadUrl)
                dialog.dismiss()
            }
            
            // 如果不是强制更新，设置取消按钮
            if (!forceUpdate) {
                dialogBuilder.setNegativeButton(context.getString(R.string.cancel)) { dialog, _ ->
                    LogManager.d(TAG, "用户取消更新")
                    dialog.dismiss()
                }
            }
            
            // 设置对话框不可取消（如果是强制更新）
            dialogBuilder.setCancelable(!forceUpdate)
            
            // 创建并显示对话框
            val dialog = dialogBuilder.create()
            dialog.show()
            
            LogManager.i(TAG, "更新对话框已显示")
            
        } catch (e: Exception) {
            LogManager.e(TAG, "显示更新对话框失败: ${e.message}", e)
            // 如果对话框显示失败，直接尝试打开下载链接
            openDownloadUrl(context, downloadUrl)
        }
    }
    
    /**
     * 打开下载URL
     * @param context 上下文
     * @param downloadUrl 下载地址
     */
    private fun openDownloadUrl(context: Context, downloadUrl: String) {
        try {
            LogManager.d(TAG, "准备打开下载链接: $downloadUrl")
            
            // 验证URL格式
            if (!isValidUrl(downloadUrl)) {
                LogManager.e(TAG, "无效的下载URL: $downloadUrl")
                showErrorMessage(context, context.getString(R.string.invalid_download_url))
                return
            }
            
            // 创建Intent打开浏览器
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
            
            // 检查是否有可用的浏览器应用
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LogManager.i(TAG, "已启动浏览器打开下载链接: $downloadUrl")
            } else {
                LogManager.e(TAG, "没有找到可用的浏览器应用")
                showErrorMessage(context, context.getString(R.string.no_browser_available))
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "打开下载链接失败: ${e.message}", e)
            showErrorMessage(context, context.getString(R.string.open_download_link_failed, e.message))
        }
    }
    
    /**
     * 验证URL格式
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            uri.scheme != null && (uri.scheme == "http" || uri.scheme == "https") && uri.host != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 显示错误消息
     */
    private fun showErrorMessage(context: Context, message: String) {
        try {
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.error))
                .setMessage(message)
                .setPositiveButton(context.getString(R.string.ok), null)
                .show()
        } catch (e: Exception) {
            LogManager.e(TAG, "显示错误消息失败: ${e.message}")
        }
    }
} 