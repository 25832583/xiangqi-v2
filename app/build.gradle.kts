plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 是否把 NNUE 权重（`pikafish.nnue`，50.7MB）打进 APK。
 *
 * 默认打进去 —— 本机开发不想每次都手动导入一遍。
 * 对外分发时关掉：`./gradlew clean assembleDebug -PbundleNnue=false`。
 * **`clean` 不能省**：这个开关改的是资源过滤规则，AGP 没把它算进增量输入，
 * 在已有完整包产物的工程上直接重建，拿到的还是一个 93MB、权重照旧在内的包。
 *
 * 为什么：`pikafish.nnue` 的条款是「仅限个人非商业使用」，与引擎代码的 GPL 是两回事，
 * 把它塞进公开分发的 APK 就是在再分发它。引擎二进制（GPL）可以随包给，权重不行。
 * 关掉之后 App 仍能运行，只是引擎以无权重模式起步，用户需要自己从皮卡鱼官方取一份导入。
 * 注意那份权重的版本必须与 [com.xiangqi.v2.config.EngineConfig.VERSION] 一致，否则引擎拒收。
 *
 * 顺带把 `board_locator_v1.onnx` 也排除：上游仓库没有许可文件（保留全部权利），
 * 同样不能随发行包分发。缺了它只是少一层兜底（退回棋子包络反推），不影响可用性。
 */
val bundleNnue: Boolean = providers.gradleProperty("bundleNnue")
    .map { it.toBoolean() }
    .getOrElse(true)

android {
    namespace = "com.xiangqi.v2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiangqi.v2"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

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

    /**
     * 分发包的资产裁剪。
     *
     * `ignoreAssetsPattern` 是 aapt 的既有机制：`:` 分隔、命中即忽略、`!` 前缀表示豁免。
     * 基础串是 AGP 的默认值，**必须原样带上** —— 只写新加的两项会让 `.git`、
     * 各种缩略图缓存全被当成 asset 打进包。
     */
    androidResources {
        if (!bundleNnue) {
            ignoreAssetsPattern =
                "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~" +
                    ":pikafish.nnue:board_locator_v1.onnx"
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
