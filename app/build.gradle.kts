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

// 每次 assembleDebug 成功后，把 APK 复制到 D:\xiangqi-apk。
// 工程在 C 盘很深的目录里，手动翻 build/outputs/apk/debug 太费事。
val apkDropDir = File("D:/xiangqi-apk")

tasks.register("syncApkToD") {
    group = "build"
    description = "把本次构建的 debug APK 同步到 D:\\xiangqi-apk"

    doLast {
        val apkDir = File(projectDir, "build/outputs/apk/debug")
        val apks = apkDir.listFiles { f: File -> f.isFile && f.extension == "apk" }
        if (apks.isNullOrEmpty()) {
            logger.lifecycle("没有找到 APK，跳过同步")
            return@doLast
        }
        apkDropDir.mkdirs()
        apks.forEach { src ->
            val dst = File(apkDropDir, src.name)
            src.copyTo(dst, overwrite = true)
            logger.lifecycle("APK 已同步 -> ${dst.absolutePath}")
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("syncApkToD")
}
