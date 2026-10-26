plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiangqi.v2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiangqi.v2"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 抬头那行版本号直接读 BuildConfig.VERSION_NAME，避免"设置里 1.0、界面上 V2"
    // 这种两处各自写死各自漂移的局面
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    testImplementation("junit:junit:4.13.2")
}

// 可选：构建完把 debug APK 复制到指定目录，方便直接用数据线取包。
// 默认**不启用** —— 需要的话在用户级配置里设一次即可（不会进仓库）：
//
//   ~/.gradle/gradle.properties
//   apkDropDir=D:/xiangqi-apk
//
// 也可以用 -PapkDropDir=... 单次指定。
val apkDropDir: String? = providers.gradleProperty("apkDropDir").orNull

if (!apkDropDir.isNullOrBlank()) {
    tasks.register("syncApk") {
        group = "build"
        description = "把本次构建的 debug APK 同步到 apkDropDir（$apkDropDir）"

        doLast {
            val apkDir = File(projectDir, "build/outputs/apk/debug")
            val apks = apkDir.listFiles { f: File -> f.isFile && f.extension == "apk" }
            if (apks.isNullOrEmpty()) {
                logger.lifecycle("没有找到 APK，跳过同步")
                return@doLast
            }
            val target = File(apkDropDir)
            target.mkdirs()
            apks.forEach { src ->
                val dst = File(target, src.name)
                src.copyTo(dst, overwrite = true)
                logger.lifecycle("APK 已同步 -> ${dst.absolutePath}")
            }
        }
    }

    tasks.matching { it.name == "assembleDebug" }.configureEach {
        finalizedBy("syncApk")
    }
}
