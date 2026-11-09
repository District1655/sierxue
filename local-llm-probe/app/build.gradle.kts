plugins { id("com.android.application") }

android {
    namespace = "com.sierxue.localprobe"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sierxue.localprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
