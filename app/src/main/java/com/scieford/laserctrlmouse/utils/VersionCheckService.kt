package com.scieford.laserctrlmouse.utils

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * 版本检查服务
 * 负责向更新服务器检查是否有新版本可用
 */
class VersionCheckService(private val context: Context) {
    
    companion object {
        private const val TAG = "VersionCheckService"
        
        // 版本检查API地址 - 开发环境
        private const val DEV_VERSION_CHECK_URL = "https://laser.scieford.com/api/version/check"
        // 版本检查API地址 - 生产环境（请根据实际情况修改）
        private const val PROD_VERSION_CHECK_URL = "https://laser.scieford.com/api/version/check"
        
        // 超时设置
        private const val CONNECT_TIMEOUT = 10L
        private const val READ_TIMEOUT = 15L
    }
    
    // 检测是否为调试模式
    private val isDebugMode: Boolean
        get() = try {
            val appInfo = context.applicationInfo
            (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            LogManager.w(TAG, "检测调试模式失败，默认使用生产环境: ${e.message}")
            false
        }
    
    // 动态获取版本检查URL
    private val versionCheckUrl: String
        get() = if (isDebugMode) {
            LogManager.d(TAG, "使用开发环境URL: $DEV_VERSION_CHECK_URL")
            DEV_VERSION_CHECK_URL
        } else {
            LogManager.d(TAG, "使用生产环境URL: $PROD_VERSION_CHECK_URL")
            PROD_VERSION_CHECK_URL
        }
    
    // HTTP客户端
    private val httpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .build()
    }
    
    // JSON处理器
    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }
    
    /**
     * 版本检查请求数据
     */
    @JsonClass(generateAdapter = true)
    data class VersionCheckRequest(
        @Json(name = "device_id") val deviceId: String,
        @Json(name = "device_model") val deviceModel: String,
        @Json(name = "gpu_info") val gpuInfo: String,
        @Json(name = "current_version") val currentVersion: String,
        @Json(name = "android_version") val androidVersion: String,
        @Json(name = "app_package") val appPackage: String
    )
    
    /**
     * 版本检查响应数据
     */
    @JsonClass(generateAdapter = true)
    data class VersionCheckResponse(
        @Json(name = "has_update") val hasUpdate: Boolean,
        @Json(name = "latest_version") val latestVersion: String,
        @Json(name = "download_url") val downloadUrl: String,
        @Json(name = "update_description") val updateDescription: String? = null,
        @Json(name = "force_update") val forceUpdate: Boolean = false,
        @Json(name = "min_supported_version") val minSupportedVersion: String? = null
    )
    
    /**
     * 检查版本更新
     * @return VersionCheckResponse 或 null（如果检查失败）
     */
    suspend fun checkVersion(): VersionCheckResponse? = withContext(Dispatchers.IO) {
        try {
            LogManager.d(TAG, "开始检查版本更新...")
            
            // 收集设备信息
            val deviceInfo = collectDeviceInfo()
            LogManager.d(TAG, "设备信息收集完成: $deviceInfo")
            
            // 构建请求数据
            val request = VersionCheckRequest(
                deviceId = deviceInfo.deviceId,
                deviceModel = deviceInfo.deviceModel,
                gpuInfo = deviceInfo.gpuInfo,
                currentVersion = deviceInfo.currentVersion,
                androidVersion = deviceInfo.androidVersion,
                appPackage = deviceInfo.appPackage
            )
            
            // 转换为JSON
            val adapter = moshi.adapter(VersionCheckRequest::class.java)
            val requestJson = adapter.toJson(request)
            LogManager.d(TAG, "请求JSON: $requestJson")
            
            // 创建HTTP请求
            val requestBody = requestJson.toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url(versionCheckUrl)
                .post(requestBody)
                .addHeader("User-Agent", "LaserCtrlMouse-Android/${deviceInfo.currentVersion}")
                .build()
            
            // 发送请求
            LogManager.d(TAG, "发送版本检查请求到: $versionCheckUrl")
            val response = httpClient.newCall(httpRequest).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                LogManager.d(TAG, "版本检查响应: $responseBody")
                
                if (responseBody != null) {
                    val responseAdapter = moshi.adapter(VersionCheckResponse::class.java)
                    val versionResponse = responseAdapter.fromJson(responseBody)
                    
                    LogManager.i(TAG, "版本检查成功 - 最新版本: ${versionResponse?.latestVersion}, 当前版本: ${deviceInfo.currentVersion}")
                    return@withContext versionResponse
                }
            } else {
                LogManager.e(TAG, "版本检查请求失败 - HTTP状态码: ${response.code}, 消息: ${response.message}")
            }
            
        } catch (e: java.net.UnknownServiceException) {
            LogManager.e(TAG, "网络安全策略错误: ${e.message}", e)
            LogManager.w(TAG, "提示：如果是开发环境，请确保已配置网络安全策略允许localhost通信")
        } catch (e: java.net.ConnectException) {
                            LogManager.e(TAG, "连接失败，请检查更新服务器是否运行: ${e.message}", e)
        } catch (e: java.net.SocketTimeoutException) {
            LogManager.e(TAG, "请求超时: ${e.message}", e)
        } catch (e: Exception) {
            LogManager.e(TAG, "版本检查异常: ${e.message}", e)
        }
        
        return@withContext null
    }
    
    /**
     * 设备信息数据类
     */
    private data class DeviceInfo(
        val deviceId: String,
        val deviceModel: String,
        val gpuInfo: String,
        val currentVersion: String,
        val androidVersion: String,
        val appPackage: String
    )
    
    /**
     * 收集设备信息
     */
    private fun collectDeviceInfo(): DeviceInfo {
        try {
            // 获取设备唯一ID
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            
            // 获取设备型号
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            
            // 获取GPU信息
            val gpuInfo = GPUInfoHelper.getGPUInfo()
            
            // 获取当前应用版本
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            val currentVersion = packageInfo.versionName ?: "unknown"
            
            // 获取Android版本
            val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            
            // 获取应用包名
            val appPackage = context.packageName
            
            return DeviceInfo(
                deviceId = deviceId ?: "unknown",
                deviceModel = deviceModel,
                gpuInfo = gpuInfo,
                currentVersion = currentVersion,
                androidVersion = androidVersion,
                appPackage = appPackage
            )
        } catch (e: Exception) {
            LogManager.e(TAG, "收集设备信息失败: ${e.message}", e)
            return DeviceInfo(
                deviceId = "unknown",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                gpuInfo = "unknown",
                currentVersion = "unknown",
                androidVersion = "Android ${Build.VERSION.RELEASE}",
                appPackage = context.packageName
            )
        }
    }
    
    /**
     * 比较版本号
     * @param current 当前版本号
     * @param latest 最新版本号
     * @return true 如果latest版本更新
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        try {
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            
            val maxLength = maxOf(currentParts.size, latestParts.size)
            
            for (i in 0 until maxLength) {
                val currentPart = currentParts.getOrNull(i) ?: 0
                val latestPart = latestParts.getOrNull(i) ?: 0
                
                when {
                    latestPart > currentPart -> return true
                    latestPart < currentPart -> return false
                }
            }
            
            return false // 版本号相同
        } catch (e: Exception) {
            LogManager.e(TAG, "版本号比较失败: ${e.message}", e)
            return false
        }
    }
} 