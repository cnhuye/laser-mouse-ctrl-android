plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scieford.laserctrlmouse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scieford.laserctrlmouse"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 添加 OpenGL ES 要求
        manifestPlaceholders["glEsVersion"] = "0x00030000" // OpenGL ES 3.0
        
        // 设置应用名称和描述 (用于Google Play)
        setProperty("archivesBaseName", "LaserCtrlMouse-v$versionName")
        
        // 支持的ABI架构
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        
        // 支持 16KB 页面大小 (Android 15+ 要求)
        externalNativeBuild {
            ndkBuild {
                arguments += listOf("-j4")
                cppFlags += listOf("-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__")
            }
        }
    }

    signingConfigs {
        create("release") {
            // Read from environment variables or gradle properties
            storeFile = file(System.getenv("RELEASE_STORE_FILE") ?: project.findProperty("RELEASE_STORE_FILE") ?: "keystore/release.jks")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: (project.findProperty("RELEASE_STORE_PASSWORD") as String? ?: "")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: (project.findProperty("RELEASE_KEY_ALIAS") as String? ?: "")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: (project.findProperty("RELEASE_KEY_PASSWORD") as String? ?: "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            
            // 优化设置
            isDebuggable = false
            isJniDebuggable = false
            isRenderscriptDebuggable = false
            isPseudoLocalesEnabled = false
            
            // 启用 native debug symbols 生成
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 确保本地库支持 16KB 页面大小
        jniLibs {
            useLegacyPackaging = false
        }
    }
    
    bundle {
        language {
            // 启用语言资源分割以减小包大小
            enableSplit = true
        }
        density {
            // 启用密度资源分割
            enableSplit = true
        }
        abi {
            // 启用ABI分割
            enableSplit = true
        }
    }
}

dependencies {

    implementation("org.opencv:opencv:4.11.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")

    // 网络库 - OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // 协程支持
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // JSON处理（如果需要）
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    
    // Iconify - Lucide Icons for Compose
    implementation("com.composables:icons-lucide:1.0.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}