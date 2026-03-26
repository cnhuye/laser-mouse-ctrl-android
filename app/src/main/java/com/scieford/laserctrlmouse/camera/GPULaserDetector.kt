package com.scieford.laserctrlmouse.camera

import android.content.Context
import android.opengl.GLES31
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.scieford.laserctrlmouse.camera.CameraImageProcessor.LaserPointData
import com.scieford.laserctrlmouse.utils.LogManager

/**
 * GPU加速的激光点检测器 - 直接处理外部纹理版本
 * 使用OpenGL ES 3.1 Compute Shader + SSBO + samplerExternalOES实现<6ms激光点检测
 * 纯GPU计算，避免纹理转换和CPU数据传输，目标性能：< 6ms
 */
class GPULaserDetector(private val context: Context) {
    companion object {
        private const val TAG = "GPULaserDetector"
        
        // GPU并行检测Compute Shader - 直接使用外部纹理采样器
        private const val LASER_DETECT_SHADER = """#version 310 es
            #extension GL_OES_EGL_image_external_essl3 : require
            layout(local_size_x = 16, local_size_y = 16) in;
            
            // 直接使用外部纹理采样器，避免纹理转换
            uniform samplerExternalOES inputTexture;
            
            // SSBO输出缓冲区：存储激光点坐标和亮度
            layout(std430, binding = 1) buffer LaserPointBuffer {
                float laserX;      // 激光点X坐标 (归一化 0-1)
                float laserY;      // 激光点Y坐标 (归一化 0-1) 
                float brightness;  // 最大亮度值
                float confidence;  // 检测置信度
                float redValue;    // 红色分量值（用于调试）
                float debugInfo;   // 调试信息
            } laserPoint;
            
            // 检测参数
            uniform float uThreshold;
            uniform float uMinBrightness;
            uniform ivec2 uTextureSize;
            
            // 共享内存用于工作组内的最大值归约
            shared float sharedMaxBrightness[256]; // 16x16 = 256
            shared vec2 sharedMaxCoords[256];
            shared float sharedRedValues[256];
            
            // RGB转HSV函数（参考LaserPointDetector的逻辑）
            vec3 rgb2hsv(vec3 c) {
                vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-10;
                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
            }
            
            // 检测是否为红色（参考LaserPointDetector的HSV范围）
            bool isRedColor(vec3 hsv) {
                float h = hsv.x * 360.0; // 转换为0-360度
                float s = hsv.y * 255.0; // 转换为0-255
                float v = hsv.z * 255.0; // 转换为0-255
                
                // 红色范围1: H=[0,10], S=[100,255], V=[20,255]
                bool range1 = (h >= 0.0 && h <= 10.0) && (s >= 100.0) && (v >= 20.0);
                
                // 红色范围2: H=[160,179], S=[100,255], V=[20,255]  
                bool range2 = (h >= 160.0 && h <= 179.0) && (s >= 100.0) && (v >= 20.0);
                
                return range1 || range2;
            }
            
            void main() {
                ivec2 coords = ivec2(gl_GlobalInvocationID.xy);
                uint localIndex = gl_LocalInvocationIndex;
                
                float maxBrightness = 0.0;
                vec2 maxCoords = vec2(0.0);
                float maxRedValue = 0.0;
                
                // 边界检查
                if (coords.x < uTextureSize.x && coords.y < uTextureSize.y) {
                    // 计算纹理坐标 (0.0-1.0)
                    vec2 texCoord = vec2(
                        float(coords.x) / float(uTextureSize.x),
                        float(coords.y) / float(uTextureSize.y)
                    );
                    
                    // 直接使用外部纹理采样读取像素 - 避免纹理转换
                    vec4 pixel = texture(inputTexture, texCoord);
                    
                    // 转换为HSV颜色空间
                    vec3 hsv = rgb2hsv(pixel.rgb);
                    
                    // 检测是否为红色
                    if (isRedColor(hsv)) {
                        // 计算亮度（使用HSV的V分量）
                        float brightness = hsv.z;
                        
                        // 也可以使用RGB亮度作为备选
                        float rgbBrightness = dot(pixel.rgb, vec3(0.299, 0.587, 0.114));
                        
                        // 使用更高的亮度值
                        brightness = max(brightness, rgbBrightness);
                        
                        // 降低阈值以便调试，检测更多的红色点
                        if (brightness > uThreshold && pixel.r > uMinBrightness) { // 使用Uniform变量
                        maxBrightness = brightness;
                            maxRedValue = pixel.r;
                        // 归一化坐标
                        maxCoords = vec2(
                                float(coords.x) / float(uTextureSize.x),
                                float(coords.y) / float(uTextureSize.y)
                            );
                        }
                    }
                    
                    // 备选方案：简单的红色检测（用于对比）
                    if (maxBrightness == 0.0) {
                        // 简单的红色偏向检测
                        float redRatio = pixel.r / max(pixel.g + pixel.b + 0.001, 0.001);
                        if (redRatio > 1.2 && pixel.r > uMinBrightness) { // 使用Uniform变量
                            maxBrightness = pixel.r;
                            maxRedValue = pixel.r;
                            maxCoords = vec2(
                                float(coords.x) / float(uTextureSize.x),
                                float(coords.y) / float(uTextureSize.y)
                            );
                        }
                    }
                }
                
                // 存储到共享内存
                sharedMaxBrightness[localIndex] = maxBrightness;
                sharedMaxCoords[localIndex] = maxCoords;
                sharedRedValues[localIndex] = maxRedValue;
                
                // 同步工作组
                barrier();
                
                // 在工作组内进行归约，找到最亮的激光点
                for (uint stride = 128u; stride > 0u; stride >>= 1u) {
                    if (localIndex < stride) {
                        uint otherIndex = localIndex + stride;
                        if (sharedMaxBrightness[otherIndex] > sharedMaxBrightness[localIndex]) {
                            sharedMaxBrightness[localIndex] = sharedMaxBrightness[otherIndex];
                            sharedMaxCoords[localIndex] = sharedMaxCoords[otherIndex];
                            sharedRedValues[localIndex] = sharedRedValues[otherIndex];
                        }
                    }
                    barrier();
                }
                
                // 第一个线程将结果写入SSBO
                if (localIndex == 0u && sharedMaxBrightness[0] > 0.0) {
                    // 直接写入最亮的激光点数据
                    laserPoint.laserX = sharedMaxCoords[0].x;
                    laserPoint.laserY = sharedMaxCoords[0].y;
                    laserPoint.brightness = sharedMaxBrightness[0];
                    laserPoint.confidence = min(sharedMaxBrightness[0] * 2.0, 1.0);
                    laserPoint.redValue = sharedRedValues[0];
                    laserPoint.debugInfo = 1.0; // 表示检测到了点
                }
            }
        """
        
        // 清零SSBO的Compute Shader
        private const val CLEAR_BUFFER_SHADER = """#version 310 es
            layout(local_size_x = 1) in;
            
            layout(std430, binding = 1) buffer LaserPointBuffer {
                float laserX;
                float laserY; 
                float brightness;
                float confidence;
                float redValue;
                float debugInfo;
            } laserPoint;
            
            void main() {
                laserPoint.laserX = 0.0;
                laserPoint.laserY = 0.0;
                laserPoint.brightness = 0.0;
                laserPoint.confidence = 0.0;
                laserPoint.redValue = 0.0;
                laserPoint.debugInfo = 0.0;
            }
        """
        
        // 清零GlobalMaxBuffer的Compute Shader
        private const val CLEAR_GLOBAL_BUFFER_SHADER = """#version 310 es
            layout(local_size_x = 1) in;
            
            layout(std430, binding = 2) buffer GlobalMaxBuffer {
                uint maxBrightnessAsUint;  // 最大亮度值的无符号整数表示
                float globalMaxX;          // 全局最亮点X坐标
                float globalMaxY;          // 全局最亮点Y坐标
                float globalMaxBrightness; // 全局最大亮度
                float globalMaxRed;        // 全局最大红色值
                uint lockFlag;             // 原子锁标志
            } globalMax;
            
            void main() {
                globalMax.maxBrightnessAsUint = 0u;
                globalMax.globalMaxX = 0.0;
                globalMax.globalMaxY = 0.0;
                globalMax.globalMaxBrightness = 0.0;
                globalMax.globalMaxRed = 0.0;
                globalMax.lockFlag = 0u;
            }
        """
        
        // 清零ProximityBuffer的Compute Shader
        private const val CLEAR_PROXIMITY_BUFFER_SHADER = """#version 310 es
            layout(local_size_x = 1) in;
            
            layout(std430, binding = 3) buffer ProximityBuffer {
                float bestScore;            // 最佳得分
                float bestX;               // 最佳X坐标
                float bestY;               // 最佳Y坐标
                float bestRed;             // 最佳红色值
                uint lockFlag;             // 原子锁标志
                uint candidateCount;       // 候选点数量（调试用）
            } proximityData;
            
            void main() {
                // 清零所有字段
                proximityData.bestScore = 0.0;
                proximityData.bestX = 0.0;
                proximityData.bestY = 0.0;
                proximityData.bestRed = 0.0;
                proximityData.lockFlag = 0u;
                proximityData.candidateCount = 0u;
            }
        """
        
        // 简化的调试Compute Shader - 只检测最红的像素
        private const val DEBUG_LASER_DETECT_SHADER = """#version 310 es
            #extension GL_OES_EGL_image_external_essl3 : require
            layout(local_size_x = 16, local_size_y = 16) in;
            
            // 直接使用外部纹理采样器
            uniform samplerExternalOES inputTexture;
            
            // SSBO输出缓冲区
            layout(std430, binding = 1) buffer LaserPointBuffer {
                float laserX;
                float laserY; 
                float brightness;
                float confidence;
                float redValue;
                float debugInfo;
            } laserPoint;
            
            // 检测参数
            uniform float uThreshold;
            uniform float uMinBrightness;
            uniform ivec2 uTextureSize;
            
            // 共享内存
            shared float sharedMaxRed[256];
            shared vec2 sharedMaxCoords[256];
            
            void main() {
                ivec2 coords = ivec2(gl_GlobalInvocationID.xy);
                uint localIndex = gl_LocalInvocationIndex;
                
                float maxRed = 0.0;
                vec2 maxCoords = vec2(0.0);
                
                // 边界检查
                if (coords.x < uTextureSize.x && coords.y < uTextureSize.y) {
                    // 计算纹理坐标
                    vec2 texCoord = vec2(
                        float(coords.x) / float(uTextureSize.x),
                        float(coords.y) / float(uTextureSize.y)
                    );
                    
                    // 读取像素
                    vec4 pixel = texture(inputTexture, texCoord);
                    
                    // 简单检测：找到红色分量最大的像素
                    if (pixel.r > uMinBrightness) { // 使用Uniform变量
                        maxRed = pixel.r;
                        maxCoords = vec2(
                            float(coords.x) / float(uTextureSize.x),
                            float(coords.y) / float(uTextureSize.y)
                        );
                    }
                }
                
                // 存储到共享内存
                sharedMaxRed[localIndex] = maxRed;
                sharedMaxCoords[localIndex] = maxCoords;
                
                // 同步
                barrier();
                
                // 归约找到最大红色值
                for (uint stride = 128u; stride > 0u; stride >>= 1u) {
                    if (localIndex < stride) {
                        uint otherIndex = localIndex + stride;
                        if (sharedMaxRed[otherIndex] > sharedMaxRed[localIndex]) {
                            sharedMaxRed[localIndex] = sharedMaxRed[otherIndex];
                            sharedMaxCoords[localIndex] = sharedMaxCoords[otherIndex];
                        }
                    }
                    barrier();
                }
                
                // 写入结果
                if (localIndex == 0u && sharedMaxRed[0] > 0.0) {
                    laserPoint.laserX = sharedMaxCoords[0].x;
                    laserPoint.laserY = sharedMaxCoords[0].y;
                    laserPoint.brightness = sharedMaxRed[0];
                    laserPoint.confidence = sharedMaxRed[0];
                    laserPoint.redValue = sharedMaxRed[0];
                    laserPoint.debugInfo = 1.0; // 明确标记检测到了
                }
            }
        """
        
        // 最简单的测试Compute Shader - 只写入固定值
        private const val TEST_COMPUTE_SHADER = """#version 310 es
            layout(local_size_x = 1) in;
            
            layout(std430, binding = 1) buffer LaserPointBuffer {
                float laserX;
                float laserY; 
                float brightness;
                float confidence;
                float redValue;
                float debugInfo;
            } laserPoint;
            
            void main() {
                // 写入固定的测试值
                laserPoint.laserX = 0.5;
                laserPoint.laserY = 0.3;
                laserPoint.brightness = 0.8;
                laserPoint.confidence = 0.9;
                laserPoint.redValue = 0.7;
                laserPoint.debugInfo = 1.0;
            }
        """
        
        // 纹理读取测试Shader - 只读取中心像素
        private const val TEXTURE_TEST_SHADER = """#version 310 es
            #extension GL_OES_EGL_image_external_essl3 : require
            layout(local_size_x = 1) in;
            
            uniform samplerExternalOES inputTexture;
            
            layout(std430, binding = 1) buffer LaserPointBuffer {
                float laserX;      // 存储中心点X坐标 (0.5)
                float laserY;      // 存储中心点Y坐标 (0.5)
                float brightness;  // 存储红色分量
                float confidence;  // 存储绿色分量
                float redValue;    // 存储蓝色分量
                float debugInfo;   // 存储亮度值
            } laserPoint;
            
            void main() {
                // 读取纹理中心点
                vec2 centerCoord = vec2(0.5, 0.5);
                vec4 pixel = texture(inputTexture, centerCoord);
                
                // 计算亮度
                float brightness = dot(pixel.rgb, vec3(0.299, 0.587, 0.114));
                
                // 输出结果
                laserPoint.laserX = 0.5;           // 中心X坐标
                laserPoint.laserY = 0.5;           // 中心Y坐标
                laserPoint.brightness = pixel.r;   // 红色分量
                laserPoint.confidence = pixel.g;   // 绿色分量
                laserPoint.redValue = pixel.b;     // 蓝色分量
                laserPoint.debugInfo = brightness; // 亮度值
            }
        """
        
        // SSBO写入验证Shader - 测试SSBO基本写入功能
        private const val SSBO_VERIFY_SHADER = """#version 310 es
            layout(local_size_x = 1) in;
            
            layout(std430, binding = 1) buffer LaserPointBuffer {
                float laserX;
                float laserY; 
                float brightness;
                float confidence;
                float redValue;
                float debugInfo;
            } laserPoint;
            
            void main() {
                // 写入明确的测试值
                laserPoint.laserX = 0.123;
                laserPoint.laserY = 0.456;
                laserPoint.brightness = 0.789;
                laserPoint.confidence = 0.999;
                laserPoint.redValue = 0.555;
                laserPoint.debugInfo = 1.0;
            }
        """
        
        // UBO版本的激光点检测Compute Shader - 使用UBO传递参数，SSBO输出结果
        private const val UBO_LASER_DETECT_SHADER = """#version 310 es
            #extension GL_OES_EGL_image_external_essl3 : require
            layout(local_size_x = 16, local_size_y = 16) in;
            
            // 直接使用外部纹理采样器
            uniform samplerExternalOES inputTexture;
            
            // SSBO输出缓冲区：存储激光点坐标和亮度
            layout(std430, binding = 1) buffer LaserPointBuffer {
                float laserX;      // 激光点X坐标 (归一化 0-1)
                float laserY;      // 激光点Y坐标 (归一化 0-1) 
                float brightness;  // 最大亮度值
                float confidence;  // 检测置信度
                float redValue;    // 红色分量值（用于调试）
                float debugInfo;   // 调试信息
            } laserPoint;
            
            // UBO输入参数：使用UBO传递检测参数，可能比Uniform更快
            layout(std140, binding = 0) uniform DetectionParams {
                float threshold;      // 亮度阈值
                float minBrightness;  // 最小红色亮度
                ivec2 textureSize;    // 纹理尺寸
                float reserved1;      // 对齐填充
                float reserved2;      // 对齐填充
            } params;
            
            // 共享内存用于工作组内的最大值归约
            shared float sharedMaxBrightness[256]; // 16x16 = 256
            shared vec2 sharedMaxCoords[256];
            shared float sharedRedValues[256];
            
            // RGB转HSV函数（与SSBO版本相同）
            vec3 rgb2hsv(vec3 c) {
                vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-10;
                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
            }
            
            // 检测是否为红色（与SSBO版本相同）
            bool isRedColor(vec3 hsv) {
                float h = hsv.x * 360.0; // 转换为0-360度
                float s = hsv.y * 255.0; // 转换为0-255
                float v = hsv.z * 255.0; // 转换为0-255
                
                // 红色范围1: H=[0,10], S=[100,255], V=[20,255]
                bool range1 = (h >= 0.0 && h <= 10.0) && (s >= 100.0) && (v >= 20.0);
                
                // 红色范围2: H=[160,179], S=[100,255], V=[20,255]  
                bool range2 = (h >= 160.0 && h <= 179.0) && (s >= 100.0) && (v >= 20.0);
                
                return range1 || range2;
            }
            
            void main() {
                ivec2 coords = ivec2(gl_GlobalInvocationID.xy);
                uint localIndex = gl_LocalInvocationIndex;
                
                float maxBrightness = 0.0;
                vec2 maxCoords = vec2(0.0);
                float maxRedValue = 0.0;
                
                // 边界检查 - 使用UBO中的纹理尺寸
                if (coords.x < params.textureSize.x && coords.y < params.textureSize.y) {
                    // 计算纹理坐标 (0.0-1.0)
                    vec2 texCoord = vec2(
                        float(coords.x) / float(params.textureSize.x),
                        float(coords.y) / float(params.textureSize.y)
                    );
                    
                    // 直接使用外部纹理采样读取像素
                    vec4 pixel = texture(inputTexture, texCoord);
                    
                    // 转换为HSV颜色空间
                    vec3 hsv = rgb2hsv(pixel.rgb);
                    
                    // 检测是否为红色
                    if (isRedColor(hsv)) {
                        // 计算亮度（使用HSV的V分量）
                        float brightness = hsv.z;
                        
                        // 也可以使用RGB亮度作为备选
                        float rgbBrightness = dot(pixel.rgb, vec3(0.299, 0.587, 0.114));
                        
                        // 使用更高的亮度值
                        brightness = max(brightness, rgbBrightness);
                        
                        // 使用UBO中的参数进行检测
                        if (brightness > params.threshold && pixel.r > params.minBrightness) {
                            maxBrightness = brightness;
                            maxRedValue = pixel.r;
                            // 归一化坐标
                            maxCoords = vec2(
                                float(coords.x) / float(params.textureSize.x),
                                float(coords.y) / float(params.textureSize.y)
                            );
                        }
                    }
                    
                    // 备选方案：简单的红色检测（用于对比）
                    if (maxBrightness == 0.0) {
                        // 简单的红色偏向检测
                        float redRatio = pixel.r / max(pixel.g + pixel.b + 0.001, 0.001);
                        if (redRatio > 1.2 && pixel.r > params.minBrightness) {
                            maxBrightness = pixel.r;
                            maxRedValue = pixel.r;
                            maxCoords = vec2(
                                float(coords.x) / float(params.textureSize.x),
                                float(coords.y) / float(params.textureSize.y)
                            );
                        }
                    }
                }
                
                // 存储到共享内存
                sharedMaxBrightness[localIndex] = maxBrightness;
                sharedMaxCoords[localIndex] = maxCoords;
                sharedRedValues[localIndex] = maxRedValue;
                
                // 同步工作组
                barrier();
                
                // 在工作组内进行归约，找到最亮的激光点
                for (uint stride = 128u; stride > 0u; stride >>= 1u) {
                    if (localIndex < stride) {
                        uint otherIndex = localIndex + stride;
                        if (sharedMaxBrightness[otherIndex] > sharedMaxBrightness[localIndex]) {
                            sharedMaxBrightness[localIndex] = sharedMaxBrightness[otherIndex];
                            sharedMaxCoords[localIndex] = sharedMaxCoords[otherIndex];
                            sharedRedValues[localIndex] = sharedRedValues[otherIndex];
                        }
                    }
                    barrier();
                }
                
                // 第一个线程将结果写入SSBO
                if (localIndex == 0u && sharedMaxBrightness[0] > 0.0) {
                    // 直接写入最亮的激光点数据
                    laserPoint.laserX = sharedMaxCoords[0].x;
                    laserPoint.laserY = sharedMaxCoords[0].y;
                    laserPoint.brightness = sharedMaxBrightness[0];
                    laserPoint.confidence = min(sharedMaxBrightness[0] * 2.0, 1.0);
                    laserPoint.redValue = sharedRedValues[0];
                    laserPoint.debugInfo = 1.0; // 表示检测到了点
                }
            }
        """
        
        // 优化版激光检测Compute Shader - 全局最亮点检测
        // 使用原子操作确保在所有工作组中找到最亮的激光点
        private const val OPTIMIZED_LASER_DETECT_SHADER = """#version 310 es
            #extension GL_OES_EGL_image_external_essl3 : require
            layout(local_size_x = 16, local_size_y = 16) in;
            
            // 输入：外部纹理采样器
            uniform samplerExternalOES inputTexture;
            
            // 输出：SSBO缓冲区，与原版保持完全相同的格式
            layout(std430, binding = 1) buffer LaserPointBuffer {
                float laserX;      // 激光点X坐标 (归一化 0-1)
                float laserY;      // 激光点Y坐标 (归一化 0-1) 
                float brightness;  // 最大亮度值
                float confidence;  // 检测置信度
                float redValue;    // 红色分量值（用于调试）
                float debugInfo;   // 调试信息
            } laserPoint;
            
            // 全局最亮点检测的原子缓冲区
            layout(std430, binding = 2) buffer GlobalMaxBuffer {
                uint maxBrightnessAsUint;  // 最大亮度值的无符号整数表示（用于原子操作）
                float globalMaxX;          // 全局最亮点X坐标
                float globalMaxY;          // 全局最亮点Y坐标
                float globalMaxBrightness; // 全局最大亮度
                float globalMaxRed;        // 全局最大红色值
                uint lockFlag;             // 原子锁标志
            } globalMax;
            
            // 检测参数，与原版保持相同
            uniform float uThreshold;
            uniform float uMinBrightness;
            uniform ivec2 uTextureSize;
            
            // 优化的共享内存 - 减少数组大小以节省内存带宽
            shared float sharedMaxScore[256];  // 使用综合得分而非多个数组
            shared vec2 sharedMaxCoords[256];
            shared float sharedRedValues[256];
            
            // 将浮点数转换为可用于原子操作的无符号整数
            uint floatToUint(float f) {
                return floatBitsToUint(f);
            }
            
            // 将无符号整数转换回浮点数
            float uintToFloat(uint u) {
                return uintBitsToFloat(u);
            }
            
            // 优化的红色检测函数 - 专注于激光点特征
            float calculateRedScore(vec3 rgb) {
                // 快速红色检测：基于红色优势度和绝对亮度
                float redDominance = rgb.r / max(rgb.g + rgb.b + 0.001, 0.001);
                
                // 红色纯度检测（激光点通常红色纯度很高）
                float redPurity = rgb.r - max(rgb.g, rgb.b);
                
                // 综合得分：优先考虑高亮度的纯红色
                float score = 0.0;
                
                // 条件1：强红色激光特征（高亮度 + 红色优势）
                if (rgb.r > uMinBrightness && redDominance > 1.5) {
                    score = rgb.r * redDominance;
                }
                
                // 条件2：纯红色激光点（高纯度红色）
                if (redPurity > 0.15 && rgb.r > uMinBrightness * 0.7) {
                    score = max(score, rgb.r * redPurity * 3.0);
                }
                
                // 条件3：超高亮度红色（强激光直射）
                if (rgb.r > uThreshold * 1.2) {
                    score = max(score, rgb.r * 2.0);
                }
                
                return score;
            }
            
            void main() {
                ivec2 coords = ivec2(gl_GlobalInvocationID.xy);
                uint localIndex = gl_LocalInvocationIndex;
                
                float maxScore = 0.0;
                vec2 maxCoords = vec2(0.0);
                float maxRedValue = 0.0;
                
                // 边界检查
                if (coords.x < uTextureSize.x && coords.y < uTextureSize.y) {
                    // 计算纹理坐标
                    vec2 texCoord = vec2(
                        float(coords.x) / float(uTextureSize.x),
                        float(coords.y) / float(uTextureSize.y)
                    );
                    
                    // 纹理采样
                    vec4 pixel = texture(inputTexture, texCoord);
                    
                    // 使用优化的红色检测
                    float redScore = calculateRedScore(pixel.rgb);
                    
                    if (redScore > 0.0) {
                        maxScore = redScore;
                        maxRedValue = pixel.r;
                        maxCoords = vec2(
                            float(coords.x) / float(uTextureSize.x),
                            float(coords.y) / float(uTextureSize.y)
                        );
                    }
                }
                
                // 存储到共享内存
                sharedMaxScore[localIndex] = maxScore;
                sharedMaxCoords[localIndex] = maxCoords;
                sharedRedValues[localIndex] = maxRedValue;
                
                // 同步工作组
                barrier();
                
                // 优化的归约操作 - 在工作组内找到最大值
                for (uint stride = 128u; stride > 0u; stride >>= 1u) {
                    if (localIndex < stride) {
                        uint otherIndex = localIndex + stride;
                        if (sharedMaxScore[otherIndex] > sharedMaxScore[localIndex]) {
                            sharedMaxScore[localIndex] = sharedMaxScore[otherIndex];
                            sharedMaxCoords[localIndex] = sharedMaxCoords[otherIndex];
                            sharedRedValues[localIndex] = sharedRedValues[otherIndex];
                        }
                    }
                    barrier();
                }
                
                // 第一个线程负责与全局最大值比较和更新
                if (localIndex == 0u && sharedMaxScore[0] > 0.0) {
                    float currentScore = sharedMaxScore[0];
                    uint currentScoreAsUint = floatToUint(currentScore);
                    
                    // 使用原子操作更新全局最大值
                    uint oldMaxAsUint = atomicMax(globalMax.maxBrightnessAsUint, currentScoreAsUint);
                    
                    // 如果当前值比之前的全局最大值大，则更新其他字段
                    if (currentScore > uintToFloat(oldMaxAsUint)) {
                        // 尝试获取锁来更新其他字段
                        if (atomicCompSwap(globalMax.lockFlag, 0u, 1u) == 0u) {
                            // 成功获取锁，更新全局最大值的其他字段
                            globalMax.globalMaxX = sharedMaxCoords[0].x;
                            globalMax.globalMaxY = sharedMaxCoords[0].y;
                            globalMax.globalMaxBrightness = currentScore;
                            globalMax.globalMaxRed = sharedRedValues[0];
                            
                            // 同时更新主输出缓冲区
                            laserPoint.laserX = sharedMaxCoords[0].x;
                            laserPoint.laserY = sharedMaxCoords[0].y;
                            laserPoint.brightness = currentScore;
                            laserPoint.confidence = min(currentScore * 1.5, 1.0);
                            laserPoint.redValue = sharedRedValues[0];
                            laserPoint.debugInfo = 1.0; // 表示检测到了点
                            
                            // 释放锁
                            atomicExchange(globalMax.lockFlag, 0u);
                        }
                    }
                }
            }
        """
        
        // 基于邻近度的激光点检测 - 考虑上一次检测到的激光点位置，选择距离最近的激光点
        private const val PROXIMITY_BASED_LASER_DETECT_SHADER = """#version 310 es
            #extension GL_OES_EGL_image_external_essl3 : require
            layout(local_size_x = 16, local_size_y = 16) in;
            
            // 输入：外部纹理采样器
            uniform samplerExternalOES inputTexture;
            
            // 输出：SSBO缓冲区，与OPTIMIZED_LASER_DETECT_SHADER保持完全相同的格式
            layout(std430, binding = 1) buffer LaserPointBuffer {
                float laserX;      // 激光点X坐标 (归一化 0-1)
                float laserY;      // 激光点Y坐标 (归一化 0-1) 
                float brightness;  // 最大亮度值
                float confidence;  // 检测置信度
                float redValue;    // 红色分量值（用于调试）
                float debugInfo;   // 调试信息
            } laserPoint;
            
            // 全局邻近度检测的缓冲区 - 简化版本，只存储最佳激光点
            layout(std430, binding = 3) buffer ProximityBuffer {
                float bestScore;            // 最佳得分
                float bestX;               // 最佳X坐标
                float bestY;               // 最佳Y坐标
                float bestRed;             // 最佳红色值
                uint lockFlag;             // 原子锁标志
                uint candidateCount;       // 候选点数量（调试用）
            } proximityData;
            
            // 检测参数，与OPTIMIZED_LASER_DETECT_SHADER保持相同
            uniform float uThreshold;
            uniform float uMinBrightness;
            uniform ivec2 uTextureSize;
            
            // 上一次激光点位置的输入参数
            uniform float uPrevLaserX;     // 上一次激光点X坐标 (归一化 0-1)
            uniform float uPrevLaserY;     // 上一次激光点Y坐标 (归一化 0-1)
            
            // 优化的共享内存
            shared float sharedMaxScore[256];
            shared vec2 sharedMaxCoords[256];
            shared float sharedRedValues[256];
            shared float sharedDistances[256];
            
            // 将浮点数转换为可用于原子操作的无符号整数
            uint floatToUint(float f) {
                return floatBitsToUint(f);
            }
            
            // 将无符号整数转换回浮点数
            float uintToFloat(uint u) {
                return uintBitsToFloat(u);
            }
            
            // 使用与OPTIMIZED_LASER_DETECT_SHADER完全相同的红色检测函数
            float calculateRedScore(vec3 rgb) {
                // 快速红色检测：基于红色优势度和绝对亮度
                float redDominance = rgb.r / max(rgb.g + rgb.b + 0.001, 0.001);
                
                // 红色纯度检测（激光点通常红色纯度很高）
                float redPurity = rgb.r - max(rgb.g, rgb.b);
                
                // 综合得分：优先考虑高亮度的纯红色
                float score = 0.0;
                
                // 条件1：强红色激光特征（高亮度 + 红色优势）
                if (rgb.r > uMinBrightness && redDominance > 1.5) {
                    score = rgb.r * redDominance;
                }
                
                // 条件2：纯红色激光点（高纯度红色）
                if (redPurity > 0.15 && rgb.r > uMinBrightness * 0.7) {
                    score = max(score, rgb.r * redPurity * 3.0);
                }
                
                // 条件3：超高亮度红色（强激光直射）
                if (rgb.r > uThreshold * 1.2) {
                    score = max(score, rgb.r * 2.0);
                }
                
                return score;
            }
            
            // 计算两点之间的距离
            float calculateDistance(vec2 point1, vec2 point2) {
                vec2 diff = point1 - point2;
                return sqrt(diff.x * diff.x + diff.y * diff.y);
            }
            
            void main() {
                ivec2 coords = ivec2(gl_GlobalInvocationID.xy);
                uint localIndex = gl_LocalInvocationIndex;
                
                float maxScore = 0.0;
                vec2 maxCoords = vec2(0.0);
                float maxRedValue = 0.0;
                float minDistance = 999999.0;
                
                // 边界检查
                if (coords.x < uTextureSize.x && coords.y < uTextureSize.y) {
                    // 计算纹理坐标
                    vec2 texCoord = vec2(
                        float(coords.x) / float(uTextureSize.x),
                        float(coords.y) / float(uTextureSize.y)
                    );
                    
                    // 纹理采样
                    vec4 pixel = texture(inputTexture, texCoord);
                    
                    // 使用相同的红色检测逻辑
                    float redScore = calculateRedScore(pixel.rgb);
                    
                    if (redScore > 0.0) {
                        vec2 currentCoords = vec2(
                            float(coords.x) / float(uTextureSize.x),
                            float(coords.y) / float(uTextureSize.y)
                        );
                        
                        // 计算与上一次激光点的距离
                        vec2 prevPosition = vec2(uPrevLaserX, uPrevLaserY);
                        float distance = calculateDistance(currentCoords, prevPosition);
                        
                        maxScore = redScore;
                        maxRedValue = pixel.r;
                        maxCoords = currentCoords;
                        minDistance = distance;
                    }
                }
                
                // 存储到共享内存
                sharedMaxScore[localIndex] = maxScore;
                sharedMaxCoords[localIndex] = maxCoords;
                sharedRedValues[localIndex] = maxRedValue;
                sharedDistances[localIndex] = minDistance;
                
                // 同步工作组
                barrier();
                
                // 优化的归约操作 - 在工作组内找到最佳激光点（优先考虑距离）
                for (uint stride = 128u; stride > 0u; stride >>= 1u) {
                    if (localIndex < stride) {
                        uint otherIndex = localIndex + stride;
                        
                        // 选择距离更近的点，如果距离相同则选择得分更高的点
                        bool shouldUpdate = false;
                        if (sharedMaxScore[otherIndex] > 0.0 && sharedMaxScore[localIndex] > 0.0) {
                            // 两个都是有效候选点，比较距离
                            if (sharedDistances[otherIndex] < sharedDistances[localIndex]) {
                                shouldUpdate = true;
                            } else if (sharedDistances[otherIndex] == sharedDistances[localIndex] && 
                                      sharedMaxScore[otherIndex] > sharedMaxScore[localIndex]) {
                                shouldUpdate = true;
                            }
                        } else if (sharedMaxScore[otherIndex] > 0.0 && sharedMaxScore[localIndex] == 0.0) {
                            // 只有other是有效候选点
                            shouldUpdate = true;
                        }
                        
                        if (shouldUpdate) {
                            sharedMaxScore[localIndex] = sharedMaxScore[otherIndex];
                            sharedMaxCoords[localIndex] = sharedMaxCoords[otherIndex];
                            sharedRedValues[localIndex] = sharedRedValues[otherIndex];
                            sharedDistances[localIndex] = sharedDistances[otherIndex];
                        }
                    }
                    barrier();
                }
                
                // 第一个线程负责与全局缓冲区比较和更新
                if (localIndex == 0u && sharedMaxScore[0] > 0.0) {
                    float currentScore = sharedMaxScore[0];
                    float currentDistance = sharedDistances[0];
                    
                    // 尝试获取锁来更新全局缓冲区
                    if (atomicCompSwap(proximityData.lockFlag, 0u, 1u) == 0u) {
                        // 成功获取锁
                        bool shouldUpdateGlobal = false;
                        
                        if (proximityData.bestScore == 0.0) {
                            // 第一个候选点
                            shouldUpdateGlobal = true;
                        } else {
                            // 比较距离，选择更近的点
                            vec2 globalCoords = vec2(proximityData.bestX, proximityData.bestY);
                            vec2 prevPosition = vec2(uPrevLaserX, uPrevLaserY);
                            float globalDistance = calculateDistance(globalCoords, prevPosition);
                            
                            if (currentDistance < globalDistance) {
                                shouldUpdateGlobal = true;
                            } else if (currentDistance == globalDistance && currentScore > proximityData.bestScore) {
                                shouldUpdateGlobal = true;
                            }
                        }
                        
                        if (shouldUpdateGlobal) {
                            proximityData.bestScore = currentScore;
                            proximityData.bestX = sharedMaxCoords[0].x;
                            proximityData.bestY = sharedMaxCoords[0].y;
                            proximityData.bestRed = sharedRedValues[0];
                            
                            // 更新主输出缓冲区
                            laserPoint.laserX = sharedMaxCoords[0].x;
                            laserPoint.laserY = sharedMaxCoords[0].y;
                            laserPoint.brightness = currentScore;
                            laserPoint.confidence = min(currentScore * 1.5, 1.0);
                            laserPoint.redValue = sharedRedValues[0];
                            laserPoint.debugInfo = 1.0; // 表示检测到了点
                        }
                        
                        // 增加候选点计数
                        proximityData.candidateCount++;
                        
                        // 释放锁
                        atomicExchange(proximityData.lockFlag, 0u);
                    }
                }
            }
        """
    }
    
    // GPU资源
    private var computeProgram = 0
    private var optimizedComputeProgram = 0  // 优化版激光检测程序
    private var clearProgram = 0
    private var clearGlobalProgram = 0  // 清零GlobalMaxBuffer的程序
    private var clearProximityProgram = 0  // 清零ProximityBuffer的程序
    private var ssboBuffer = 0
    private var globalMaxBuffer = 0  // 全局最大值缓冲区（用于原子操作）
    
    // 邻近度检测相关资源
    private var proximityComputeProgram = 0  // 邻近度检测程序
    private var proximityBuffer = 0  // 邻近度检测缓冲区
    private var proximityData: FloatBuffer? = null  // 邻近度数据缓冲区
    
    // 原有的控制变量 - 保持兼容性，但建议使用新的 currentDetectionMode
    @Deprecated("使用 currentDetectionMode 替代")
    private var useProximityMode = true  // 是否启用邻近度检测模式
    private var lastLaserX = 0.5f  // 上一次激光点X坐标
    private var lastLaserY = 0.5f  // 上一次激光点Y坐标
    
    // 调试模式
    private var debugComputeProgram = 0
    private var useDebugMode = false
    
    // 测试模式
    private var testComputeProgram = 0
    private var useTestMode = false
    
    // 纹理测试模式
    private var textureTestProgram = 0
    private var useTextureTestMode = false
    
    // SSBO验证模式
    private var ssboVerifyProgram = 0
    private var useSSBOVerifyMode = false
    
    // SSBO数据缓冲区
    private var ssboData: FloatBuffer? = null
    private var globalMaxData: FloatBuffer? = null  // 全局最大值数据缓冲区
    
    // 检测参数
    private var width = 0
    private var height = 0
    private var initialized = false
    
    // 设备能力检测
    private var supportsBufferMapping = false
    private var supportsExternalTextureInCompute = false
    
    // 性能统计
    private var lastProcessTime = 0L
    private val processTimesMs = mutableListOf<Float>()
    
    // 缓存的Uniform位置（避免重复获取）
    private var cachedThresholdLocation = -1
    private var cachedMinBrightnessLocation = -1
    private var cachedTextureSizeLocation = -1
    private var cachedTextureLocation = -1
    private var uniformLocationsCached = false
    
    // 性能优化选项
    private var enablePerformanceOptimizations = true
    private var skipRedundantGLCalls = true
    private var useMinimalSynchronization = true  // 启用最小同步
    private var enableFastMode = true  // 默认启用快速模式以获得最佳性能
    private var useUBOMode = false  // 启用UBO模式测试
    
    @Deprecated("使用 currentDetectionMode 替代")
    private var useOptimizedMode = false  // 默认使用优化版本激光检测
    
    // UBO相关变量
    private var uboBuffer = 0
    private var uboData: FloatBuffer? = null
    
    // UBO模式相关变量
    private var uboComputeProgram = 0
    
    // 纹理输出模式相关变量
    private var outputTexture = 0
    private var outputFBO = 0
    private var useTextureOutput = false
    private var textureOutputProgram = 0
    
    // ========== Fallback模式相关代码 ==========
    
    // Fallback模式资源
    private var fallbackMode = false
    private var conversionFBO = 0
    private var conversionTexture = 0
    private var conversionProgram = 0
    private var conversionVertexBuffer: FloatBuffer? = null
    private var conversionTexCoordBuffer: FloatBuffer? = null
    
    // Fallback模式的着色器
    private val FALLBACK_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = aTextureCoord;
        }
    """
    
    private val FALLBACK_FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """
    
    // Fallback模式的Compute Shader（使用常规2D纹理）
    private val FALLBACK_LASER_DETECT_SHADER = """#version 310 es
        layout(local_size_x = 16, local_size_y = 16) in;
        
        // 使用常规2D纹理采样器
        uniform sampler2D inputTexture;
        
        // SSBO输出缓冲区：存储激光点坐标和亮度
        layout(std430, binding = 1) buffer LaserPointBuffer {
            float laserX;      // 激光点X坐标 (归一化 0-1)
            float laserY;      // 激光点Y坐标 (归一化 0-1) 
            float brightness;  // 最大亮度值
            float confidence;  // 检测置信度
            float redValue;    // 红色分量值（用于调试）
            float debugInfo;   // 调试信息
        } laserPoint;
        
        // 检测参数
        uniform float uThreshold;
        uniform float uMinBrightness;
        uniform ivec2 uTextureSize;
        
        // 共享内存用于工作组内的最大值归约
        shared float sharedMaxBrightness[256]; // 16x16 = 256
        shared vec2 sharedMaxCoords[256];
        shared float sharedRedValues[256];
        
        // RGB转HSV函数（与主Shader相同）
        vec3 rgb2hsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }
        
        // 检测是否为红色（与主Shader相同）
        bool isRedColor(vec3 hsv) {
            float h = hsv.x * 360.0; // 转换为0-360度
            float s = hsv.y * 255.0; // 转换为0-255
            float v = hsv.z * 255.0; // 转换为0-255
            
            // 红色范围1: H=[0,10], S=[100,255], V=[20,255]
            bool range1 = (h >= 0.0 && h <= 10.0) && (s >= 100.0) && (v >= 20.0);
            
            // 红色范围2: H=[160,179], S=[100,255], V=[20,255]  
            bool range2 = (h >= 160.0 && h <= 179.0) && (s >= 100.0) && (v >= 20.0);
            
            return range1 || range2;
        }
        
        void main() {
            ivec2 coords = ivec2(gl_GlobalInvocationID.xy);
            uint localIndex = gl_LocalInvocationIndex;
            
            float maxBrightness = 0.0;
            vec2 maxCoords = vec2(0.0);
            float maxRedValue = 0.0;
            
            // 边界检查
            if (coords.x < uTextureSize.x && coords.y < uTextureSize.y) {
                // 计算纹理坐标 (0.0-1.0)
                vec2 texCoord = vec2(
                    float(coords.x) / float(uTextureSize.x),
                    float(coords.y) / float(uTextureSize.y)
                );
                
                // 使用常规纹理采样读取像素
                vec4 pixel = texture(inputTexture, texCoord);
                
                // 转换为HSV颜色空间
                vec3 hsv = rgb2hsv(pixel.rgb);
                
                // 检测是否为红色
                if (isRedColor(hsv)) {
                    // 计算亮度（使用HSV的V分量）
                    float brightness = hsv.z;
                    
                    // 也可以使用RGB亮度作为备选
                    float rgbBrightness = dot(pixel.rgb, vec3(0.299, 0.587, 0.114));
                    
                    // 使用更高的亮度值
                    brightness = max(brightness, rgbBrightness);
                    
                    // 降低阈值以便调试，检测更多的红色点
                    if (brightness > 0.1 && pixel.r > 0.1) { // 降低阈值
                        maxBrightness = brightness;
                        maxRedValue = pixel.r;
                        // 归一化坐标
                        maxCoords = vec2(
                            float(coords.x) / float(uTextureSize.x),
                            float(coords.y) / float(uTextureSize.y)
                        );
                    }
                }
                
                // 备选方案：简单的红色检测（用于对比）
                if (maxBrightness == 0.0) {
                    // 简单的红色偏向检测
                    float redRatio = pixel.r / max(pixel.g + pixel.b + 0.001, 0.001);
                    if (redRatio > 1.2 && pixel.r > 0.3) { // 进一步降低阈值
                        maxBrightness = pixel.r;
                        maxRedValue = pixel.r;
                        maxCoords = vec2(
                            float(coords.x) / float(uTextureSize.x),
                            float(coords.y) / float(uTextureSize.y)
                        );
                    }
                }
            }
            
            // 存储到共享内存
            sharedMaxBrightness[localIndex] = maxBrightness;
            sharedMaxCoords[localIndex] = maxCoords;
            sharedRedValues[localIndex] = maxRedValue;
            
            // 同步工作组
            barrier();
            
            // 在工作组内进行归约，找到最亮的激光点
            for (uint stride = 128u; stride > 0u; stride >>= 1u) {
                if (localIndex < stride) {
                    uint otherIndex = localIndex + stride;
                    if (sharedMaxBrightness[otherIndex] > sharedMaxBrightness[localIndex]) {
                        sharedMaxBrightness[localIndex] = sharedMaxBrightness[otherIndex];
                        sharedMaxCoords[localIndex] = sharedMaxCoords[otherIndex];
                        sharedRedValues[localIndex] = sharedRedValues[otherIndex];
                    }
                }
                barrier();
            }
            
            // 第一个线程将结果写入SSBO
            if (localIndex == 0u && sharedMaxBrightness[0] > 0.0) {
                // 直接写入最亮的激光点数据
                laserPoint.laserX = sharedMaxCoords[0].x;
                laserPoint.laserY = sharedMaxCoords[0].y;
                laserPoint.brightness = sharedMaxBrightness[0];
                laserPoint.confidence = min(sharedMaxBrightness[0] * 2.0, 1.0);
                laserPoint.redValue = sharedRedValues[0];
                laserPoint.debugInfo = 1.0; // 表示检测到了点
            }
        }
    """
    
    /**
     * 初始化GPU检测器
     */
    fun initialize(width: Int, height: Int): Boolean {
        this.width = width
        this.height = height
        
        try {
            // 检查OpenGL ES 3.1支持
            val version = GLES20.glGetString(GLES20.GL_VERSION)
            LogManager.i(TAG, "OpenGL版本: $version")
            
            if (version?.contains("OpenGL ES 3.1") != true && version?.contains("OpenGL ES 3.2") != true) {
                LogManager.e(TAG, "需要OpenGL ES 3.1+支持，当前版本: $version")
                return false
            }
            
            // 检查外部纹理在Compute Shader中的支持
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
            supportsExternalTextureInCompute = extensions?.contains("GL_OES_EGL_image_external_essl3") == true

            LogManager.i(TAG, "设备支持在Compute Shader中直接使用外部纹理")
            
            // 查询GPU硬件能力
            queryGPUCapabilities()
            
            // 检查Compute Shader和SSBO支持
            try {
                val testShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
                if (testShader == 0) {
                    LogManager.e(TAG, "设备不支持Compute Shader")
                    return false
                }
                GLES31.glDeleteShader(testShader)
                
                // 检查SSBO支持
                val maxSSBOBindings = IntArray(1)
                GLES31.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS, maxSSBOBindings, 0)
                if (maxSSBOBindings[0] < 2) {
                    LogManager.e(TAG, "设备SSBO绑定点不足: ${maxSSBOBindings[0]}")
                    return false
                }
                
                LogManager.i(TAG, "Compute Shader和SSBO支持检查通过")
            } catch (e: Exception) {
                LogManager.e(TAG, "Compute Shader或SSBO不可用: ${e.message}")
                return false
            }
            
            initializeGPUResources()
            initialized = true
            LogManager.i(TAG, "GPU激光检测器初始化成功: ${width}x${height} (直接外部纹理模式)")
            return true
            
        } catch (e: Exception) {
            LogManager.e(TAG, "初始化失败: ${e.message}")
            e.printStackTrace()
            release()
            return false
        }
    }
    
    /**
     * 初始化GPU资源
     */
    private fun initializeGPUResources() {
        LogManager.d(TAG, "开始初始化GPU资源...")

        uniformLocationsCached = false
        cachedTextureLocation = -1
        
        // 保存当前OpenGL状态
        val savedProgram = IntArray(1)
        val savedBuffer = IntArray(1)
        
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, savedProgram, 0)
        GLES31.glGetIntegerv(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING, savedBuffer, 0)
        
        try {
            // 1. 创建并编译主检测Compute Shader
            val computeShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(computeShader, LASER_DETECT_SHADER)
            GLES31.glCompileShader(computeShader)
            
            val compileStatus = IntArray(1)
            GLES31.glGetShaderiv(computeShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(computeShader)
                LogManager.e(TAG, "主检测Compute shader编译失败: $log")
                throw RuntimeException("主检测Compute shader编译失败")
            }
            
            computeProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(computeProgram, computeShader)
            GLES31.glLinkProgram(computeProgram)
            
            val linkStatus = IntArray(1)
            GLES31.glGetProgramiv(computeProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(computeProgram)
                LogManager.e(TAG, "主检测程序链接失败: $log")
                throw RuntimeException("主检测程序链接失败")
            }
            GLES31.glDeleteShader(computeShader)
            
            // 2. 创建并编译清零Compute Shader
            val clearShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(clearShader, CLEAR_BUFFER_SHADER)
            GLES31.glCompileShader(clearShader)
            
            GLES31.glGetShaderiv(clearShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(clearShader)
                LogManager.e(TAG, "清零Compute shader编译失败: $log")
                throw RuntimeException("清零Compute shader编译失败")
            }
            
            clearProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(clearProgram, clearShader)
            GLES31.glLinkProgram(clearProgram)
            
            GLES31.glGetProgramiv(clearProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(clearProgram)
                LogManager.e(TAG, "清零程序链接失败: $log")
                throw RuntimeException("清零程序链接失败")
            }
            GLES31.glDeleteShader(clearShader)
            
            // 3. 创建并编译清零GlobalMaxBuffer的Compute Shader
            val clearGlobalShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(clearGlobalShader, CLEAR_GLOBAL_BUFFER_SHADER)
            GLES31.glCompileShader(clearGlobalShader)
            
            GLES31.glGetShaderiv(clearGlobalShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(clearGlobalShader)
                LogManager.e(TAG, "清零GlobalMaxBuffer Compute shader编译失败: $log")
                throw RuntimeException("清零GlobalMaxBuffer Compute shader编译失败")
            }
            
            clearGlobalProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(clearGlobalProgram, clearGlobalShader)
            GLES31.glLinkProgram(clearGlobalProgram)
            
            GLES31.glGetProgramiv(clearGlobalProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(clearGlobalProgram)
                LogManager.e(TAG, "清零GlobalMaxBuffer程序链接失败: $log")
                throw RuntimeException("清零GlobalMaxBuffer程序链接失败")
            }
            GLES31.glDeleteShader(clearGlobalShader)
            
            // 4. 创建并编译调试Compute Shader
            val debugShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(debugShader, DEBUG_LASER_DETECT_SHADER)
            GLES31.glCompileShader(debugShader)
            
            GLES31.glGetShaderiv(debugShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(debugShader)
                LogManager.e(TAG, "调试Compute shader编译失败: $log")
                throw RuntimeException("调试Compute shader编译失败")
            }
            
            debugComputeProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(debugComputeProgram, debugShader)
            GLES31.glLinkProgram(debugComputeProgram)
            
            GLES31.glGetProgramiv(debugComputeProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(debugComputeProgram)
                LogManager.e(TAG, "调试程序链接失败: $log")
                throw RuntimeException("调试程序链接失败")
            }
            GLES31.glDeleteShader(debugShader)
            
            // 5. 创建并编译测试Compute Shader
            val testShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(testShader, TEST_COMPUTE_SHADER)
            GLES31.glCompileShader(testShader)
            
            GLES31.glGetShaderiv(testShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(testShader)
                LogManager.e(TAG, "测试Compute shader编译失败: $log")
                throw RuntimeException("测试Compute shader编译失败")
            }
            
            testComputeProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(testComputeProgram, testShader)
            GLES31.glLinkProgram(testComputeProgram)
            
            GLES31.glGetProgramiv(testComputeProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(testComputeProgram)
                LogManager.e(TAG, "测试程序链接失败: $log")
                throw RuntimeException("测试程序链接失败")
            }
            GLES31.glDeleteShader(testShader)
            
            // 6. 创建并编译纹理测试Compute Shader
            val textureTestShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(textureTestShader, TEXTURE_TEST_SHADER)
            GLES31.glCompileShader(textureTestShader)
            
            GLES31.glGetShaderiv(textureTestShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(textureTestShader)
                LogManager.e(TAG, "纹理测试Compute shader编译失败: $log")
                throw RuntimeException("纹理测试Compute shader编译失败")
            }
            
            textureTestProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(textureTestProgram, textureTestShader)
            GLES31.glLinkProgram(textureTestProgram)
            
            GLES31.glGetProgramiv(textureTestProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(textureTestProgram)
                LogManager.e(TAG, "纹理测试程序链接失败: $log")
                throw RuntimeException("纹理测试程序链接失败")
            }
            GLES31.glDeleteShader(textureTestShader)
            
            // 7. 创建并编译SSBO验证Compute Shader
            val ssboVerifyShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(ssboVerifyShader, SSBO_VERIFY_SHADER)
            GLES31.glCompileShader(ssboVerifyShader)
            
            GLES31.glGetShaderiv(ssboVerifyShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(ssboVerifyShader)
                LogManager.e(TAG, "SSBO验证Compute shader编译失败: $log")
                throw RuntimeException("SSBO验证Compute shader编译失败")
            }
            
            ssboVerifyProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(ssboVerifyProgram, ssboVerifyShader)
            GLES31.glLinkProgram(ssboVerifyProgram)
            
            GLES31.glGetProgramiv(ssboVerifyProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(ssboVerifyProgram)
                LogManager.e(TAG, "SSBO验证程序链接失败: $log")
                throw RuntimeException("SSBO验证程序链接失败")
            }
            GLES31.glDeleteShader(ssboVerifyShader)
            
            // 8. 创建并编译UBO版本的Compute Shader
            val uboShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(uboShader, UBO_LASER_DETECT_SHADER)
            GLES31.glCompileShader(uboShader)
            
            GLES31.glGetShaderiv(uboShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(uboShader)
                LogManager.e(TAG, "UBO Compute shader编译失败: $log")
                throw RuntimeException("UBO Compute shader编译失败")
            }
            
            uboComputeProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(uboComputeProgram, uboShader)
            GLES31.glLinkProgram(uboComputeProgram)
            
            GLES31.glGetProgramiv(uboComputeProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(uboComputeProgram)
                LogManager.e(TAG, "UBO程序链接失败: $log")
                throw RuntimeException("UBO程序链接失败")
            }
            GLES31.glDeleteShader(uboShader)
            
            // 9. 创建并编译优化版激光检测Compute Shader
            val optimizedShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(optimizedShader, OPTIMIZED_LASER_DETECT_SHADER)
            GLES31.glCompileShader(optimizedShader)
            
            GLES31.glGetShaderiv(optimizedShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(optimizedShader)
                LogManager.e(TAG, "优化版Compute shader编译失败: $log")
                throw RuntimeException("优化版Compute shader编译失败")
            }
            
            optimizedComputeProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(optimizedComputeProgram, optimizedShader)
            GLES31.glLinkProgram(optimizedComputeProgram)
            
            GLES31.glGetProgramiv(optimizedComputeProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(optimizedComputeProgram)
                LogManager.e(TAG, "优化版程序链接失败: $log")
                throw RuntimeException("优化版程序链接失败")
            }
            GLES31.glDeleteShader(optimizedShader)
            
            LogManager.i(TAG, "优化版激光检测Compute Shader初始化完成")
            
            // 9.5. 创建并编译邻近度检测Compute Shader
            val proximityShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(proximityShader, PROXIMITY_BASED_LASER_DETECT_SHADER)
            GLES31.glCompileShader(proximityShader)
            
            GLES31.glGetShaderiv(proximityShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(proximityShader)
                LogManager.e(TAG, "邻近度检测Compute shader编译失败: $log")
                throw RuntimeException("邻近度检测Compute shader编译失败")
            }
            
            proximityComputeProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(proximityComputeProgram, proximityShader)
            GLES31.glLinkProgram(proximityComputeProgram)
            
            GLES31.glGetProgramiv(proximityComputeProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(proximityComputeProgram)
                LogManager.e(TAG, "邻近度检测程序链接失败: $log")
                throw RuntimeException("邻近度检测程序链接失败")
            }
            GLES31.glDeleteShader(proximityShader)
            
            LogManager.i(TAG, "邻近度检测Compute Shader初始化完成")
            
            // 9.8. 创建并编译清零邻近度缓冲区的Compute Shader
            val clearProximityShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(clearProximityShader, CLEAR_PROXIMITY_BUFFER_SHADER)
            GLES31.glCompileShader(clearProximityShader)
            
            GLES31.glGetShaderiv(clearProximityShader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES31.glGetShaderInfoLog(clearProximityShader)
                LogManager.e(TAG, "清零邻近度缓冲区Compute shader编译失败: $log")
                throw RuntimeException("清零邻近度缓冲区Compute shader编译失败")
            }
            
            clearProximityProgram = GLES31.glCreateProgram()
            GLES31.glAttachShader(clearProximityProgram, clearProximityShader)
            GLES31.glLinkProgram(clearProximityProgram)
            
            GLES31.glGetProgramiv(clearProximityProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES31.glGetProgramInfoLog(clearProximityProgram)
                LogManager.e(TAG, "清零邻近度缓冲区程序链接失败: $log")
                throw RuntimeException("清零邻近度缓冲区程序链接失败")
            }
            GLES31.glDeleteShader(clearProximityShader)
            
            LogManager.i(TAG, "清零邻近度缓冲区Compute Shader初始化完成")
            
            // 10. 创建SSBO缓冲区
            val buffers = IntArray(1)
            GLES31.glGenBuffers(1, buffers, 0)
            ssboBuffer = buffers[0]
            
            // 创建SSBO数据：6个float (x, y, brightness, confidence, redValue, debugInfo)
            ssboData = ByteBuffer.allocateDirect(6 * 4) // 6 floats * 4 bytes
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboBuffer)
            
            // 明确分配内存并设置初始值为0
            val initialData = ByteBuffer.allocateDirect(24) // 6 floats * 4 bytes
                .order(ByteOrder.nativeOrder())
            for (i in 0 until 24) {
                initialData.put(i, 0) // 全部初始化为0
            }
            initialData.position(0)
            
            // 使用 GL_DYNAMIC_COPY 标志，支持读写操作
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 24, initialData, GLES31.GL_DYNAMIC_COPY)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboBuffer)
            
            // 验证SSBO创建是否成功
            checkGLError("SSBO缓冲区创建")
            
            // 验证缓冲区大小
            val bufferSize = IntArray(1)
            GLES31.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER, GLES31.GL_BUFFER_SIZE, bufferSize, 0)
            LogManager.d(TAG, "SSBO缓冲区创建完成，ID: $ssboBuffer, 大小: ${bufferSize[0]} bytes")
            
            if (bufferSize[0] != 24) {
                throw RuntimeException("SSBO缓冲区大小不正确: ${bufferSize[0]} (期望: 24)")
            }
            
            // 11. 创建全局最大值缓冲区（GlobalMaxBuffer）
            val globalBuffers = IntArray(1)
            GLES31.glGenBuffers(1, globalBuffers, 0)
            globalMaxBuffer = globalBuffers[0]
            
            // 创建GlobalMaxBuffer数据：6个值 (maxBrightnessAsUint, globalMaxX, globalMaxY, globalMaxBrightness, globalMaxRed, lockFlag)
            // 布局：uint, float, float, float, float, uint (6 * 4 = 24 bytes)
            globalMaxData = ByteBuffer.allocateDirect(6 * 4) // 6 * 4 bytes
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, globalMaxBuffer)
            
            // 明确分配内存并设置初始值为0
            val globalInitialData = ByteBuffer.allocateDirect(24) // 6 * 4 bytes
                .order(ByteOrder.nativeOrder())
            for (i in 0 until 24) {
                globalInitialData.put(i, 0) // 全部初始化为0
            }
            globalInitialData.position(0)
            
            // 使用 GL_DYNAMIC_COPY 标志，支持读写操作
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 24, globalInitialData, GLES31.GL_DYNAMIC_COPY)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, globalMaxBuffer)
            
            // 验证GlobalMaxBuffer创建是否成功
            checkGLError("GlobalMaxBuffer缓冲区创建")
            
            // 验证缓冲区大小
            val globalBufferSize = IntArray(1)
            GLES31.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER, GLES31.GL_BUFFER_SIZE, globalBufferSize, 0)
            LogManager.d(TAG, "GlobalMaxBuffer缓冲区创建完成，ID: $globalMaxBuffer, 大小: ${globalBufferSize[0]} bytes")
            
            if (globalBufferSize[0] != 24) {
                throw RuntimeException("GlobalMaxBuffer缓冲区大小不正确: ${globalBufferSize[0]} (期望: 24)")
            }
            
            // 11.5. 创建邻近度检测缓冲区（ProximityBuffer）
            val proximityBuffers = IntArray(1)
            GLES31.glGenBuffers(1, proximityBuffers, 0)
            proximityBuffer = proximityBuffers[0]
            
            // 创建ProximityBuffer数据：
            // prevLaserX(4), prevLaserY(4), candidateCount(4), lockFlag(4), 
            // candidateX[16](64), candidateY[16](64), candidateScore[16](64), candidateRed[16](64)
            // 总计：4 + 64*4 = 260 bytes，需要对齐到4字节边界，使用264 bytes
            val proximityBufferSize = 66 * 4 // 66 个 float/uint 值
            proximityData = ByteBuffer.allocateDirect(proximityBufferSize)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, proximityBuffer)
            
            // 明确分配内存并设置初始值
            val proximityInitialData = ByteBuffer.allocateDirect(proximityBufferSize)
                .order(ByteOrder.nativeOrder())
            
            // 设置初始值
            proximityInitialData.asFloatBuffer().apply {
                put(0, 0.5f)  // prevLaserX，初始化为屏幕中心
                put(1, 0.5f)  // prevLaserY，初始化为屏幕中心
                put(2, 0f)    // candidateCount，初始化为0
                put(3, 0f)    // lockFlag，初始化为0
                // 其余的candidateX、candidateY、candidateScore、candidateRed数组都初始化为0
                for (i in 4 until 66) {
                    put(i, 0f)
                }
            }
            proximityInitialData.position(0)
            
            // 使用 GL_DYNAMIC_COPY 标志，支持读写操作
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, proximityBufferSize, proximityInitialData, GLES31.GL_DYNAMIC_COPY)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, proximityBuffer)  // 使用绑定点3
            
            // 验证ProximityBuffer创建是否成功
            checkGLError("ProximityBuffer缓冲区创建")
            
            // 验证缓冲区大小
            val proximityBufferSizeCheck = IntArray(1)
            GLES31.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER, GLES31.GL_BUFFER_SIZE, proximityBufferSizeCheck, 0)
            LogManager.d(TAG, "ProximityBuffer缓冲区创建完成，ID: $proximityBuffer, 大小: ${proximityBufferSizeCheck[0]} bytes")
            
            if (proximityBufferSizeCheck[0] != proximityBufferSize) {
                throw RuntimeException("ProximityBuffer缓冲区大小不正确: ${proximityBufferSizeCheck[0]} (期望: $proximityBufferSize)")
            }
            
            // 11. 创建UBO缓冲区用于传递参数
            val uboBuffers = IntArray(1)
            GLES31.glGenBuffers(1, uboBuffers, 0)
            uboBuffer = uboBuffers[0]
            
            // 创建UBO数据：6个float (threshold, minBrightness, textureSize.x, textureSize.y, reserved1, reserved2)
            // 按照std140布局，需要16字节对齐
            uboData = ByteBuffer.allocateDirect(6 * 4) // 6 floats * 4 bytes
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            
            GLES31.glBindBuffer(GLES31.GL_UNIFORM_BUFFER, uboBuffer)
            
            // 分配UBO内存
            val uboInitialData = ByteBuffer.allocateDirect(24) // 6 floats * 4 bytes
                .order(ByteOrder.nativeOrder())
            // 设置默认参数
            uboInitialData.asFloatBuffer().apply {
                put(0, 0.8f)  // threshold
                put(1, 0.7f)  // minBrightness
                put(2, width.toFloat())  // textureSize.x
                put(3, height.toFloat()) // textureSize.y
                put(4, 0.0f)  // reserved1
                put(5, 0.0f)  // reserved2
            }
            uboInitialData.position(0)
            
            // 使用 GL_DYNAMIC_DRAW 标志，支持频繁更新
            GLES31.glBufferData(GLES31.GL_UNIFORM_BUFFER, 24, uboInitialData, GLES31.GL_DYNAMIC_DRAW)
            GLES31.glBindBufferBase(GLES31.GL_UNIFORM_BUFFER, 0, uboBuffer)
            
            // 验证UBO创建是否成功
            checkGLError("UBO缓冲区创建")
            
            // 验证UBO缓冲区大小
            val uboBufferSize = IntArray(1)
            GLES31.glGetBufferParameteriv(GLES31.GL_UNIFORM_BUFFER, GLES31.GL_BUFFER_SIZE, uboBufferSize, 0)
            LogManager.d(TAG, "UBO缓冲区创建完成，ID: $uboBuffer, 大小: ${uboBufferSize[0]} bytes")
            
            if (uboBufferSize[0] != 24) {
                throw RuntimeException("UBO缓冲区大小不正确: ${uboBufferSize[0]} (期望: 24)")
            }
            
            // 测试缓冲区映射是否可用
            val testMappedBuffer = GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                0,
                24,
                GLES31.GL_MAP_READ_BIT or GLES31.GL_MAP_WRITE_BIT
            )
            if (testMappedBuffer != null) {
                GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                supportsBufferMapping = true
                LogManager.i(TAG, "SSBO缓冲区映射测试成功")
            } else {
                supportsBufferMapping = false
                LogManager.w(TAG, "设备不支持SSBO缓冲区映射，将使用fallback方案")
            }
            
            checkGLError("GPU资源初始化")
            LogManager.i(TAG, "GPU资源初始化完成 - 使用直接外部纹理SSBO纯GPU计算")
            
        } catch (e: Exception) {
            LogManager.e(TAG, "GPU资源初始化失败: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            // 恢复之前的OpenGL状态
            GLES20.glUseProgram(savedProgram[0])
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, savedBuffer[0])
            
            LogManager.d(TAG, "OpenGL状态已恢复")
        }
    }
    
    /**
     * 检查OpenGL错误
     */
    private fun checkGLError(operation: String) {
        val error = GLES31.glGetError()
        if (error != GLES31.GL_NO_ERROR) {
            LogManager.e(TAG, "$operation: OpenGL错误 $error")
            throw RuntimeException("$operation: OpenGL错误 $error")
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        // 检查当前线程是否有有效的OpenGL上下文
        val glVersion = try {
            GLES20.glGetString(GLES20.GL_VERSION)
        } catch (e: Exception) {
            LogManager.w(TAG, "获取OpenGL版本失败，可能不在OpenGL上下文中: ${e.message}")
            null
        }
        
        if (glVersion == null) {
            LogManager.w(TAG, "没有有效的OpenGL上下文，跳过OpenGL资源释放")
            // 仍然释放非OpenGL资源
            ssboData = null
            globalMaxData = null
            proximityData = null
            initialized = false
            fallbackMode = false
            LogManager.i(TAG, "GPU检测器资源释放完成（跳过OpenGL资源）")
            return
        }
        
        LogManager.d(TAG, "在有效的OpenGL上下文中释放GPU检测器资源，OpenGL版本: $glVersion")
        
        try {
        // 释放所有Compute Shader程序
        if (computeProgram != 0) {
            GLES31.glDeleteProgram(computeProgram)
            computeProgram = 0
            LogManager.d(TAG, "computeProgram已释放")
        }
        
        if (optimizedComputeProgram != 0) {
            GLES31.glDeleteProgram(optimizedComputeProgram)
            optimizedComputeProgram = 0
            LogManager.d(TAG, "optimizedComputeProgram已释放")
        }
        
        if (clearProgram != 0) {
            GLES31.glDeleteProgram(clearProgram)
            clearProgram = 0
            LogManager.d(TAG, "clearProgram已释放")
        }
        
        if (clearGlobalProgram != 0) {
            GLES31.glDeleteProgram(clearGlobalProgram)
            clearGlobalProgram = 0
            LogManager.d(TAG, "clearGlobalProgram已释放")
        }
        
        if (clearProximityProgram != 0) {
            GLES31.glDeleteProgram(clearProximityProgram)
            clearProximityProgram = 0
            LogManager.d(TAG, "clearProximityProgram已释放")
        }
        
        if (debugComputeProgram != 0) {
            GLES31.glDeleteProgram(debugComputeProgram)
            debugComputeProgram = 0
            LogManager.d(TAG, "debugComputeProgram已释放")
        }
        
        if (testComputeProgram != 0) {
            GLES31.glDeleteProgram(testComputeProgram)
            testComputeProgram = 0
            LogManager.d(TAG, "testComputeProgram已释放")
        }
        
        if (textureTestProgram != 0) {
            GLES31.glDeleteProgram(textureTestProgram)
            textureTestProgram = 0
            LogManager.d(TAG, "textureTestProgram已释放")
        }
        
        if (ssboVerifyProgram != 0) {
            GLES31.glDeleteProgram(ssboVerifyProgram)
            ssboVerifyProgram = 0
            LogManager.d(TAG, "ssboVerifyProgram已释放")
        }
        
        if (uboComputeProgram != 0) {
            GLES31.glDeleteProgram(uboComputeProgram)
            uboComputeProgram = 0
            LogManager.d(TAG, "uboComputeProgram已释放")
        }
        
        if (textureOutputProgram != 0) {
            GLES31.glDeleteProgram(textureOutputProgram)
            textureOutputProgram = 0
            LogManager.d(TAG, "textureOutputProgram已释放")
        }
        
        if (proximityComputeProgram != 0) {
            GLES31.glDeleteProgram(proximityComputeProgram)
            proximityComputeProgram = 0
            LogManager.d(TAG, "proximityComputeProgram已释放")
        }
        
        // 释放所有缓冲区对象
        if (ssboBuffer != 0) {
            GLES31.glDeleteBuffers(1, intArrayOf(ssboBuffer), 0)
            ssboBuffer = 0
            LogManager.d(TAG, "ssboBuffer已释放")
        }
        
        if (globalMaxBuffer != 0) {
            GLES31.glDeleteBuffers(1, intArrayOf(globalMaxBuffer), 0)
            globalMaxBuffer = 0
            LogManager.d(TAG, "globalMaxBuffer已释放")
        }
        
        if (uboBuffer != 0) {
            GLES31.glDeleteBuffers(1, intArrayOf(uboBuffer), 0)
            uboBuffer = 0
            LogManager.d(TAG, "uboBuffer已释放")
        }
        
        if (proximityBuffer != 0) {
            GLES31.glDeleteBuffers(1, intArrayOf(proximityBuffer), 0)
            proximityBuffer = 0
            LogManager.d(TAG, "proximityBuffer已释放")
        }
        
        // 释放纹理输出模式相关资源
        if (outputTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(outputTexture), 0)
            outputTexture = 0
            LogManager.d(TAG, "outputTexture已释放")
        }
        
        if (outputFBO != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(outputFBO), 0)
            outputFBO = 0
            LogManager.d(TAG, "outputFBO已释放")
        }
            
            // 释放Fallback模式资源
            if (conversionProgram != 0) {
                GLES20.glDeleteProgram(conversionProgram)
                conversionProgram = 0
                LogManager.d(TAG, "conversionProgram已释放")
            }
            
            if (conversionFBO != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(conversionFBO), 0)
                conversionFBO = 0
                LogManager.d(TAG, "conversionFBO已释放")
            }
            
            if (conversionTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(conversionTexture), 0)
                conversionTexture = 0
                LogManager.d(TAG, "conversionTexture已释放")
            }
            
            conversionVertexBuffer = null
            conversionTexCoordBuffer = null
            LogManager.d(TAG, "conversionBuffers已释放")
        
            // 检查OpenGL错误
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                LogManager.w(TAG, "释放GPU检测器资源后发现OpenGL错误: $error")
            } else {
                LogManager.d(TAG, "GPU检测器资源释放成功，无OpenGL错误")
            }
            
        } catch (e: Exception) {
            LogManager.e(TAG, "释放GPU检测器资源时发生异常: ${e.message}")
            e.printStackTrace()
        }
        
        // 释放非OpenGL资源
        ssboData = null
        globalMaxData = null
        proximityData = null
        initialized = false
        fallbackMode = false
        
        LogManager.i(TAG, "GPU检测器资源已完全释放")
    }
    
    /**
     * 启用性能优化模式
     * @param enableOptimizations 是否启用性能优化
     * @param skipGLErrorChecks 是否跳过冗余的OpenGL错误检查
     * @param useMinimalSync 是否使用最小同步（可能影响稳定性）
     * @param enableFast 是否启用快速模式（跳过一些非关键步骤）
     */
    fun setPerformanceOptimizations(
        enableOptimizations: Boolean = true,
        skipGLErrorChecks: Boolean = true,
        useMinimalSync: Boolean = false,
        enableFast: Boolean = false
    ) {
        enablePerformanceOptimizations = enableOptimizations
        skipRedundantGLCalls = skipGLErrorChecks
        useMinimalSynchronization = useMinimalSync
        enableFastMode = enableFast
        
        LogManager.i(TAG, "性能优化设置: 启用=$enableOptimizations, 跳过GL检查=$skipGLErrorChecks, 最小同步=$useMinimalSync, 快速模式=$enableFast")
    }
    

    /**
     * 超快速SSBO读取模式 - 优化的映射方式，减少开销
     */
    private fun readLaserPointFromSSBOUltraFast(): LaserPointData? {
        val readStartTime = System.nanoTime()
        var stepStartTime = readStartTime
        
        try {
            LogManager.d(TAG, "【超快速模式】=== 开始SSBO读取 ===")
            
            // 步骤1：检查OpenGL状态
            stepStartTime = System.nanoTime()
            val glError1 = GLES31.glGetError()
            if (glError1 != GLES31.GL_NO_ERROR) {
                LogManager.w(TAG, "【超快速模式】初始OpenGL错误: 0x${glError1.toString(16)}")
            }
            val step1Time = (System.nanoTime() - stepStartTime) / 1_000_000f
            LogManager.d(TAG, "【超快速模式】步骤1 - OpenGL状态检查: ${String.format("%.3f", step1Time)}ms")
            
            // 步骤2：绑定SSBO
            stepStartTime = System.nanoTime()
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboBuffer)
            val glError2 = GLES31.glGetError()
            if (glError2 != GLES31.GL_NO_ERROR) {
                LogManager.e(TAG, "【超快速模式】绑定SSBO失败: 0x${glError2.toString(16)}")
                return null
            }
            val step2Time = (System.nanoTime() - stepStartTime) / 1_000_000f
            LogManager.d(TAG, "【超快速模式】步骤2 - 绑定SSBO: ${String.format("%.3f", step2Time)}ms")
            
            // 步骤3：检查缓冲区状态
            stepStartTime = System.nanoTime()
            val bufferSize = IntArray(1)
            GLES31.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER, GLES31.GL_BUFFER_SIZE, bufferSize, 0)
            val bufferMapped = IntArray(1)
            GLES31.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER, GLES31.GL_BUFFER_MAPPED, bufferMapped, 0)
            val step3Time = (System.nanoTime() - stepStartTime) / 1_000_000f
            LogManager.d(TAG, "【超快速模式】步骤3 - 缓冲区状态检查: ${String.format("%.3f", step3Time)}ms (大小:${bufferSize[0]}, 映射状态:${bufferMapped[0]})")
            
            // 步骤4：映射缓冲区
            stepStartTime = System.nanoTime()
            val mappedBuffer = GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                0,
                24,
                GLES31.GL_MAP_READ_BIT  // 只读取，减少开销
            )
            val step4Time = (System.nanoTime() - stepStartTime) / 1_000_000f
            LogManager.d(TAG, "【超快速模式】步骤4 - 映射缓冲区: ${String.format("%.3f", step4Time)}ms (结果: ${if (mappedBuffer != null) "成功" else "失败"})")
            
            if (mappedBuffer == null) {
                val glError3 = GLES31.glGetError()
                LogManager.w(TAG, "【超快速模式】映射失败，OpenGL错误: 0x${glError3.toString(16)}，降级到快速模式")
                return readLaserPointFromSSBOFast()
            }
            
            // 步骤5：转换ByteBuffer并创建FloatBuffer
            stepStartTime = System.nanoTime()
            val byteBuffer = mappedBuffer as ByteBuffer
            byteBuffer.order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()
            val step5Time = (System.nanoTime() - stepStartTime) / 1_000_000f
            LogManager.d(TAG, "【超快速模式】步骤5 - Buffer转换: ${String.format("%.3f", step5Time)}ms")
            
            // 步骤6：读取数据
            stepStartTime = System.nanoTime()
            val x = floatBuffer.get(0)
            val y = floatBuffer.get(1)
            val brightness = floatBuffer.get(2)
            val confidence = floatBuffer.get(3)
            val redValue = floatBuffer.get(4)
            val debugInfo = floatBuffer.get(5)
            val step6Time = (System.nanoTime() - stepStartTime) / 1_000_000f
            LogManager.d(TAG, "【超快速模式】步骤6 - 数据读取: ${String.format("%.3f", step6Time)}ms")
            
            // 步骤7：解除映射
            stepStartTime = System.nanoTime()
            val unmapResult = GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            val step7Time = (System.nanoTime() - stepStartTime) / 1_000_000f
            LogManager.d(TAG, "【超快速模式】步骤7 - 解除映射: ${String.format("%.3f", step7Time)}ms (结果: $unmapResult)")
            
            // 步骤8：数据验证
            stepStartTime = System.nanoTime()
            val isValidPoint = brightness > 0f && debugInfo > 0.5f
            val step8Time = (System.nanoTime() - stepStartTime) / 1_000_000f
            LogManager.d(TAG, "【超快速模式】步骤8 - 数据验证: ${String.format("%.3f", step8Time)}ms")
            
            val totalTime = (System.nanoTime() - readStartTime) / 1_000_000f
            
            // 详细性能总结
            LogManager.d(TAG, "【超快速模式性能总结】总耗时: ${String.format("%.3f", totalTime)}ms")
            LogManager.d(TAG, "  - OpenGL检查: ${String.format("%.3f", step1Time)}ms (${String.format("%.1f", step1Time/totalTime*100)}%)")
            LogManager.d(TAG, "  - SSBO绑定: ${String.format("%.3f", step2Time)}ms (${String.format("%.1f", step2Time/totalTime*100)}%)")
            LogManager.d(TAG, "  - 状态检查: ${String.format("%.3f", step3Time)}ms (${String.format("%.1f", step3Time/totalTime*100)}%)")
            LogManager.d(TAG, "  - 缓冲区映射: ${String.format("%.3f", step4Time)}ms (${String.format("%.1f", step4Time/totalTime*100)}%)")
            LogManager.d(TAG, "  - Buffer转换: ${String.format("%.3f", step5Time)}ms (${String.format("%.1f", step5Time/totalTime*100)}%)")
            LogManager.d(TAG, "  - 数据读取: ${String.format("%.3f", step6Time)}ms (${String.format("%.1f", step6Time/totalTime*100)}%)")
            LogManager.d(TAG, "  - 解除映射: ${String.format("%.3f", step7Time)}ms (${String.format("%.1f", step7Time/totalTime*100)}%)")
            LogManager.d(TAG, "  - 数据验证: ${String.format("%.3f", step8Time)}ms (${String.format("%.1f", step8Time/totalTime*100)}%)")
            
            // 检查是否检测到有效的激光点
            if (isValidPoint) {
                LogManager.d(TAG, "【超快速模式】GPU检测到激光点: ($x, $y) 亮度=$brightness 置信度=$confidence")
                LogManager.d(TAG, "【超快速模式】数据详情: redValue=$redValue, debugInfo=$debugInfo")
                return LaserPointData(x, y, brightness, confidence)
            } else {
                LogManager.d(TAG, "【超快速模式】未检测到有效激光点 (brightness=$brightness, debugInfo=$debugInfo)")
                return null
            }
        } catch (e: Exception) {
            val totalTime = (System.nanoTime() - readStartTime) / 1_000_000f
            LogManager.e(TAG, "【超快速模式】SSBO读取失败 (耗时: ${String.format("%.3f", totalTime)}ms): ${e.message}")
            e.printStackTrace()
            // 降级到映射方式
            return readLaserPointFromSSBOFast()
        }
    }

    /**
     * 快速SSBO读取模式 - 跳过大部分检查和日志
     */
    private fun readLaserPointFromSSBOFast(): LaserPointData? {
        try {
            // 直接绑定SSBO并尝试映射
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboBuffer)
            
            // 尝试最常用的映射标志
            val mappedBuffer = GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                0,
                24,
                GLES31.GL_MAP_READ_BIT or GLES31.GL_MAP_WRITE_BIT
            )
            
            if (mappedBuffer != null) {
                try {
                    val mappedFloatBuffer = (mappedBuffer as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    val x = mappedFloatBuffer.get(0)
                    val y = mappedFloatBuffer.get(1)
                    val brightness = mappedFloatBuffer.get(2)
                    val confidence = mappedFloatBuffer.get(3)
                    val redValue = mappedFloatBuffer.get(4)
                    val debugInfo = mappedFloatBuffer.get(5)
                    
                    GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                    
                    // 检查是否检测到有效的激光点
                    if (brightness > 0f && debugInfo > 0.5f) {
                        LogManager.d(TAG, "【快速模式】GPU检测到激光点: ($x, $y) 亮度=$brightness")
                        return LaserPointData(x, y, brightness, confidence)
                    } else {
                        LogManager.d(TAG, "【快速模式】未检测到有效激光点 (brightness=$brightness, debugInfo=$debugInfo)")
                        return null
                    }
                } catch (e: Exception) {
                    try {
                        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                    } catch (ignored: Exception) {}
                }
            }
            return null
        } catch (e: Exception) {
            LogManager.e(TAG, "【快速模式】SSBO读取失败: ${e.message}")
            return null
        }
    }



    /**
     * 极简GPU检测模式 - 移除所有不必要的步骤，专注性能
     * 重构为基于检测模式的统一调度方法
     */
    fun detectLaserPoint(externalTextureId: Int, threshold: Float, minBrightness: Float): LaserPointData? {
        if (!initialized) {
            LogManager.e(TAG, "GPU检测器未初始化")
            return null
        }

        // 检查是否在OpenGL线程中
        val currentThread = Thread.currentThread().name
//        LogManager.d(TAG, "【DEBUG】当前线程: $currentThread")

        // 检查OpenGL上下文
        val glVersion = GLES20.glGetString(GLES20.GL_VERSION)
        val glRenderer = GLES20.glGetString(GLES20.GL_RENDERER)
//        LogManager.d(TAG, "【DEBUG】OpenGL上下文检查 - 版本: $glVersion, 渲染器: $glRenderer")

        if (glVersion == null || glRenderer == null) {
            LogManager.e(TAG, "【DEBUG】没有有效的OpenGL上下文，无法执行GPU检测")
            return null
        }

        // 检查输入纹理ID是否有效
        if (externalTextureId <= 0 && !useTestMode) {
            LogManager.e(TAG, "【DEBUG】输入外部纹理ID无效: $externalTextureId")
            return null
        }

        return when (currentDetectionMode) {
            LaserDetectionMode.BASIC -> detectWithBasicMode(externalTextureId, threshold, minBrightness)
            LaserDetectionMode.OPTIMIZED -> detectWithOptimizedMode(externalTextureId, threshold, minBrightness)
            LaserDetectionMode.PROXIMITY -> detectWithProximityMode(externalTextureId, threshold, minBrightness)
        }
    }
    
    /**
     * 基础模式检测 - 使用 computeProgram
     */
    private fun detectWithBasicMode(externalTextureId: Int, threshold: Float, minBrightness: Float): LaserPointData? {
        val startTime = System.nanoTime()
        
        try {
            if (!enableFastMode) {
                LogManager.d(TAG, "【基础模式】开始检测，纹理ID: $externalTextureId")
            }

            // 直接清零SSBO（不保存状态）
            GLES31.glUseProgram(clearProgram)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboBuffer)
            GLES31.glDispatchCompute(1, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            // 使用基础检测程序
            GLES31.glUseProgram(computeProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboBuffer)
            
            // 确保Uniform位置已缓存，如果没有则获取
            if (!uniformLocationsCached || cachedTextureLocation == -1) {
                cachedTextureLocation = GLES31.glGetUniformLocation(computeProgram, "inputTexture")
                cachedThresholdLocation = GLES31.glGetUniformLocation(computeProgram, "uThreshold")
                cachedMinBrightnessLocation = GLES31.glGetUniformLocation(computeProgram, "uMinBrightness")
                cachedTextureSizeLocation = GLES31.glGetUniformLocation(computeProgram, "uTextureSize")
                uniformLocationsCached = true
            }

            if (!enableFastMode) {
                LogManager.d(TAG, "【基础模式】初始化Uniform位置 - texture: $cachedTextureLocation, threshold: $cachedThresholdLocation, minBrightness: $cachedMinBrightnessLocation, textureSize: $cachedTextureSizeLocation")
            }

            if (cachedTextureLocation == -1 || cachedThresholdLocation == -1 || cachedMinBrightnessLocation == -1 || cachedTextureSizeLocation == -1) {
                LogManager.e(TAG, "【基础模式】Uniform位置获取失败")
                return null
            }

            // 使用缓存的Uniform位置
            GLES31.glUniform1i(cachedTextureLocation, 0)
            GLES31.glUniform1f(cachedThresholdLocation, threshold)
            GLES31.glUniform1f(cachedMinBrightnessLocation, minBrightness)
            GLES31.glUniform2i(cachedTextureSizeLocation, width, height)
            
            if (!enableFastMode) {
                LogManager.d(TAG, "【基础模式】设置参数完成 - threshold: $threshold, minBrightness: $minBrightness, size: ${width}x${height}")
            }
            
            // 执行计算
            val groupsX = (width + 15) / 16
            val groupsY = (height + 15) / 16
            GLES31.glDispatchCompute(groupsX, groupsY, 1)

            if (!enableFastMode) {
                LogManager.d(TAG, "【基础模式】GPU计算调度完成，工作组: ${groupsX}x${groupsY}")
            }

            // 最小同步
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            GLES31.glFinish()

            checkGLError("执行基础检测 shader 失败")
            
            // 确保SSBO正确绑定后再读取
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboBuffer)
            
            // 使用最优化的读取方法
            if (!enableFastMode) {
                LogManager.d(TAG, "【基础模式】开始读取SSBO结果")
            }
            
            var result: LaserPointData? = null
            var method1Time = 0f

            // 方法1：超快速模式
            val method1Start = System.nanoTime()
            result = readLaserPointFromSSBOUltraFast()
            method1Time = (System.nanoTime() - method1Start) / 1_000_000f

            // 输出时间统计
            if (!enableFastMode) {
                LogManager.d(TAG, "【SSBO读取时间统计】")
                LogManager.d(TAG, "  - 超快速模式: ${String.format("%.3f", method1Time)}ms ${if (method1Time > 0 && result != null) "✅成功" else if (method1Time > 0) "❌失败" else "⏭️跳过"}")
                val totalReadTime = method1Time
                LogManager.d(TAG, "  - 总读取时间: ${String.format("%.3f", totalReadTime)}ms")
            }
            
            val totalTime = (System.nanoTime() - startTime) / 1_000_000f
            
            if (enableFastMode) {
                // 快速模式下只输出关键信息
                if (result != null) {
                    LogManager.d(TAG, "【基础模式】检测成功: (${String.format("%.3f", result.x)}, ${String.format("%.3f", result.y)}) ${String.format("%.2f", totalTime)}ms")
                }
            } else {
                if (result != null) {
                    LogManager.d(TAG, "【基础模式】检测成功，总耗时: ${String.format("%.3f", totalTime)}ms，激光点: (${result.x}, ${result.y})")
                } else {
                    LogManager.d(TAG, "【基础模式】未检测到激光点，总耗时: ${String.format("%.3f", totalTime)}ms")
                }
            }
            
            return result
            
        } catch (e: Exception) {
            val totalTime = (System.nanoTime() - startTime) / 1_000_000f
            LogManager.e(TAG, "【基础模式】检测失败，耗时: ${String.format("%.3f", totalTime)}ms，异常: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * 优化模式检测 - 使用 optimizedComputeProgram
     */
    private fun detectWithOptimizedMode(externalTextureId: Int, threshold: Float, minBrightness: Float): LaserPointData? {
        val startTime = System.nanoTime()
        
        try {
            if (!enableFastMode) {
                LogManager.d(TAG, "【优化模式】开始检测，纹理ID: $externalTextureId")
            }

            // 直接清零SSBO（不保存状态）
            GLES31.glUseProgram(clearProgram)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboBuffer)
            GLES31.glDispatchCompute(1, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            // 清零GlobalMaxBuffer（用于全局最亮点检测）
            if (globalMaxBuffer != 0) {
                if (!enableFastMode) {
                    LogManager.d(TAG, "【优化模式】清零GlobalMaxBuffer")
                }
                // 使用专用的清零shader
                GLES31.glUseProgram(clearGlobalProgram)
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, globalMaxBuffer)
                GLES31.glDispatchCompute(1, 1, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            }

            // 使用优化检测程序
            if (optimizedComputeProgram == 0) {
                LogManager.w(TAG, "【优化模式】优化程序未初始化，回退到基础模式")
                return detectWithBasicMode(externalTextureId, threshold, minBrightness)
            }

            GLES31.glUseProgram(optimizedComputeProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboBuffer)
            
            // 绑定GlobalMaxBuffer用于全局最亮点检测
            if (globalMaxBuffer != 0) {
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, globalMaxBuffer)
                if (!enableFastMode) {
                    LogManager.d(TAG, "【优化模式】绑定GlobalMaxBuffer用于全局最亮点检测")
                }
            }
            
            // 确保Uniform位置已缓存，如果没有则获取
            if (!uniformLocationsCached || cachedTextureLocation == -1) {
                cachedTextureLocation = GLES31.glGetUniformLocation(optimizedComputeProgram, "inputTexture")
                cachedThresholdLocation = GLES31.glGetUniformLocation(optimizedComputeProgram, "uThreshold")
                cachedMinBrightnessLocation = GLES31.glGetUniformLocation(optimizedComputeProgram, "uMinBrightness")
                cachedTextureSizeLocation = GLES31.glGetUniformLocation(optimizedComputeProgram, "uTextureSize")
                uniformLocationsCached = true
            }

            if (!enableFastMode) {
                LogManager.d(TAG, "【优化模式】初始化Uniform位置 - texture: $cachedTextureLocation, threshold: $cachedThresholdLocation, minBrightness: $cachedMinBrightnessLocation, textureSize: $cachedTextureSizeLocation")
            }

            if (cachedTextureLocation == -1 || cachedThresholdLocation == -1 || cachedMinBrightnessLocation == -1 || cachedTextureSizeLocation == -1) {
                LogManager.e(TAG, "【优化模式】Uniform位置获取失败")
                return null
            }

            // 使用缓存的Uniform位置
            GLES31.glUniform1i(cachedTextureLocation, 0)
            GLES31.glUniform1f(cachedThresholdLocation, threshold)
            GLES31.glUniform1f(cachedMinBrightnessLocation, minBrightness)
            GLES31.glUniform2i(cachedTextureSizeLocation, width, height)
            
            if (!enableFastMode) {
                LogManager.d(TAG, "【优化模式】设置参数完成 - threshold: $threshold, minBrightness: $minBrightness, size: ${width}x${height}")
            }
            
            // 执行计算
            val groupsX = (width + 15) / 16
            val groupsY = (height + 15) / 16
            GLES31.glDispatchCompute(groupsX, groupsY, 1)

            if (!enableFastMode) {
                LogManager.d(TAG, "【优化模式】GPU计算调度完成，工作组: ${groupsX}x${groupsY}")
            }

            // 最小同步
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            GLES31.glFinish()

            checkGLError("执行优化检测 shader 失败")
            
            // 确保SSBO正确绑定后再读取
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboBuffer)
            
            // 使用最优化的读取方法
            if (!enableFastMode) {
                LogManager.d(TAG, "【优化模式】开始读取SSBO结果")
            }
            
            var result: LaserPointData? = null
            var method1Time = 0f

            // 方法1：超快速模式
            val method1Start = System.nanoTime()
            result = readLaserPointFromSSBOUltraFast()
            method1Time = (System.nanoTime() - method1Start) / 1_000_000f

            // 输出时间统计
            if (!enableFastMode) {
                LogManager.d(TAG, "【SSBO读取时间统计】")
                LogManager.d(TAG, "  - 超快速模式: ${String.format("%.3f", method1Time)}ms ${if (method1Time > 0 && result != null) "✅成功" else if (method1Time > 0) "❌失败" else "⏭️跳过"}")
                val totalReadTime = method1Time
                LogManager.d(TAG, "  - 总读取时间: ${String.format("%.3f", totalReadTime)}ms")
            }
            
            val totalTime = (System.nanoTime() - startTime) / 1_000_000f
            
            if (enableFastMode) {
                // 快速模式下只输出关键信息
                if (result != null) {
                    LogManager.d(TAG, "【优化模式】检测成功: (${String.format("%.3f", result.x)}, ${String.format("%.3f", result.y)}) ${String.format("%.2f", totalTime)}ms")
                }
            } else {
                if (result != null) {
                    LogManager.d(TAG, "【优化模式】检测成功，总耗时: ${String.format("%.3f", totalTime)}ms，激光点: (${result.x}, ${result.y})")
                } else {
                    LogManager.d(TAG, "【优化模式】未检测到激光点，总耗时: ${String.format("%.3f", totalTime)}ms")
                }
            }
            
            return result
            
        } catch (e: Exception) {
            val totalTime = (System.nanoTime() - startTime) / 1_000_000f
            LogManager.e(TAG, "【优化模式】检测失败，耗时: ${String.format("%.3f", totalTime)}ms，异常: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * 邻近度模式检测 - 使用 proximityComputeProgram（包装现有的 detectLaserPointWithProximity 方法）
     */
    private fun detectWithProximityMode(externalTextureId: Int, threshold: Float, minBrightness: Float): LaserPointData? {
        return detectLaserPointWithProximity(externalTextureId, threshold, minBrightness)
    }


    /**
     * 基于邻近度的激光点检测 - 考虑上一次检测到的激光点位置，选择距离最近的激光点
     * @param externalTextureId 外部纹理ID（来自GLCameraSurfaceView的摄像头纹理）
     * @param threshold 亮度阈值 (0.0-1.0)
     * @param minBrightness 最小红色亮度 (0.0-1.0)
     * @param prevLaserX 上一次激光点X坐标 (归一化 0-1)，如果为null则使用内部存储的值
     * @param prevLaserY 上一次激光点Y坐标 (归一化 0-1)，如果为null则使用内部存储的值
     * @return 检测到的激光点，null表示未检测到
     */
    fun detectLaserPointWithProximity(
        externalTextureId: Int,
        threshold: Float = 0.8f,
        minBrightness: Float = 0.7f,
        prevLaserX: Float? = null,
        prevLaserY: Float? = null
    ): LaserPointData? {
        if (!initialized) {
            LogManager.e(TAG, "GPU检测器未初始化")
            return null
        }

        // 检查 proximityComputeProgram 是否有效
        if (proximityComputeProgram == 0) {
            LogManager.e(TAG, "proximityComputeProgram 未初始化 (ID = 0)")
            return null
        }

        // 更新上一次激光点位置
        if (prevLaserX != null) lastLaserX = prevLaserX
        if (prevLaserY != null) lastLaserY = prevLaserY

        if (!enableFastMode) {
            LogManager.d(
                TAG,
                "【邻近度检测】开始检测 - 纹理ID: $externalTextureId, 程序ID: $proximityComputeProgram"
            )
            LogManager.d(TAG, "【邻近度检测】参数 - threshold: $threshold, minBrightness: $minBrightness")
            LogManager.d(TAG, "【邻近度检测】上次位置 - (${lastLaserX}, ${lastLaserY})")
        }

        val startTime = System.nanoTime()

        // 保存当前OpenGL状态
        val savedProgram = IntArray(1)
        val savedBuffer = IntArray(1)

        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, savedProgram, 0)
        GLES31.glGetIntegerv(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING, savedBuffer, 0)

        try {
            // 检查缓冲区是否有效
            if (ssboBuffer == 0 || proximityBuffer == 0) {
                LogManager.e(TAG, "邻近度检测缓冲区ID无效 - ssboBuffer: $ssboBuffer, proximityBuffer: $proximityBuffer")
                return null
            }

            // 检查OpenGL错误
            fun checkGLError(operation: String) {
                val error = GLES20.glGetError()
                if (error != GLES20.GL_NO_ERROR) {
                    LogManager.e(TAG, "【OpenGL错误】$operation 失败: $error")
                    throw RuntimeException("OpenGL错误: $operation")
                }
            }

            // 步骤1：清零SSBO缓冲区
            if (!enableFastMode) { LogManager.d(TAG, "【邻近度检测】步骤1: 清零SSBO缓冲区")}
            GLES31.glUseProgram(clearProgram)
            checkGLError("使用清零程序")

            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboBuffer)
            checkGLError("绑定SSBO缓冲区")

            GLES31.glDispatchCompute(1, 1, 1)
            checkGLError("执行清零计算")

            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            checkGLError("内存屏障")

            // TODO: 现在使用proximityComputeProgram，需要清零proximityBuffer
            if (!enableFastMode) { LogManager.d(TAG, "【邻近度检测】步骤1.5: 清零邻近度缓冲区") }
            GLES31.glUseProgram(clearProximityProgram)
            checkGLError("使用清零邻近度缓冲区程序")

            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, proximityBuffer)
            checkGLError("绑定邻近度缓冲区")

            GLES31.glDispatchCompute(1, 1, 1)
            checkGLError("执行清零邻近度缓冲区计算")

            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            checkGLError("邻近度缓冲区内存屏障")

            // 步骤2：使用邻近度检测程序
            if (!enableFastMode) { LogManager.d(TAG, "【邻近度检测】步骤2: 切换到邻近度检测程序") }
            GLES31.glUseProgram(proximityComputeProgram)
            checkGLError("使用邻近度检测程序")

            // 绑定纹理
            if (!enableFastMode) { LogManager.d(TAG, "【邻近度检测】步骤3: 绑定纹理") }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
            checkGLError("绑定外部纹理")

            // 设置Uniform参数
            if (!enableFastMode) { LogManager.d(TAG, "【邻近度检测】步骤4: 设置Uniform参数") }
            val textureLocation = GLES31.glGetUniformLocation(proximityComputeProgram, "inputTexture")
            val thresholdLocation = GLES31.glGetUniformLocation(proximityComputeProgram, "uThreshold")
            val minBrightnessLocation = GLES31.glGetUniformLocation(proximityComputeProgram, "uMinBrightness")
            val textureSizeLocation = GLES31.glGetUniformLocation(proximityComputeProgram, "uTextureSize")
            val prevLaserXLocation = GLES31.glGetUniformLocation(proximityComputeProgram, "uPrevLaserX")
            val prevLaserYLocation = GLES31.glGetUniformLocation(proximityComputeProgram, "uPrevLaserY")

            if (!enableFastMode) {
                LogManager.d(
                    TAG,
                    "【邻近度检测】Uniform位置 - texture: $textureLocation, threshold: $thresholdLocation, minBrightness: $minBrightnessLocation"
                )
                LogManager.d(
                    TAG,
                    "【邻近度检测】Uniform位置 - textureSize: $textureSizeLocation, prevX: $prevLaserXLocation, prevY: $prevLaserYLocation"
                )
            }

            if (textureLocation == -1 || thresholdLocation == -1 || minBrightnessLocation == -1 ||
                textureSizeLocation == -1 || prevLaserXLocation == -1 || prevLaserYLocation == -1) {
                LogManager.e(TAG, "【邻近度检测】Uniform位置获取失败，着色器可能编译失败")
                return null
            }

            GLES31.glUniform1i(textureLocation, 0)
            GLES31.glUniform1f(thresholdLocation, threshold)
            GLES31.glUniform1f(minBrightnessLocation, minBrightness)
            GLES31.glUniform2i(textureSizeLocation, width, height)
            GLES31.glUniform1f(prevLaserXLocation, lastLaserX)
            GLES31.glUniform1f(prevLaserYLocation, lastLaserY)
            checkGLError("设置Uniform参数")

            // 绑定缓冲区
            if (!enableFastMode) { LogManager.d(TAG, "【邻近度检测】步骤5: 绑定SSBO缓冲区") }
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboBuffer)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, proximityBuffer)
            checkGLError("绑定SSBO缓冲区")

            // 执行计算
            val groupsX = (width + 15) / 16
            val groupsY = (height + 15) / 16
            if (!enableFastMode) { LogManager.d(TAG, "【邻近度检测】步骤6: 执行GPU计算 - 工作组: ${groupsX}x${groupsY}, 纹理尺寸: ${width}x${height}") }

            val computeStartTime = System.nanoTime()
            GLES31.glDispatchCompute(groupsX, groupsY, 1)
            checkGLError("调度计算")

            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            checkGLError("内存屏障")

            // 强制等待GPU完成
            GLES31.glFinish()
            val computeTime = (System.nanoTime() - computeStartTime) / 1_000_000f
            if (!enableFastMode) { LogManager.d(TAG, "【邻近度检测】GPU计算完成，耗时: ${String.format("%.2f", computeTime)}ms") }

            // 读取结果
            if (!enableFastMode) { LogManager.d(TAG, "【邻近度检测】步骤7: 读取SSBO结果") }
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboBuffer)
            checkGLError("绑定SSBO读取")

            val mappedBuffer = GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                0,
                24,
                GLES31.GL_MAP_READ_BIT
            )

            if (mappedBuffer != null) {
                val byteBuffer = mappedBuffer as ByteBuffer
                val resultBuffer = byteBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                val laserX = resultBuffer.get(0)
                val laserY = resultBuffer.get(1)
                val brightness = resultBuffer.get(2)
                val confidence = resultBuffer.get(3)
                val redValue = resultBuffer.get(4)
                val debugInfo = resultBuffer.get(5)

                GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)

                val processingTime = (System.nanoTime() - startTime) / 1_000_000f

                if (!enableFastMode) {
                    LogManager.d(
                        TAG,
                        "【邻近度检测】SSBO结果 - x: $laserX, y: $laserY, brightness: $brightness"
                    )
                    LogManager.d(
                        TAG,
                        "【邻近度检测】SSBO结果 - confidence: $confidence, redValue: $redValue, debugInfo: $debugInfo"
                    )
                }

                if (brightness > 0.0f) {
                    // 更新内部存储的上一次激光点位置
                    lastLaserX = laserX
                    lastLaserY = laserY

                    LogManager.d(TAG, "邻近度检测成功: (${String.format("%.3f", laserX)}, ${String.format("%.3f", laserY)}) " +
                            "亮度: ${String.format("%.3f", brightness)}, 置信度: ${String.format("%.3f", confidence)}, " +
                            "候选点数: ${debugInfo.toInt()}, 处理时间: ${String.format("%.2f", processingTime)}ms")

                    return LaserPointData(
                        x = laserX,
                        y = laserY,
                        brightness = brightness,
                        confidence = confidence
                    )
                } else {
                    LogManager.d(TAG, "邻近度检测未找到激光点，候选点数: ${debugInfo.toInt()}, 处理时间: ${String.format("%.2f", processingTime)}ms")
                    return null
                }
            } else {
                LogManager.e(TAG, "邻近度检测缓冲区映射失败")
                return null
            }

        } catch (e: Exception) {
            val processingTime = (System.nanoTime() - startTime) / 1_000_000f
            LogManager.e(TAG, "邻近度检测失败，耗时: ${String.format("%.2f", processingTime)}ms, 错误: ${e.message}")
            e.printStackTrace()
            return null
        } finally {
            // 恢复OpenGL状态
            GLES20.glUseProgram(savedProgram[0])
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, savedBuffer[0])
        }
    }

    /**
     * 查询GPU硬件能力
     */
    private fun queryGPUCapabilities() {
        try {
            LogManager.i(TAG, "=== GPU硬件能力查询开始 ===")
            
            // 查询基本GPU信息
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION)
            val shadingLanguageVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
            
            LogManager.i(TAG, "GPU厂商: $vendor")
            LogManager.i(TAG, "GPU型号: $renderer")
            LogManager.i(TAG, "OpenGL版本: $glVersion")
            LogManager.i(TAG, "着色器语言版本: $shadingLanguageVersion")
            
            // 查询工作组相关限制
            val maxWorkGroupSize = IntArray(3)
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0, maxWorkGroupSize, 0)
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 1, maxWorkGroupSize, 1)
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 2, maxWorkGroupSize, 2)
            
            val maxWorkGroupCount = IntArray(3)
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, maxWorkGroupCount, 0)
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1, maxWorkGroupCount, 1)
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 2, maxWorkGroupCount, 2)
            
            val maxWorkGroupInvocations = IntArray(1)
            GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, maxWorkGroupInvocations, 0)
            
            LogManager.i(TAG, "最大工作组大小: [${maxWorkGroupSize[0]}, ${maxWorkGroupSize[1]}, ${maxWorkGroupSize[2]}]")
            LogManager.i(TAG, "最大工作组数量: [${maxWorkGroupCount[0]}, ${maxWorkGroupCount[1]}, ${maxWorkGroupCount[2]}]")
            LogManager.i(TAG, "最大工作组线程数: ${maxWorkGroupInvocations[0]}")
            
            // 计算当前使用的工作组大小和理论最大值
            val currentWorkGroupSize = 16 * 16 // 当前使用的16x16
            val theoreticalMaxWorkGroupSize = maxWorkGroupSize[0] * maxWorkGroupSize[1]
            LogManager.i(TAG, "当前工作组大小: $currentWorkGroupSize")
            LogManager.i(TAG, "理论最大工作组大小: $theoreticalMaxWorkGroupSize")
            LogManager.i(TAG, "工作组大小利用率: ${String.format("%.1f", (currentWorkGroupSize.toFloat() / maxWorkGroupInvocations[0]) * 100)}%")
            
            // 查询内存相关限制
            val maxSharedMemorySize = IntArray(1)
            GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, maxSharedMemorySize, 0)
            
            val maxSSBOBindings = IntArray(1)
            GLES31.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS, maxSSBOBindings, 0)
            
            val maxSSBOSize = IntArray(1)
            GLES31.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, maxSSBOSize, 0)
            
            LogManager.i(TAG, "最大共享内存大小: ${maxSharedMemorySize[0]} bytes (${maxSharedMemorySize[0] / 1024} KB)")
            LogManager.i(TAG, "最大SSBO绑定点数: ${maxSSBOBindings[0]}")
            LogManager.i(TAG, "最大SSBO块大小: ${maxSSBOSize[0]} bytes (${maxSSBOSize[0] / (1024 * 1024)} MB)")
            
            // 计算当前共享内存使用量
            val currentSharedMemoryUsage = calculateCurrentSharedMemoryUsage()
            LogManager.i(TAG, "当前共享内存使用: $currentSharedMemoryUsage bytes (${currentSharedMemoryUsage / 1024} KB)")
            LogManager.i(TAG, "共享内存利用率: ${String.format("%.1f", (currentSharedMemoryUsage.toFloat() / maxSharedMemorySize[0]) * 100)}%")
            
            // 查询纹理相关限制
            val maxTextureSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            
            val maxTextureImageUnits = IntArray(1)
            GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS, maxTextureImageUnits, 0)
            
            LogManager.i(TAG, "最大纹理尺寸: ${maxTextureSize[0]}x${maxTextureSize[0]}")
            LogManager.i(TAG, "最大纹理图像单元数: ${maxTextureImageUnits[0]}")
            
            // 查询Uniform相关限制
            val maxUniformBufferBindings = IntArray(1)
            GLES31.glGetIntegerv(GLES31.GL_MAX_UNIFORM_BUFFER_BINDINGS, maxUniformBufferBindings, 0)
            
            val maxComputeUniformComponents = IntArray(1)
            GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_UNIFORM_COMPONENTS, maxComputeUniformComponents, 0)
            
            LogManager.i(TAG, "最大Uniform缓冲区绑定点数: ${maxUniformBufferBindings[0]}")
            LogManager.i(TAG, "最大Compute Shader Uniform组件数: ${maxComputeUniformComponents[0]}")
            
            // 查询原子操作相关限制
            val maxAtomicCounterBufferBindings = IntArray(1)
            GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_ATOMIC_COUNTER_BUFFERS, maxAtomicCounterBufferBindings, 0)
            
            val maxAtomicCounters = IntArray(1)
            GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_ATOMIC_COUNTERS, maxAtomicCounters, 0)
            
            LogManager.i(TAG, "最大原子计数器缓冲区数: ${maxAtomicCounterBufferBindings[0]}")
            LogManager.i(TAG, "最大原子计数器数: ${maxAtomicCounters[0]}")
            
            // 性能建议
            generatePerformanceRecommendations(
                maxWorkGroupInvocations[0],
                maxSharedMemorySize[0],
                currentWorkGroupSize,
                currentSharedMemoryUsage
            )
            
            LogManager.i(TAG, "=== GPU硬件能力查询完成 ===")
            
        } catch (e: Exception) {
            LogManager.w(TAG, "GPU硬件能力查询失败: ${e.message}")
        }
    }
    
    /**
     * 计算当前共享内存使用量
     */
    private fun calculateCurrentSharedMemoryUsage(): Int {
        // 当前工作组大小为16x16 = 256个线程
        val workGroupSize = 256
        
        // PROXIMITY_BASED_LASER_DETECT_SHADER中的共享内存使用:
        // shared float sharedMaxScore[256];     // 256 * 4 = 1024 bytes
        // shared vec2 sharedMaxCoords[256];     // 256 * 8 = 2048 bytes  
        // shared float sharedRedValues[256];    // 256 * 4 = 1024 bytes
        // shared float sharedDistances[256];    // 256 * 4 = 1024 bytes
        
        val proximityShaderSharedMemory = workGroupSize * 4 + // sharedMaxScore
                                         workGroupSize * 8 + // sharedMaxCoords (vec2)
                                         workGroupSize * 4 + // sharedRedValues  
                                         workGroupSize * 4   // sharedDistances
        
        return proximityShaderSharedMemory
    }
    
    /**
     * 生成性能优化建议
     */
    private fun generatePerformanceRecommendations(
        maxWorkGroupInvocations: Int,
        maxSharedMemorySize: Int,
        currentWorkGroupSize: Int,
        currentSharedMemoryUsage: Int
    ) {
        LogManager.i(TAG, "=== 性能优化建议 ===")
        
        // 工作组大小建议
        if (currentWorkGroupSize < maxWorkGroupInvocations / 2) {
            val suggestedSize = minOf(maxWorkGroupInvocations, 512)
            LogManager.i(TAG, "建议: 可以尝试增大工作组大小到 $suggestedSize 以提高并行度")
            
            // 计算推荐的工作组配置
            val sqrtSize = kotlin.math.sqrt(suggestedSize.toDouble()).toInt()
            if (sqrtSize * sqrtSize == suggestedSize) {
                LogManager.i(TAG, "推荐工作组配置: ${sqrtSize}x${sqrtSize}")
            } else {
                val factors = findWorkGroupFactors(suggestedSize)
                LogManager.i(TAG, "推荐工作组配置: ${factors.first}x${factors.second}")
            }
        }
        
        // 共享内存使用建议
        val sharedMemoryUtilization = (currentSharedMemoryUsage.toFloat() / maxSharedMemorySize) * 100
        if (sharedMemoryUtilization > 80) {
            LogManager.w(TAG, "警告: 共享内存使用率过高 (${String.format("%.1f", sharedMemoryUtilization)}%)，可能影响性能")
            LogManager.i(TAG, "建议: 考虑减少共享内存数组大小或优化数据结构")
        } else if (sharedMemoryUtilization < 20) {
            LogManager.i(TAG, "建议: 共享内存利用率较低，可以考虑增加缓存更多数据以提高性能")
        }
        
        // 工作组配置建议
        LogManager.i(TAG, "当前配置 16x16 适合大多数GPU，是一个平衡的选择")
        LogManager.i(TAG, "如需优化，建议测试以下配置: 32x8, 32x16, 64x4")
    }
    
    /**
     * 找到工作组大小的合适因数分解
     */
    private fun findWorkGroupFactors(size: Int): Pair<Int, Int> {
        val sqrt = kotlin.math.sqrt(size.toDouble()).toInt()
        
        for (i in sqrt downTo 1) {
            if (size % i == 0) {
                val j = size / i
                // 优先选择比较平衡的配置
                if (kotlin.math.abs(i - j) <= sqrt / 2) {
                    return Pair(i, j)
                }
            }
        }
        
        return Pair(1, size)
    }
    
    /**
     * 激光检测模式枚举
     */
    enum class LaserDetectionMode {
        BASIC,          // 基础模式 - 使用 computeProgram
        OPTIMIZED,      // 优化模式 - 使用 optimizedComputeProgram  
        PROXIMITY       // 邻近度模式 - 使用 proximityComputeProgram
    }
    
    // 当前激光检测模式
    private var currentDetectionMode = LaserDetectionMode.PROXIMITY
}