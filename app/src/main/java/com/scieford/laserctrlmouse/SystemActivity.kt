package com.scieford.laserctrlmouse

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scieford.laserctrlmouse.ui.theme.LaserCtrlMouseTheme
import com.scieford.laserctrlmouse.settings.SettingsManager
import com.scieford.laserctrlmouse.settings.UserLevel
import com.scieford.laserctrlmouse.utils.LogManager
import com.scieford.laserctrlmouse.utils.VersionCheckService
import com.scieford.laserctrlmouse.utils.UpdateDialogHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SystemActivity : ComponentActivity() {
    companion object {
        private const val TAG = "SystemActivity"
        private const val VIP_PURCHASE_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        LogManager.d(TAG, "系统界面启动")
        
        setContent {
            LaserCtrlMouseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SystemScreen(
                        onBack = {
                            LogManager.d(TAG, "用户返回")
                            finish()
                        },
                        onVipPurchase = {
                            LogManager.d(TAG, "用户点击VIP升级")
                            openVipPurchaseActivity()
                        },
                        onFeedback = {
                            LogManager.d(TAG, "用户点击反馈")
                            openFeedbackActivity()
                        },
                        onPrivacyPolicy = {
                            LogManager.d(TAG, "用户点击使用协议")
                            openPrivacyPolicyActivity()
                        },
                        onHelp = {
                            LogManager.d(TAG, "用户点击帮助")
                            openHelpPage()
                        },
                        onWebsite = {
                            LogManager.d(TAG, "用户点击官网")
                            openWebsite()
                        },
                        onPcClient = {
                            LogManager.d(TAG, "用户点击获取电脑端")
                            openPcClientDownload()
                        }
                    )
                }
            }
        }
    }
    
    /**
     * 打开VIP购买界面
     */
    private fun openVipPurchaseActivity() {
        val intent = Intent(this, VipPurchaseActivity::class.java)
        startActivityForResult(intent, VIP_PURCHASE_REQUEST_CODE)
    }
    
    /**
     * 打开反馈界面
     */
    private fun openFeedbackActivity() {
        val intent = Intent(this, FeedbackActivity::class.java)
        startActivity(intent)
    }
    
    /**
     * 打开使用协议界面
     */
    private fun openPrivacyPolicyActivity() {
        val intent = Intent(this, WebViewActivity::class.java)
        intent.putExtra("url", "https://laser.scieford.com/privacy.html")
        intent.putExtra("title", getString(R.string.privacy_policy))
        startActivity(intent)
    }
    
    /**
     * 打开帮助页面
     */
    private fun openHelpPage() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://laser.scieford.com/tutorial"))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                LogManager.d(TAG, "已启动浏览器打开帮助页面")
            } else {
                Toast.makeText(this, getString(R.string.no_browser_found), Toast.LENGTH_SHORT).show()
                LogManager.w(TAG, "没有找到可用的浏览器应用")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "打开帮助页面失败: ${e.message}")
            Toast.makeText(this, getString(R.string.open_help_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 打开官网
     */
    private fun openWebsite() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://laser.scieford.com/"))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                LogManager.d(TAG, "已启动浏览器打开官网")
            } else {
                Toast.makeText(this, getString(R.string.no_browser_found), Toast.LENGTH_SHORT).show()
                LogManager.w(TAG, "没有找到可用的浏览器应用")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "打开官网失败: ${e.message}")
            Toast.makeText(this, getString(R.string.open_website_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 打开电脑端下载页面
     */
    private fun openPcClientDownload() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://laser.scieford.com/download"))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                LogManager.d(TAG, "已启动浏览器打开电脑端下载页面")
            } else {
                Toast.makeText(this, getString(R.string.no_browser_found), Toast.LENGTH_SHORT).show()
                LogManager.w(TAG, "没有找到可用的浏览器应用")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "打开电脑端下载页面失败: ${e.message}")
            Toast.makeText(this, getString(R.string.open_website_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 执行版本检查
     */
    fun performVersionCheck(onStartChecking: () -> Unit, onFinishChecking: () -> Unit) {
        LogManager.i(TAG, "手动检查版本更新")
        
        // 开始检查，更新UI状态
        onStartChecking()
        
        // 创建版本检查服务
        val versionCheckService = VersionCheckService(this)
        
        // 在协程中执行版本检查（异步执行，不阻塞UI）
        CoroutineScope(Dispatchers.Main).launch {
            try {
                LogManager.d(TAG, "开始手动版本检查...")
                
                // 执行版本检查
                val versionResponse = versionCheckService.checkVersion()
                
                if (versionResponse != null) {
                    LogManager.i(TAG, "手动版本检查完成 - 最新版本: ${versionResponse.latestVersion}")
                    
                    // 检查是否有更新
                    if (versionResponse.hasUpdate) {
                        LogManager.i(TAG, "发现新版本: ${versionResponse.latestVersion}")
                        
                        // 显示更新对话框
                        UpdateDialogHelper.showUpdateDialog(
                            context = this@SystemActivity,
                            currentVersion = getAppVersion(),
                            latestVersion = versionResponse.latestVersion,
                            downloadUrl = versionResponse.downloadUrl,
                            updateDescription = versionResponse.updateDescription,
                            forceUpdate = versionResponse.forceUpdate
                        )
                    } else {
                        LogManager.i(TAG, "当前版本已是最新版本")
                        Toast.makeText(this@SystemActivity, getString(R.string.current_version_is_latest), Toast.LENGTH_SHORT).show()
                    }
                } else {
                                            LogManager.w(TAG, "手动版本检查失败或更新服务器无响应")
                    Toast.makeText(this@SystemActivity, getString(R.string.version_check_failed), Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                LogManager.e(TAG, "手动版本检查异常: ${e.message}", e)
                Toast.makeText(this@SystemActivity, "${getString(R.string.version_check_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // 完成检查，更新UI状态
                onFinishChecking()
            }
        }
    }
    
    /**
     * 获取当前应用版本号
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: getString(R.string.version_unknown)
        } catch (e: Exception) {
            LogManager.e(TAG, "获取应用版本号失败: ${e.message}")
            getString(R.string.version_unknown)
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == VIP_PURCHASE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                LogManager.d(TAG, "VIP购买成功")
                Toast.makeText(this, getString(R.string.congratulations_vip), Toast.LENGTH_SHORT).show()
                // 界面会自动刷新显示VIP状态
            } else {
                LogManager.d(TAG, "VIP购买取消或失败")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    onBack: () -> Unit,
    onVipPurchase: () -> Unit,
    onFeedback: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onHelp: () -> Unit,
    onWebsite: () -> Unit,
    onPcClient: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = SettingsManager.getInstance(context)
    
    // 当前用户级别状态
    var currentUserLevel by remember { mutableStateOf(settingsManager.getSettings().userLevel) }
    
    // 获取当前版本信息
    val currentVersion = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: context.getString(R.string.version_unknown)
        } catch (e: Exception) {
            context.getString(R.string.version_unknown)
        }
    }
    
    // 版本检查状态
    var isCheckingVersion by remember { mutableStateOf(false) }
    
    // 隐藏功能：版本号点击计数器
    var versionClickCount by remember { mutableStateOf(0) }
    
    // 定期刷新用户级别状态
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val settings = settingsManager.getSettings()
                currentUserLevel = settings.userLevel
                kotlinx.coroutines.delay(1000) // 每秒检查一次
            } catch (e: Exception) {
                LogManager.e("SystemScreen", "刷新用户级别失败: ${e.message}")
                kotlinx.coroutines.delay(5000) // 出错时延长间隔
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 标题和返回按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = context.getString(R.string.system_settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            OutlinedButton(onClick = onBack) {
                Text(context.getString(R.string.back))
            }
        }
        
        // 用户级别设置 - 仅在扩展功能启用时显示
        if (MainActivity.extFeatureEnabled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = context.getString(R.string.user_level_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = currentUserLevel.getDisplayName(context),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (currentUserLevel == UserLevel.VIP) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        if (currentUserLevel == UserLevel.NORMAL) {
                            Button(onClick = onVipPurchase) {
                                Text(context.getString(R.string.upgrade))
                            }
                        } else {
                            Text(
                                text = context.getString(R.string.vip),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        
        // 当前版本和检查更新
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.clickable {
                            // 隐藏功能：点击版本号8次开启扩展功能
                            versionClickCount++
                            LogManager.d("SystemScreen", "版本号点击次数: $versionClickCount")
                            
                            if (versionClickCount >= 8) {
                                // 开启扩展功能
                                MainActivity.extFeatureEnabled = true
                                LogManager.i("SystemScreen", "扩展功能已启用！")
                                
                                // 显示提示消息
                                Toast.makeText(
                                    context,
                                    "扩展功能已启用！",
                                    Toast.LENGTH_SHORT
                                ).show()
                                
                                // 重置计数器
                                versionClickCount = 0
                            }
                        }
                    ) {
                        Text(
                            text = context.getString(R.string.current_version_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = currentVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Button(
                        onClick = {
                            if (!isCheckingVersion) {
                                isCheckingVersion = true
                                val systemActivity = context as SystemActivity
                                systemActivity.performVersionCheck({
                                    // 开始检查，更新UI状态
                                    isCheckingVersion = true
                                }, {
                                    // 完成检查，更新UI状态
                                    isCheckingVersion = false
                                })
                            }
                        },
                        enabled = !isCheckingVersion
                    ) {
                        if (isCheckingVersion) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(context.getString(R.string.checking))
                            }
                        } else {
                            Text(context.getString(R.string.check_for_updates))
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 反馈
        SystemMenuItem(
            title = context.getString(R.string.feedback_menu_title),
            subtitle = context.getString(R.string.feedback_menu_subtitle),
            onClick = onFeedback
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 使用协议
        SystemMenuItem(
            title = context.getString(R.string.privacy_policy_menu_title),
            subtitle = context.getString(R.string.privacy_policy_menu_subtitle),
            onClick = onPrivacyPolicy
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 帮助
        SystemMenuItem(
            title = context.getString(R.string.help_menu_title),
            subtitle = context.getString(R.string.help_menu_subtitle),
            onClick = onHelp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 官网
        SystemMenuItem(
            title = context.getString(R.string.website_menu_title),
            subtitle = context.getString(R.string.website_menu_subtitle),
            onClick = onWebsite
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 获取电脑端
        SystemMenuItem(
            title = context.getString(R.string.pc_client_menu_title),
            subtitle = context.getString(R.string.pc_client_menu_subtitle),
            onClick = onPcClient
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemMenuItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
} 