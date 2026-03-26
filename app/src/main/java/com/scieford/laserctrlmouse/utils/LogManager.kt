package com.scieford.laserctrlmouse.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 自定义日志管理器
 * 
 * 功能：
 * 1. 根据应用状态写入不同的日志文件
 * 2. 控制日志文件大小
 * 3. 处理日志文件备份和清理
 * 4. 提供日志上传功能
 */
class LogManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "LogManager"
        
        // 日志文件名称
        private const val DEFAULT_LOG = "default.log"
        private const val SCREEN_DETECT_LOG = "screen-detect.log"
        private const val LASER_DETECT_LOG = "laser-detect.log"
        
        // 备份文件后缀
        private const val BACKUP_SUFFIX = "-bak.log"
        
        // 文件大小限制
        private const val MAX_DEFAULT_LOG_SIZE = 10 * 1024 * 1024L // 10MB
        private const val MAX_SCREEN_DETECT_LOG_SIZE = 10 * 1024 * 1024L // 10MB  
        private const val MAX_LASER_DETECT_LOG_SIZE = 5 * 1024 * 1024L // 5MB
        
        // 单例实例
        @Volatile
        private var INSTANCE: LogManager? = null
        
        // 临时日志缓存（在正式初始化前使用）
        private val tempLogCache = mutableListOf<TempLogEntry>()
        private var tempCacheEnabled = true
        
        // 临时日志条目
        private data class TempLogEntry(
            val timestamp: Long,
            val tag: String,
            val level: String,
            val message: String,
            val throwable: Throwable? = null
        )
        
        /**
         * 早期初始化 - 在Application.attachBaseContext中调用
         * 在这个阶段applicationContext可能还没有准备好，所以直接使用传入的context
         */
        fun earlyInit(context: Context) {
            if (INSTANCE == null) {
                synchronized(this) {
                    if (INSTANCE == null) {
                        // 在attachBaseContext阶段，直接使用传入的context
                        // 不使用applicationContext，因为它可能还没有准备好
                        INSTANCE = LogManager(context)
                        // 将临时缓存的日志写入文件
                        flushTempCache()
                    }
                }
            }
        }
        
        /**
         * 完整初始化 - 在Application.onCreate中调用（确保完整功能）
         */
        fun initialize(context: Context) {
            earlyInit(context)
        }
        
        /**
         * 获取单例实例
         */
        fun getInstance(): LogManager? {
            return INSTANCE
        }
        
        /**
         * 将临时缓存的日志写入文件
         */
        private fun flushTempCache() {
            synchronized(tempLogCache) {
                val instance = INSTANCE
                if (instance != null && tempLogCache.isNotEmpty()) {
                    // 输出缓存统计
                    val cacheSize = tempLogCache.size
                    instance.writeLogMessage("LogManager", "INFO", "开始写入 $cacheSize 条缓存的临时日志")
                    
                    // 写入所有缓存的日志
                    tempLogCache.forEach { entry ->
                        instance.writeLogMessage(entry.tag, entry.level, entry.message, entry.throwable)
                    }
                    
                    // 清空缓存
                    tempLogCache.clear()
                    tempCacheEnabled = false
                    
                    instance.writeLogMessage("LogManager", "INFO", "临时日志缓存已全部写入文件")
                }
            }
        }
        
        /**
         * 添加日志到临时缓存
         */
        private fun addToTempCache(tag: String, level: String, message: String, throwable: Throwable? = null) {
            synchronized(tempLogCache) {
                if (tempCacheEnabled) {
                    tempLogCache.add(TempLogEntry(System.currentTimeMillis(), tag, level, message, throwable))
                    // 限制缓存大小，避免内存溢出
                    if (tempLogCache.size > 1000) {
                        tempLogCache.removeAt(0) // 移除最老的条目
                    }
                }
            }
            
            // 同时输出到 logcat 以便调试
            when (level) {
                "DEBUG" -> Log.d(tag, "$message [临时缓存]", throwable)
                "INFO" -> Log.i(tag, "$message [临时缓存]", throwable)
                "WARN" -> Log.w(tag, "$message [临时缓存]", throwable)
                "ERROR" -> Log.e(tag, "$message [临时缓存]", throwable)
            }
        }
        
        // 静态日志方法 - 完全兼容原生Log
        fun d(tag: String, message: String) {
            val instance = getInstance()
            if (instance != null) {
                instance.debug(tag, message)
            } else {
                addToTempCache(tag, "DEBUG", message)
            }
        }
        
        fun i(tag: String, message: String) {
            val instance = getInstance()
            if (instance != null) {
                instance.info(tag, message)
            } else {
                addToTempCache(tag, "INFO", message)
            }
        }
        
        fun w(tag: String, message: String) {
            val instance = getInstance()
            if (instance != null) {
                instance.warn(tag, message)
            } else {
                addToTempCache(tag, "WARN", message)
            }
        }
        
        fun e(tag: String, message: String) {
            val instance = getInstance()
            if (instance != null) {
                instance.error(tag, message)
            } else {
                addToTempCache(tag, "ERROR", message)
            }
        }
        
        fun e(tag: String, message: String, throwable: Throwable) {
            val instance = getInstance()
            if (instance != null) {
                instance.error(tag, message, throwable)
            } else {
                addToTempCache(tag, "ERROR", message, throwable)
            }
        }
    }
    
    // 日志状态枚举
    enum class LogState {
        DEFAULT,        // 默认状态
        SCREEN_DETECT,  // 屏幕检测状态
        LASER_DETECT    // 激光检测状态
    }
    
    // 当前日志状态
    @Volatile
    private var currentState = LogState.DEFAULT
    
    // 日志目录
    private val logDir: File = File(context.filesDir, "logs")
    
    // 日志文件
    private val defaultLogFile = File(logDir, DEFAULT_LOG)
    private val screenDetectLogFile = File(logDir, SCREEN_DETECT_LOG)
    private val laserDetectLogFile = File(logDir, LASER_DETECT_LOG)
    
    // 备份文件
    private val defaultLogBackupFile = File(logDir, DEFAULT_LOG.replace(".log", BACKUP_SUFFIX))
    private val screenDetectLogBackupFile = File(logDir, SCREEN_DETECT_LOG.replace(".log", BACKUP_SUFFIX))
    private val laserDetectLogBackupFile = File(logDir, LASER_DETECT_LOG.replace(".log", BACKUP_SUFFIX))
    
    // 读写锁，保证线程安全
    private val lock = ReentrantReadWriteLock()
    
    // 日志写入器
    private var currentLogWriter: BufferedWriter? = null
    
    // 日志格式化器
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val uploadDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    
    // 协程作用域
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 主线程Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    init {
        // 创建日志目录
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        
        // 处理启动时的备份逻辑
        handleStartupBackup()
        
        // 初始化默认日志写入器
        initializeLogWriter()
        
        // 输出初始化信息
        writeLogMessage("LogManager", "INFO", "LogManager 初始化完成，日志目录: ${logDir.absolutePath}")
    }
    
    /**
     * 处理启动时的备份逻辑
     */
    private fun handleStartupBackup() {
        try {
            // 如果发现已有日志文件，重命名为备份文件
            if (defaultLogFile.exists()) {
                if (defaultLogBackupFile.exists()) {
                    defaultLogBackupFile.delete()
                }
                defaultLogFile.renameTo(defaultLogBackupFile)
                Log.i(TAG, "已将 ${DEFAULT_LOG} 重命名为备份文件")
            }
            
            if (screenDetectLogFile.exists()) {
                if (screenDetectLogBackupFile.exists()) {
                    screenDetectLogBackupFile.delete()
                }
                screenDetectLogFile.renameTo(screenDetectLogBackupFile)
                Log.i(TAG, "已将 ${SCREEN_DETECT_LOG} 重命名为备份文件")
            }
            
            if (laserDetectLogFile.exists()) {
                if (laserDetectLogBackupFile.exists()) {
                    laserDetectLogBackupFile.delete()
                }
                laserDetectLogFile.renameTo(laserDetectLogBackupFile)
                Log.i(TAG, "已将 ${LASER_DETECT_LOG} 重命名为备份文件")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理启动备份时出错: ${e.message}", e)
        }
    }
    
    /**
     * 初始化日志写入器
     */
    private fun initializeLogWriter() {
        lock.write {
            try {
                currentLogWriter?.close()
                currentLogWriter = BufferedWriter(FileWriter(getCurrentLogFile(), true))
            } catch (e: Exception) {
                Log.e(TAG, "初始化日志写入器失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 获取当前状态对应的日志文件
     */
    private fun getCurrentLogFile(): File {
        return when (currentState) {
            LogState.DEFAULT -> defaultLogFile
            LogState.SCREEN_DETECT -> screenDetectLogFile
            LogState.LASER_DETECT -> laserDetectLogFile
        }
    }
    
    /**
     * 获取当前状态对应的文件大小限制
     */
    private fun getCurrentMaxLogSize(): Long {
        return when (currentState) {
            LogState.DEFAULT -> MAX_DEFAULT_LOG_SIZE
            LogState.SCREEN_DETECT -> MAX_SCREEN_DETECT_LOG_SIZE
            LogState.LASER_DETECT -> MAX_LASER_DETECT_LOG_SIZE
        }
    }
    
    /**
     * 切换日志状态
     */
    fun switchLogState(newState: LogState) {
        if (currentState == newState) return
        
        lock.write {
            try {
                writeLogMessage("LogManager", "INFO", "切换日志状态: $currentState -> $newState")
                
                // 关闭当前写入器
                currentLogWriter?.flush()
                currentLogWriter?.close()
                
                // 更新状态
                currentState = newState
                
                // 创建新的写入器
                currentLogWriter = BufferedWriter(FileWriter(getCurrentLogFile(), true))
                
                writeLogMessage("LogManager", "INFO", "日志状态切换完成，当前写入文件: ${getCurrentLogFile().name}")
            } catch (e: Exception) {
                Log.e(TAG, "切换日志状态失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 写入日志消息的核心方法
     */
    private fun writeLogMessage(tag: String, level: String, message: String, throwable: Throwable? = null) {
        coroutineScope.launch {
            lock.write {
                try {
                    val timestamp = dateFormat.format(Date())
                    val logMessage = buildString {
                        append("$timestamp [$level] $tag: $message")
                        if (throwable != null) {
                            append("\n")
                            append(Log.getStackTraceString(throwable))
                        }
                        append("\n")
                    }
                    
                    // 检查文件大小并处理
                    val currentFile = getCurrentLogFile()
                    if (currentFile.exists() && currentFile.length() > getCurrentMaxLogSize()) {
                        handleLogFileRotation()
                    }
                    
                    // 写入日志
                    currentLogWriter?.write(logMessage)
                    currentLogWriter?.flush()
                    
                    // 同时输出到Android原生日志系统（方便调试）
                    when (level) {
                        "DEBUG" -> Log.d(tag, message, throwable)
                        "INFO" -> Log.i(tag, message, throwable)
                        "WARN" -> Log.w(tag, message, throwable)
                        "ERROR" -> Log.e(tag, message, throwable)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "写入日志失败: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 处理日志文件轮转（当文件过大时）
     */
    private fun handleLogFileRotation() {
        try {
            val currentFile = getCurrentLogFile()
            val tempFile = File(currentFile.parent, "${currentFile.name}.tmp")
            
            // 关闭当前写入器
            currentLogWriter?.close()
            
            // 将当前文件的后半部分保留，前半部分删除
            val lines = currentFile.readLines()
            val keepLines = lines.takeLast(lines.size / 2) // 保留后半部分
            
            tempFile.writeText(keepLines.joinToString("\n") + "\n")
            currentFile.delete()
            tempFile.renameTo(currentFile)
            
            // 重新创建写入器
            currentLogWriter = BufferedWriter(FileWriter(currentFile, true))
            
            writeLogMessage("LogManager", "INFO", "日志文件轮转完成，保留了 ${keepLines.size} 行日志")
        } catch (e: Exception) {
            Log.e(TAG, "日志文件轮转失败: ${e.message}", e)
            // 如果轮转失败，尝试重新创建写入器
            try {
                currentLogWriter = BufferedWriter(FileWriter(getCurrentLogFile(), true))
            } catch (e2: Exception) {
                Log.e(TAG, "重新创建写入器失败: ${e2.message}", e2)
            }
        }
    }
    
    // 公开的日志方法
    fun debug(tag: String, message: String) = writeLogMessage(tag, "DEBUG", message)
    fun info(tag: String, message: String) = writeLogMessage(tag, "INFO", message)
    fun warn(tag: String, message: String) = writeLogMessage(tag, "WARN", message)
    fun error(tag: String, message: String) = writeLogMessage(tag, "ERROR", message)
    fun error(tag: String, message: String, throwable: Throwable) = writeLogMessage(tag, "ERROR", message, throwable)
    
    /**
     * 清理所有日志文件
     */
    fun clearAllLogs() {
        lock.write {
            try {
                writeLogMessage("LogManager", "INFO", "开始清理所有日志文件")
                
                // 关闭当前写入器
                currentLogWriter?.close()
                currentLogWriter = null
                
                // 删除所有日志文件
                deleteFileIfExists(defaultLogFile)
                deleteFileIfExists(screenDetectLogFile) 
                deleteFileIfExists(laserDetectLogFile)
                deleteFileIfExists(defaultLogBackupFile)
                deleteFileIfExists(screenDetectLogBackupFile)
                deleteFileIfExists(laserDetectLogBackupFile)
                
                Log.i(TAG, "所有日志文件已清理")
            } catch (e: Exception) {
                Log.e(TAG, "清理日志文件失败: ${e.message}", e)
            }
        }
    }
    
    private fun deleteFileIfExists(file: File) {
        if (file.exists()) {
            val deleted = file.delete()
            Log.d(TAG, "${file.name} 删除${if (deleted) "成功" else "失败"}")
        }
    }
    
    /**
     * 获取所有日志文件的信息
     */
    fun getLogFilesInfo(): Map<String, Any> {
        return lock.read {
            mapOf(
                "defaultLog" to getFileInfo(defaultLogFile),
                "screenDetectLog" to getFileInfo(screenDetectLogFile),
                "laserDetectLog" to getFileInfo(laserDetectLogFile),
                "defaultLogBackup" to getFileInfo(defaultLogBackupFile),
                "screenDetectLogBackup" to getFileInfo(screenDetectLogBackupFile),
                "laserDetectLogBackup" to getFileInfo(laserDetectLogBackupFile),
                "currentState" to currentState.name,
                "logDir" to logDir.absolutePath
            )
        }
    }
    
    private fun getFileInfo(file: File): Map<String, Any> {
        return if (file.exists()) {
            mapOf(
                "exists" to true,
                "size" to file.length(),
                "lastModified" to file.lastModified(),
                "path" to file.absolutePath
            )
        } else {
            mapOf("exists" to false)
        }
    }
    
    /**
     * 创建日志压缩包
     */
    fun createLogZipFile(): File? {
        return try {
            val timestamp = uploadDateFormat.format(Date())
            val zipFile = File(context.cacheDir, "logs_$timestamp.zip")
            
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                // 添加所有存在的日志文件
                addFileToZip(zipOut, defaultLogFile, DEFAULT_LOG)
                addFileToZip(zipOut, screenDetectLogFile, SCREEN_DETECT_LOG)
                addFileToZip(zipOut, laserDetectLogFile, LASER_DETECT_LOG)
                addFileToZip(zipOut, defaultLogBackupFile, DEFAULT_LOG.replace(".log", BACKUP_SUFFIX))
                addFileToZip(zipOut, screenDetectLogBackupFile, SCREEN_DETECT_LOG.replace(".log", BACKUP_SUFFIX))
                addFileToZip(zipOut, laserDetectLogBackupFile, LASER_DETECT_LOG.replace(".log", BACKUP_SUFFIX))
                
                // 添加设备信息文件
                addDeviceInfoToZip(zipOut)
            }
            
            Log.i(TAG, "日志压缩包创建成功: ${zipFile.absolutePath}")
            zipFile
        } catch (e: Exception) {
            Log.e(TAG, "创建日志压缩包失败: ${e.message}", e)
            null
        }
    }
    
    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists()) return
        
        try {
            val entry = ZipEntry(entryName)
            zipOut.putNextEntry(entry)
            
            FileInputStream(file).use { fis ->
                fis.copyTo(zipOut)
            }
            
            zipOut.closeEntry()
            Log.d(TAG, "已添加文件到压缩包: $entryName (${file.length()} 字节)")
        } catch (e: Exception) {
            Log.e(TAG, "添加文件到压缩包失败: $entryName, ${e.message}", e)
        }
    }
    
    private fun addDeviceInfoToZip(zipOut: ZipOutputStream) {
        try {
            val deviceInfo = buildString {
                append("设备信息\n")
                append("====================\n")
                append("时间: ${dateFormat.format(Date())}\n")
                append("制造商: ${android.os.Build.MANUFACTURER}\n")
                append("型号: ${android.os.Build.MODEL}\n")
                append("Android 版本: ${android.os.Build.VERSION.RELEASE}\n")
                append("API 级别: ${android.os.Build.VERSION.SDK_INT}\n")
                append("应用版本: ${getAppVersionInfo()}\n")
                append("日志状态: ${currentState.name}\n")
                
                // 添加内存信息
                val runtime = Runtime.getRuntime()
                val maxMemory = runtime.maxMemory() / 1024 / 1024
                val totalMemory = runtime.totalMemory() / 1024 / 1024
                val freeMemory = runtime.freeMemory() / 1024 / 1024
                val usedMemory = totalMemory - freeMemory
                
                append("内存信息:\n")
                append("  最大内存: ${maxMemory}MB\n")
                append("  总内存: ${totalMemory}MB\n")
                append("  已用内存: ${usedMemory}MB\n")
                append("  可用内存: ${freeMemory}MB\n")
            }
            
            val entry = ZipEntry("device_info.txt")
            zipOut.putNextEntry(entry)
            zipOut.write(deviceInfo.toByteArray())
            zipOut.closeEntry()
            
            Log.d(TAG, "已添加设备信息到压缩包")
        } catch (e: Exception) {
            Log.e(TAG, "添加设备信息到压缩包失败: ${e.message}", e)
        }
    }
    
    private fun getAppVersionInfo(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "未知"
        }
    }
    
    /**
     * 上传日志到云端日志服务器
     * @param feedbackInfo 用户反馈信息
     * @param contactInfo 用户联系方式
     * @param onSuccess 成功回调
     * @param onError 失败回调
     */
    fun uploadLogsToServer(
        feedbackInfo: String = "",
        contactInfo: String = "",
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        coroutineScope.launch {
            try {
                writeLogMessage("LogManager", "INFO", "开始上传日志到云端日志服务器")
                writeLogMessage("LogManager", "INFO", "反馈信息: $feedbackInfo")
                writeLogMessage("LogManager", "INFO", "联系方式: $contactInfo")
                
                // 创建日志压缩包
                val zipFile = createLogZipFile()
                if (zipFile == null) {
                    mainHandler.post { onError("创建日志压缩包失败") }
                    return@launch
                }
                
                writeLogMessage("LogManager", "INFO", "日志压缩包已创建: ${zipFile.absolutePath}, 大小: ${zipFile.length()} 字节")
                
                // 上传到云端日志服务器
                val uploadResult = uploadToCloudServer(zipFile, feedbackInfo, contactInfo)
                
                // 清理临时文件
                if (zipFile.exists()) {
                    zipFile.delete()
                    writeLogMessage("LogManager", "INFO", "临时压缩包文件已清理")
                }
                
                if (uploadResult.isSuccess) {
                    writeLogMessage("LogManager", "INFO", "日志上传成功: ${uploadResult.message}")
                    mainHandler.post { onSuccess() }
                } else {
                    val errorMsg = "日志上传失败: ${uploadResult.message}"
                    writeLogMessage("LogManager", "ERROR", errorMsg)
                    mainHandler.post { onError(errorMsg) }
                }
                
            } catch (e: Exception) {
                val errorMsg = "上传日志时发生异常: ${e.message}"
                writeLogMessage("LogManager", "ERROR", errorMsg, e)
                mainHandler.post { onError(errorMsg) }
            }
        }
    }
    
    /**
     * 上传结果数据类
     */
    private data class UploadResult(
        val isSuccess: Boolean,
        val message: String
    )
    
    /**
     * 上传到云端日志服务器的具体实现
     */
    private suspend fun uploadToCloudServer(
        zipFile: File,
        feedbackInfo: String,
        contactInfo: String
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            // 云端日志上传服务器地址 (这里需要替换为实际的日志服务器地址)
            val uploadUrl = "https://laser.scieford.com/api/feedback/submit"
            
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            // 构建多部分请求体
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("feedback_info", feedbackInfo)
                .addFormDataPart("contact_info", contactInfo)
                .addFormDataPart("device_info", getDeviceInfoJson())
                .addFormDataPart("app_version", getAppVersionInfo())
                .addFormDataPart(
                    "log_file",
                    zipFile.name,
                    zipFile.asRequestBody("application/zip".toMediaTypeOrNull())
                )
                .build()
            
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .addHeader("User-Agent", "LaserCtrlMouse-Android/${getAppVersionInfo()}")
                .build()
            
            writeLogMessage("LogManager", "INFO", "开始HTTP上传，文件大小: ${zipFile.length()} 字节")
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    writeLogMessage("LogManager", "INFO", "上传成功，响应: $responseBody")
                    UploadResult(true, "上传成功")
                } else {
                    val errorMsg = "日志服务器响应错误: ${response.code} - ${response.message}"
                    writeLogMessage("LogManager", "ERROR", "$errorMsg, 响应内容: $responseBody")
                    UploadResult(false, errorMsg)
                }
            }
            
        } catch (e: Exception) {
            val errorMsg = "网络请求异常: ${e.message}"
            writeLogMessage("LogManager", "ERROR", errorMsg, e)
            UploadResult(false, errorMsg)
        }
    }
    
    /**
     * 获取设备信息的JSON格式
     */
    private fun getDeviceInfoJson(): String {
        return try {
            val deviceInfo = mapOf(
                "manufacturer" to android.os.Build.MANUFACTURER,
                "model" to android.os.Build.MODEL,
                "android_version" to android.os.Build.VERSION.RELEASE,
                "api_level" to android.os.Build.VERSION.SDK_INT,
                "app_version" to getAppVersionInfo(),
                "timestamp" to System.currentTimeMillis(),
                "log_state" to currentState.name
            )
            
            // 简单的JSON序列化 (在实际项目中建议使用Moshi或Gson)
            buildString {
                append("{")
                deviceInfo.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(",")
                    append("\"${entry.key}\":\"${entry.value}\"")
                }
                append("}")
            }
        } catch (e: Exception) {
            "{\"error\":\"Failed to serialize device info\"}"
        }
    }
    
    /**
     * 销毁LogManager
     */
    fun destroy() {
        lock.write {
            try {
                writeLogMessage("LogManager", "INFO", "LogManager 开始销毁")
                
                // 关闭写入器
                currentLogWriter?.flush()
                currentLogWriter?.close()
                currentLogWriter = null
                
                // 取消所有协程
                coroutineScope.cancel()
                
                Log.i(TAG, "LogManager 销毁完成")
            } catch (e: Exception) {
                Log.e(TAG, "销毁LogManager失败: ${e.message}", e)
            }
        }
    }
} 