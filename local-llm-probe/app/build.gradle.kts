import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins { id("com.android.application") }

val buildTime = OffsetDateTime.now(ZoneOffset.ofHours(8))
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss '+08:00'"))

android {
    namespace = "com.sierxue.localprobe"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sierxue.localprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.0"
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "backend"
    productFlavors {
        create("cpu") {
            buildConfigField("String", "NATIVE_LIBRARY_NAME", "\"llama_probe_cpu\"")
        }
        create("vulkan") {
            minSdk = 28
            buildConfigField("String", "NATIVE_LIBRARY_NAME", "\"llama_probe_vulkan\"")
        }
    }
    sourceSets.getByName("main") {
        jniLibs.srcDirs("native-libs")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {}
