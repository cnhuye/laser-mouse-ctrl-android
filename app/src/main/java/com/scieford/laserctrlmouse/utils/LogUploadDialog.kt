package com.scieford.laserctrlmouse.utils

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.scieford.laserctrlmouse.R

/**
 * 日志上传对话框
 */
@Composable
fun LogUploadDialog(
    onDismiss: () -> Unit,
    onUpload: (feedbackInfo: String, contactInfo: String) -> Unit
) {
    var feedbackText by remember { mutableStateOf("") }
    var contactText by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = { if (!isUploading) onDismiss() },
        title = { Text(context.getString(R.string.upload_logs)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = context.getString(R.string.feedback_description_prompt),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                
                OutlinedTextField(
                    value = feedbackText,
                    onValueChange = { feedbackText = it },
                    label = { Text(context.getString(R.string.feedback_description_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 5,
                    placeholder = { Text(context.getString(R.string.feedback_description_placeholder)) }
                )
                
                Text(
                    text = context.getString(R.string.contact_info_prompt),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                
                OutlinedTextField(
                    value = contactText,
                    onValueChange = { contactText = it },
                    label = { Text(context.getString(R.string.contact_info_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(context.getString(R.string.contact_info_placeholder)) }
                )
                
                Text(
                    text = context.getString(R.string.upload_content_info),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isUploading) {
                        isUploading = true
                        onUpload(feedbackText, contactText)
                    }
                },
                enabled = !isUploading
            ) {
                Text(if (isUploading) context.getString(R.string.uploading) else context.getString(R.string.upload))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!isUploading) onDismiss() },
                enabled = !isUploading
            ) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}

/**
 * 显示日志上传结果
 */
fun showLogUploadResult(context: Context, success: Boolean, message: String) {
    val title = if (success) context.getString(R.string.upload_success_title) else context.getString(R.string.upload_failed_title)
    val icon = if (success) android.R.drawable.ic_dialog_info else android.R.drawable.ic_dialog_alert
    
    AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(message)
        .setIcon(icon)
        .setPositiveButton(context.getString(R.string.confirm), null)
        .show()
}

/**
 * 显示日志文件信息对话框
 */
fun showLogFilesInfo(context: Context) {
    try {
        val logManager = LogManager.getInstance()
        val filesInfo = logManager?.getLogFilesInfo()
        
        if (filesInfo == null) {
            Toast.makeText(context, context.getString(R.string.logmanager_not_initialized), Toast.LENGTH_SHORT).show()
            return
        }
        
        val message = buildString {
            append("日志文件信息：\n\n")
            
            append("当前日志状态：${filesInfo["currentState"]}\n")
            append("日志目录：${filesInfo["logDir"]}\n\n")
            
            append("文件详情：\n")
            
            val files = mapOf(
                "默认日志" to filesInfo["defaultLog"],
                "屏幕检测日志" to filesInfo["screenDetectLog"],
                "激光检测日志" to filesInfo["laserDetectLog"],
                "默认日志备份" to filesInfo["defaultLogBackup"],
                "屏幕检测备份" to filesInfo["screenDetectLogBackup"],
                "激光检测备份" to filesInfo["laserDetectLogBackup"]
            )
            
            files.forEach { (name, info) ->
                append("$name：")
                if (info is Map<*, *> && info["exists"] == true) {
                    val size = (info["size"] as? Long) ?: 0
                    val sizeKB = size / 1024
                    append("存在 (${sizeKB}KB)\n")
                } else {
                    append("不存在\n")
                }
            }
        }
        
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.log_files_info_title))
            .setMessage(message)
            .setPositiveButton(context.getString(R.string.confirm), null)
            .setNeutralButton(context.getString(R.string.clear_logs)) { _, _ ->
                showClearLogsConfirmation(context)
            }
            .show()
            
    } catch (e: Exception) {
        LogManager.e("LogUploadDialog", "显示日志文件信息失败", e)
        Toast.makeText(context, context.getString(R.string.get_log_info_failed, e.message), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 显示清理日志确认对话框
 */
private fun showClearLogsConfirmation(context: Context) {
    AlertDialog.Builder(context)
        .setTitle(context.getString(R.string.clear_logs_confirmation_title))
        .setMessage(context.getString(R.string.clear_logs_confirmation_message))
        .setIcon(android.R.drawable.ic_dialog_alert)
        .setPositiveButton(context.getString(R.string.clear_logs_confirm)) { _, _ ->
            try {
                val logManager = LogManager.getInstance()
                logManager?.clearAllLogs()
                Toast.makeText(context, context.getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                LogManager.e("LogUploadDialog", "清理日志失败", e)
                Toast.makeText(context, context.getString(R.string.clear_logs_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton(context.getString(R.string.cancel), null)
        .show()
} 