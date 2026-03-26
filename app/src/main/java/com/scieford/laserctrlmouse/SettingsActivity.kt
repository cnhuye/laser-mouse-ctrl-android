package com.scieford.laserctrlmouse.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scieford.laserctrlmouse.camera.Camera2Helper
import com.scieford.laserctrlmouse.MainActivity
import com.scieford.laserctrlmouse.R
import com.scieford.laserctrlmouse.ui.theme.LaserCtrlMouseTheme
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.scieford.laserctrlmouse.utils.LogManager

class SettingsActivity : ComponentActivity() {
    companion object {
        private const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        LogManager.d(TAG, "设置界面启动")
        
        setContent {
            LaserCtrlMouseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        onSaveSettings = { settingsChanged ->
                            handleSettingsSave(settingsChanged)
                        },
                        onCancel = {
                            LogManager.d(TAG, "用户取消设置")
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        }
                    )
                }
            }
        }
    }
    
    private fun handleSettingsSave(settingsChanged: Boolean) {
        LogManager.d(TAG, "处理设置保存，是否有更改: $settingsChanged")
        
        if (settingsChanged) {
            LogManager.d(TAG, "设置已更改，返回需要重启摄像头的结果")
            setResult(Activity.RESULT_OK)
        } else {
            LogManager.d(TAG, "设置无更改")
            setResult(Activity.RESULT_CANCELED)
        }
        
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSaveSettings: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = SettingsManager.getInstance(context)
    
    // 创建Camera2Helper实例用于获取摄像头信息
    val camera2Helper = remember { Camera2Helper(context, settingsManager) }
    
    // 获取真实的摄像头信息
    val cameraInfoList = remember { camera2Helper.getCameraInfoList() }
    
    // 当前设置状态
    var currentSettings by remember { mutableStateOf(settingsManager.getSettings()) }
    val originalSettings = remember { settingsManager.getSettings() }
    
    // 过滤可用的摄像头列表（根据当前帧率模式）
    val availableCameras = remember(currentSettings.frameRateMode) {
        // 当扩展功能关闭时，所有摄像头都按普通帧率模式进行过滤
        val effectiveFrameRateMode = if (MainActivity.extFeatureEnabled) {
            currentSettings.frameRateMode
        } else {
            FrameRateMode.NORMAL
        }
        
        cameraInfoList.filter { cameraInfo ->
            cameraInfo.isAvailableForFrameRateMode(effectiveFrameRateMode)
        }
    }
    
    // 确保当前选中的摄像头在可用列表中
    LaunchedEffect(availableCameras, currentSettings.selectedCameraId) {
        if (availableCameras.isNotEmpty() && 
            !availableCameras.any { it.id == currentSettings.selectedCameraId }) {
            // 当前选中的摄像头不可用，选择第一个可用的摄像头
            currentSettings = currentSettings.copy(selectedCameraId = availableCameras.first().id)
        }
    }
    
    // 获取当前选中摄像头的信息
    val selectedCameraInfo = cameraInfoList.find { it.id == currentSettings.selectedCameraId }
    
    // 过滤支持的分辨率
    val supportedResolutions = remember(selectedCameraInfo) {
        selectedCameraInfo?.supportedResolutions ?: Resolution.values().toList()
    }
    
    // 确保当前分辨率在支持列表中
    LaunchedEffect(supportedResolutions, currentSettings.resolution) {
        if (!supportedResolutions.contains(currentSettings.resolution)) {
            // 当前分辨率不支持，选择第一个支持的分辨率
            if (supportedResolutions.isNotEmpty()) {
                currentSettings = currentSettings.copy(resolution = supportedResolutions.first())
            }
        }
    }
    
    // 曝光时间范围
    val exposureRange = remember(selectedCameraInfo) {
        0.0f..100.0f  // 固定范围为 0-100ms
    }
    
    // 确保曝光时间在范围内
    LaunchedEffect(exposureRange, currentSettings.exposureTimeMs) {
        val clampedExposure = currentSettings.exposureTimeMs.coerceIn(exposureRange)
        if (clampedExposure != currentSettings.exposureTimeMs) {
            currentSettings = currentSettings.copy(exposureTimeMs = clampedExposure)
        }
    }
    
    // 检查设置是否有变化
    fun hasChanges(): Boolean {
        return currentSettings != originalSettings
    }
    
    // 保存设置
    fun saveSettings() {
        LogManager.d("SettingsScreen", "保存设置: $currentSettings")
        
        // 保存到SettingsManager
        settingsManager.saveSettings(currentSettings)
        
        // 不再在这里直接应用设置，让MainActivity统一处理
        // 这样可以避免在设置界面和MainActivity之间的状态不一致
        
        LogManager.d("SettingsScreen", "设置已保存到SharedPreferences")
        
        onSaveSettings(hasChanges())
    }
    
    // 应用语言预览（立即生效，但不保存）
    fun applyLanguagePreview(language: Language) {
        // 移除立即应用语言预览的功能，避免在设置界面频繁切换语言
        // 语言切换将在保存设置后由MainActivity统一处理
        LogManager.d("SettingsScreen", "语言已选择: ${language.displayName}，将在保存后生效")
        
        // 可以在这里显示一个提示，告诉用户语言将在保存后生效
        // Toast.makeText(context, "语言将在保存设置后生效", Toast.LENGTH_SHORT).show()
    }
    
    // 布局
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 主要内容区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .padding(bottom = 80.dp) // 为底部按钮留出空间
                .verticalScroll(rememberScrollState())
        ) {
            // 标题
            Text(
                text = context.getString(R.string.settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            // 语言设置 - 使用单选按钮组
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(R.string.interface_language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    Text(
                        text = context.getString(R.string.interface_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Column(
                        modifier = Modifier.selectableGroup()
                    ) {
                        Language.values().forEach { language ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = (currentSettings.language == language),
                                        onClick = {
                                            currentSettings = currentSettings.copy(language = language)
                                            // 立即应用语言预览
                                            applyLanguagePreview(language)
                                        },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (currentSettings.language == language),
                                    onClick = null
                                )
                                Text(
                                    text = language.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            // 帧率模式设置 - 仅在扩展功能启用时显示
            if (true || MainActivity.extFeatureEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.frame_rate_mode),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        
                        Text(
                            text = context.getString(R.string.frame_rate_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        Column(
                            modifier = Modifier.selectableGroup()
                        ) {
                            FrameRateMode.values().forEach { mode ->
                                val displayName = when (mode) {
                                    FrameRateMode.NORMAL -> context.getString(R.string.normal_frame_rate)
                                    FrameRateMode.HIGH_SPEED -> context.getString(R.string.high_speed_frame_rate)
                                }
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = (currentSettings.frameRateMode == mode),
                                            onClick = {
                                                currentSettings = currentSettings.copy(frameRateMode = mode)
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (currentSettings.frameRateMode == mode),
                                        onClick = null
                                    )
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                            
                            // 高速帧率模式下的备注
                            if (currentSettings.frameRateMode == FrameRateMode.HIGH_SPEED) {
                                Text(
                                    text = context.getString(R.string.high_speed_frame_rate_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .padding(start = 32.dp, top = 8.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            // 摄像头选择
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(R.string.camera_selection),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    Text(
                        text = context.getString(R.string.camera_selection_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (availableCameras.isEmpty()) {
                        Text(
                            text = "当前帧率模式下没有可用的摄像头",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Column(
                            modifier = Modifier.selectableGroup()
                        ) {
                            availableCameras.forEach { cameraInfo ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = (currentSettings.selectedCameraId == cameraInfo.id),
                                            onClick = {
                                                currentSettings = currentSettings.copy(selectedCameraId = cameraInfo.id)
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (currentSettings.selectedCameraId == cameraInfo.id),
                                        onClick = null
                                    )
                                    Column(
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Text(
                                            text = cameraInfo.getDisplayName(context),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = cameraInfo.getDetailDescription(context),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // 分辨率选择
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(R.string.resolution),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    Text(
                        text = context.getString(R.string.resolution_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (supportedResolutions.isEmpty()) {
                        Text(
                            text = "当前摄像头没有支持的分辨率",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Column(
                            modifier = Modifier.selectableGroup()
                        ) {
                            supportedResolutions.forEach { resolution ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = (currentSettings.resolution == resolution),
                                            onClick = {
                                                currentSettings = currentSettings.copy(resolution = resolution)
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (currentSettings.resolution == resolution),
                                        onClick = null
                                    )
                                    Text(
                                        text = resolution.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // ISO感光度设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(R.string.iso_sensitivity),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    Text(
                        text = context.getString(R.string.iso_sensitivity_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "ISO ${currentSettings.isoValue}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // ISO值的可选范围：50, 100, 200, 400, 800, 1600
                    val isoValues = listOf(50, 100, 200, 400, 800, 1600)
                    val currentIsoIndex = isoValues.indexOf(currentSettings.isoValue).let { 
                        if (it == -1) 1 else it // 如果找不到，默认为100 (index 1)
                    }
                    
                    Slider(
                        value = currentIsoIndex.toFloat(),
                        onValueChange = { newIndex ->
                            val selectedIso = isoValues[newIndex.toInt()]
                            currentSettings = currentSettings.copy(isoValue = selectedIso)
                        },
                        valueRange = 0f..(isoValues.size - 1).toFloat(),
                        steps = isoValues.size - 2 // steps = 总数 - 1 - 1
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ISO ${isoValues.first()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "ISO ${isoValues.last()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // 曝光时间设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(R.string.exposure_time),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    Text(
                        text = context.getString(R.string.exposure_time_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "${String.format("%.1f", currentSettings.exposureTimeMs)}ms",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Slider(
                        value = currentSettings.exposureTimeMs,
                        onValueChange = { newValue ->
                            currentSettings = currentSettings.copy(
                                exposureTimeMs = (newValue * 2).toInt() / 2f // 四舍五入到0.5ms
                            )
                        },
                        valueRange = exposureRange,
                        steps = ((exposureRange.endInclusive - exposureRange.start) * 2).toInt() - 1 // 步长为0.5ms
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${String.format("%.1f", exposureRange.start)}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${String.format("%.1f", exposureRange.endInclusive)}ms", 
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // 激光点检测阈值设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(R.string.threshold),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    Text(
                        text = context.getString(R.string.threshold_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = String.format("%.2f", currentSettings.threshold),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Slider(
                        value = currentSettings.threshold,
                        onValueChange = { newValue ->
                            currentSettings = currentSettings.copy(
                                threshold = (newValue * 20).toInt() / 20f // 四舍五入到0.05
                            )
                        },
                        valueRange = 0.0f..1.0f,
                        steps = 19 // (1.0 - 0.0) / 0.05 - 1 = 19
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "0.00",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "1.00",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // 最小亮度阈值设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(R.string.min_brightness),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    Text(
                        text = context.getString(R.string.min_brightness_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = String.format("%.2f", currentSettings.minBrightness),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Slider(
                        value = currentSettings.minBrightness,
                        onValueChange = { newValue ->
                            currentSettings = currentSettings.copy(
                                minBrightness = (newValue * 20).toInt() / 20f // 四舍五入到0.05
                            )
                        },
                        valueRange = 0.0f..1.0f,
                        steps = 19 // (1.0 - 0.0) / 0.05 - 1 = 19
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "0.00",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "1.00",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // 底部按钮区域 - 固定在屏幕底部
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 重置按钮
                OutlinedButton(
                    onClick = {
                        currentSettings = SettingsData() // 重置为默认设置
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(context.getString(R.string.reset))
                }
                
                // 取消按钮
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(context.getString(R.string.cancel))
                }
                
                // 保存按钮
                Button(
                    onClick = { saveSettings() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(context.getString(R.string.save))
                }
            }
        }
    }
} 