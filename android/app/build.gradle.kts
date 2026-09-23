import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
}

// Gradle 只自动加载 gradle.properties；local.properties 需手动读，
// 否则凭据静默为空、远程 AI 永远走本地兜底
val touchSceneLocalProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) f.inputStream().use(::load)
    }

fun touchSceneProperty(name: String): String? =
    (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: touchSceneLocalProps.getProperty(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.insta360.kmpsdk.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.insta360.kmpsdk.demo"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = libs.versions.inskmpVersion.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 远程 AI 接口与 key：在 local.properties 或 gradle 命令行配置
        // touchscene.ai.endpoint / touchscene.ai.key，凭据不进版本库
        buildConfigField(
            "String",
            "TOUCHSCENE_AI_ENDPOINT",
            "\"${touchSceneProperty("touchscene.ai.endpoint") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "TOUCHSCENE_AI_KEY",
            "\"${touchSceneProperty("touchscene.ai.key") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "TOUCHSCENE_AI_MODEL",
            "\"${touchSceneProperty("touchscene.ai.model") ?: "qwen3-vl-flash"}\"",
        )
        buildConfigField(
            "String",
            "TOUCHSCENE_AI_ASR_MODEL",
            "\"${touchSceneProperty("touchscene.ai.asrModel") ?: "qwen3-asr-flash"}\"",
        )

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    // lint 与当前 Kotlin UAST 工具链偶发不兼容导致 NonNullableMutableLiveDataDetector 崩溃，禁用该检测器以恢复 lintDebug
    lint {
        disable += "NullSafeMutableLiveData"
        // 仓库内尚有大量历史 lint error（如缺失翻译），本次功能不改变文案覆盖面；不因 lint error 中止以便产出报告
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.mlkit.image.labeling)
    testImplementation(libs.junit)
    // 让 JVM 单元测试能运行 android 同款 org.json 解析逻辑
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.immersionbar)
    implementation(libs.xx.permission)
    implementation(libs.timber)
    implementation(libs.glide)
    kapt(libs.glide.compiler)

    implementation(libs.inskmp.camera)
    implementation(libs.inskmp.media)

    // OpenCV：边缘(自适应阈值+轮廓)与轮廓(GrabCut)算法所需
    implementation(project(":opencv-sdk"))
}
