package com.scieford.laserctrlmouse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.scieford.laserctrlmouse.ui.theme.LaserCtrlMouseTheme
import com.scieford.laserctrlmouse.utils.LogManager
import com.scieford.laserctrlmouse.utils.LogUploadDialog

class FeedbackActivity : ComponentActivity() {
    companion object {
        private const val TAG = "FeedbackActivity"
    }

    override fun attachBaseContext(newBase: Context?) {
        // 确保FeedbackActivity始终使用正确的语言设置
        LogManager.d(TAG, "FeedbackActivity attachBaseContext被调用")
        
        val contextWithCorrectLanguage = newBase?.let { context ->
            try {
                // 使用Application的全局语言管理方法
                LaserCtrlMouseApplication.applyLanguageForContext(context)
            } catch (e: Exception) {
                LogManager.e(TAG, "FeedbackActivity attachBaseContext应用语言设置失败: ${e.message}")
                context // 如果失败，使用原始context
            }
        }
        
        super.attachBaseContext(contextWithCorrectLanguage ?: newBase)
        
        LogManager.d(TAG, "FeedbackActivity attachBaseContext完成")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        LogManager.d(TAG, "反馈界面启动")
        
        setContent {
            LaserCtrlMouseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FeedbackScreen(
                        onBack = {
                            LogManager.d(TAG, "用户返回")
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FeedbackScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showUploadDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableStateOf(0) }
    var hasError by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 标题栏
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = context.getString(R.string.feedback_center),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                OutlinedButton(onClick = onBack) {
                    Text(context.getString(R.string.back))
                }
            }
        }
        
        // 进度条
        if (isLoading) {
            LinearProgressIndicator(
                progress = loadProgress / 100f,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // WebView 或错误信息 - 占据除了底部按钮外的所有空间
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (hasError) {
                // 显示错误信息
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.padding(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "🌐",
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Text(
                                text = context.getString(R.string.webpage_load_failed),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = context.getString(R.string.check_network_connection),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = { 
                                    hasError = false
                                    isLoading = true
                                }
                            ) {
                                Text(context.getString(R.string.reload))
                            }
                        }
                    }
                }
            } else {
                // WebView - 显示常见问题页面
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                            }
                            
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    hasError = false
                                    LogManager.d("FeedbackScreen", "开始加载页面: $url")
                                }
                                
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    LogManager.d("FeedbackScreen", "页面加载完成: $url")
                                }
                                
                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?
                                ) {
                                    super.onReceivedError(view, errorCode, description, failingUrl)
                                    isLoading = false
                                    hasError = true
                                    LogManager.e("FeedbackScreen", "页面加载错误: $errorCode - $description")
                                }
                            }
                            
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    loadProgress = newProgress
                                    if (newProgress == 100) {
                                        isLoading = false
                                    }
                                }
                            }
                            
                            // 加载常见问题页面
                            loadUrl("https://laser.scieford.com/qa")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        // 底部反馈按钮 - 固定在页面底部
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "📝 ${context.getString(R.string.start_feedback)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Button(
                    onClick = { showUploadDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.getString(R.string.feedback_submit_button))
                }
            }
        }
    }
    
    // 反馈对话框
    if (showUploadDialog) {
        LogUploadDialog(
            onDismiss = { showUploadDialog = false },
            onUpload = { feedbackInfo, contactInfo ->
                showUploadDialog = false
                
                // 执行日志上传
                val logManager = LogManager.getInstance()
                logManager?.uploadLogsToServer(
                    feedbackInfo = feedbackInfo,
                    contactInfo = contactInfo,
                    onSuccess = {
                        com.scieford.laserctrlmouse.utils.showLogUploadResult(
                            context, 
                            true, 
                            context.getString(R.string.feedback_success)
                        )
                    },
                    onError = { errorMsg ->
                        com.scieford.laserctrlmouse.utils.showLogUploadResult(
                            context, 
                            false, 
                            context.getString(R.string.feedback_failed, errorMsg)
                        )
                    }
                )
            }
        )
    }
} 