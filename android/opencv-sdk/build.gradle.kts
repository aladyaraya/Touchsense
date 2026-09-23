plugins { id("com.android.library") }

android {
    namespace = "org.opencv"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        targetSdk = 35
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].apply {
        java.setSrcDirs(listOf("src"))
        res.setSrcDirs(listOf("res"))
        manifest.srcFile("AndroidManifest.xml")
        jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
