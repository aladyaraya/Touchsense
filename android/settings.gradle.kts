import java.util.Properties

val touchSceneLocalProperties =
    Properties().apply {
        val localFile = file("local.properties")
        if (localFile.isFile) localFile.inputStream().use(::load)
    }

fun ProviderFactory.instaCredential(propertyName: String, environmentName: String): String? =
    gradleProperty(propertyName).orNull
        ?: environmentVariable(environmentName).orNull
        ?: touchSceneLocalProperties.getProperty(propertyName)

val instaMavenUsername = providers.instaCredential("insta360.maven.username", "INSTA360_MAVEN_USERNAME")
val instaMavenPassword = providers.instaCredential("insta360.maven.password", "INSTA360_MAVEN_PASSWORD")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("https://androidsdk.insta360.com/repository/maven-public/")
            if (!instaMavenUsername.isNullOrBlank() && !instaMavenPassword.isNullOrBlank()) {
                credentials {
                    username = instaMavenUsername
                    password = instaMavenPassword
                }
            }
        }
    }
}

rootProject.name = "AndroidSDKDemo"
include(":app")
include(":opencv-sdk")
