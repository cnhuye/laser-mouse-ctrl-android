package com.scieford.laserctrlmouse

import android.annotation.SuppressLint
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.scieford.laserctrlmouse.ui.theme.LaserCtrlMouseTheme
import com.scieford.laserctrlmouse.utils.LogManager

class WebViewActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WebViewActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        LogManager.d(TAG, "WebView界面启动")
        
        // 获取传入的URL和标题
        val url = intent.getStringExtra("url") ?: "https://www.baidu.com"
        val title = intent.getStringExtra("title") ?: "网页"
        
        LogManager.d(TAG, "加载URL: $url, 标题: $title")
        
        setContent {
            LaserCtrlMouseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WebViewScreen(
                        url = url,
                        title = title,
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
fun WebViewScreen(
    url: String,
    title: String,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableStateOf(0) }
    var pageTitle by remember { mutableStateOf(title) }
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
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isLoading) {
                        Text(
                            text = "加载中... $loadProgress%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                OutlinedButton(onClick = onBack) {
                    Text("返回")
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
        
        // WebView 或错误信息
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
                            text = "网页加载失败",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = "请检查网络连接后重试",
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
                            Text("重新加载")
                        }
                    }
                }
            }
        } else {
            // WebView
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
                                LogManager.d("WebViewScreen", "开始加载页面: $url")
                            }
                            
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                LogManager.d("WebViewScreen", "页面加载完成: $url")
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
                                LogManager.e("WebViewScreen", "页面加载错误: $errorCode - $description")
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
                            
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                title?.let { 
                                    pageTitle = it
                                    LogManager.d("WebViewScreen", "页面标题: $it")
                                }
                            }
                        }
                        
                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) { webView ->
                // 在这里可以对WebView进行更新操作
                if (hasError) {
                    webView.loadUrl(url)
                }
            }
        }
    }
} 