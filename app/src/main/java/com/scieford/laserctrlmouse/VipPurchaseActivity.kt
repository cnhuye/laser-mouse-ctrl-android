package com.scieford.laserctrlmouse

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import com.scieford.laserctrlmouse.ui.theme.LaserCtrlMouseTheme
import com.scieford.laserctrlmouse.settings.SettingsManager
import com.scieford.laserctrlmouse.settings.UserLevel
import com.scieford.laserctrlmouse.utils.LogManager

class VipPurchaseActivity : ComponentActivity() {
    companion object {
        private const val TAG = "VipPurchaseActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        LogManager.d(TAG, "VIP购买界面启动")
        
        setContent {
            LaserCtrlMouseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VipPurchaseScreen(
                        onBack = {
                            LogManager.d(TAG, "用户取消VIP购买")
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        onPurchase = { packageType ->
                            LogManager.d(TAG, "用户选择购买VIP套餐: $packageType")
                            purchaseVip(packageType)
                        }
                    )
                }
            }
        }
    }
    
    /**
     * 购买VIP
     */
    private fun purchaseVip(packageType: String) {
        try {
            LogManager.d(TAG, "开始购买VIP流程: $packageType")
            
            // TODO: 这里应该集成真实的支付系统
            // 比如Google Play Billing, 微信支付, 支付宝等
            
            // 临时模拟购买成功
            simulatePurchaseSuccess()
            
        } catch (e: Exception) {
            LogManager.e(TAG, "VIP购买异常: ${e.message}")
            Toast.makeText(this, "购买失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 模拟购买成功
     */
    private fun simulatePurchaseSuccess() {
        // 模拟网络请求延迟
        lifecycleScope.launch {
            delay(2000) // 模拟2秒的购买流程
            
            runOnUiThread {
                try {
                    // 更新用户级别为VIP
                    val settingsManager = SettingsManager.getInstance(this@VipPurchaseActivity)
                    settingsManager.updateUserLevel(UserLevel.VIP)
                    
                    LogManager.d(TAG, "VIP购买成功，已更新用户级别")
                    
                    // 返回成功结果
                    setResult(Activity.RESULT_OK)
                    finish()
                    
                } catch (e: Exception) {
                    LogManager.e(TAG, "更新VIP状态失败: ${e.message}")
                    Toast.makeText(this@VipPurchaseActivity, "购买成功但状态更新失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
fun VipPurchaseScreen(
    onBack: () -> Unit,
    onPurchase: (String) -> Unit
) {
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "升级VIP",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            OutlinedButton(onClick = onBack) {
                Text("返回")
            }
        }
        
        // VIP特权介绍
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "VIP 特权",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                VipFeatureItem("✨ 无广告体验")
                VipFeatureItem("🚀 优先技术支持")
                VipFeatureItem("⚡ 高级功能解锁")
                VipFeatureItem("🎯 专属激光点算法优化")
                VipFeatureItem("📊 详细性能统计")
                VipFeatureItem("☁️ 云端配置同步")
            }
        }
        
        // 购买选项
        Text(
            text = "选择套餐",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // 月度套餐
        VipPackageCard(
            title = "月度VIP",
            originalPrice = "¥29",
            currentPrice = "¥19",
            description = "首月特惠价格",
            isRecommended = false,
            isProcessing = isProcessing,
            onPurchase = {
                isProcessing = true
                onPurchase("monthly")
            }
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 年度套餐（推荐）
        VipPackageCard(
            title = "年度VIP",
            originalPrice = "¥348",
            currentPrice = "¥168",
            description = "平均每月仅需14元，超值优惠",
            isRecommended = true,
            isProcessing = isProcessing,
            onPurchase = {
                isProcessing = true
                onPurchase("yearly")
            }
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 终身套餐
        VipPackageCard(
            title = "终身VIP",
            originalPrice = "¥999",
            currentPrice = "¥399",
            description = "一次购买，终身享受",
            isRecommended = false,
            isProcessing = isProcessing,
            onPurchase = {
                isProcessing = true
                onPurchase("lifetime")
            }
        )
        
        // 说明文字
        Text(
            text = "• 购买后立即生效\n• 支持7天无理由退款\n• 支持多设备同步使用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun VipFeatureItem(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun VipPackageCard(
    title: String,
    originalPrice: String,
    currentPrice: String,
    description: String,
    isRecommended: Boolean,
    isProcessing: Boolean,
    onPurchase: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isRecommended) 6.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecommended) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        )
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        if (isRecommended) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    text = "推荐",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = originalPrice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currentPrice,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isRecommended) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Button(
                    onClick = onPurchase,
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecommended) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("购买")
                    }
                }
            }
        }
    }
} 