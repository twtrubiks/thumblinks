import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.twtrubiks.thumblinks"
    // GeckoView 159 要求 37.2（Android 17 的 minor SDK）以上
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.twtrubiks.thumblinks"
        // Android 9；java.time 從 API 26 就內建，不需要 core library desugaring
        minSdk = 28
        targetSdk = 36
        // 發版時由 release.yml 帶進來：versionCode 是 2026 年起算的分鐘數，只會越來越大，新版才裝得上去
        versionCode = providers.environmentVariable("THUMBLINKS_VERSION_CODE").map(String::toInt).getOrElse(1)
        versionName = providers.environmentVariable("THUMBLINKS_VERSION_NAME").getOrElse("0.1")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 給別人下載的 APK（GitHub Release）一律用同一把金鑰簽：換了金鑰，手機上的舊版就不能直接更新，
    // 只能移除重裝，清單會一起刪掉。金鑰放在 GitHub Secrets，由 release.yml 解開後用環境變數帶進來
    val releaseKeystore = providers.environmentVariable("THUMBLINKS_KEYSTORE")
    if (releaseKeystore.isPresent) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore.get())
            storeType = "pkcs12"
            storePassword = providers.environmentVariable("THUMBLINKS_KEYSTORE_PASSWORD").get()
            keyAlias = "thumblinks"
            keyPassword = providers.environmentVariable("THUMBLINKS_KEYSTORE_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            // R8 刪掉沒用到的程式碼並最佳化，Compose 在 release 版會比 debug 版順很多
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 沒有發版金鑰（自己 build、錄影的 CI）就用這台機器的 debug 金鑰簽，能直接安裝，也能覆蓋同一台機器打包的 debug 版
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // GeckoView 帶了四種 CPU 的引擎，整包 500 MB 以上。只出兩份：
    // arm64-v8a 給手機（release.yml 發的就是這份），x86_64 給 CI 的模擬器（record.sh 裝的）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // native library 預設不壓縮（裝好之後直接從 APK 讀），GeckoView 的 libxul.so 光一個就 150 MB。
    // 給手機的 APK 是從 GitHub Release 下載的，壓縮後 APK 小一半以上；代價是安裝時要解開到手機裡
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<Test>().configureEach {
    // CI 只留得下 gradle-build.txt，測試失敗要直接印出期望值與實際值
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.exifinterface)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.geckoview)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.sqlite.jdbc)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // 錄影時在模擬器上開一個假網站（有 og:image 的網頁、沒有縮圖的網頁、圖片本身），CI 不必連外也能走完加入連結
    androidTestImplementation(libs.mockwebserver3)
}
