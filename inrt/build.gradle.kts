
plugins {
    id("com.android.application")
    id("kotlin-android")
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(versions.javaVersionInt))
    }
}

android {
    compileSdk = versions.compile
    defaultConfig {
        applicationId = "org.autojs.autoxjs.inrt"
        minSdk = versions.mini
        targetSdk = versions.target
        versionCode = versions.appVersionCode
        versionName = versions.appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
//        multiDexEnabled = true
//        buildConfigField("boolean","isMarket","true") // 这是有注册码的版本
        buildConfigField("boolean", "isMarket", "false")
        // 默认脚本目录：开源 Autox.js 用 /sdcard/脚本/，目录名本身是外部特征，suncV7 风味覆盖成 /vseven/
        buildConfigField("String", "SCRIPT_DIR", "\"/脚本/\"")
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["resourcePackageName"] = applicationId.toString()
            }
        }
    }
    lint.abortOnError = false
    lint.disable.apply {
        add("MissingTranslation")
        add("ExtraTranslation")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions{
        kotlinCompilerExtensionVersion = compose_version
    }
    buildTypes {
        named("debug") {
            isMinifyEnabled = false
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android.txt"),
                    "proguard-rules.pro"
                )
            )
        }
        named("release") {
            isMinifyEnabled = false
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android.txt"),
                    "proguard-rules.pro"
                )
            )
        }
    }
    flavorDimensions.apply {
        add("channel")
    }

    productFlavors {
        create("common") {
            buildConfigField("boolean", "isMarket", "false")
            manifestPlaceholders.putAll(mapOf("appName" to "inrt"))
            ndk.abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
        create("template") {
            manifestPlaceholders.putAll(mapOf("appName" to "template"))
            packagingOptions.apply {
                jniLibs.excludes.add("*")
            }
            ndk.abiFilters.add("")
        }
        // 打包成子 APK 用的 vseven 模板：与 template 同样瘦身，但换掉一切可被内容扫描读走的家族特征
        create("suncV7") {
            applicationId = "com.sunc.vseven.inrt"
            manifestPlaceholders.putAll(mapOf("appName" to "vseven"))
            buildConfigField("String", "SCRIPT_DIR", "\"/vseven/\"")
            packagingOptions.apply {
                jniLibs.excludes.add("*")
            }
            ndk.abiFilters.add("")
        }
    }
    sourceSets {
        named("main") {
            jniLibs.srcDir("/libs")
            res.srcDirs("src/main/res", "src/main/res-i18n")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        jniLibs.pickFirsts.addAll(
            listOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/arm64-v8a/libhiai.so",
                "lib/arm64-v8a/libhiai_ir.so",
                "lib/arm64-v8a/libhiai_ir_build.so",
                "lib/arm64-v8a/libNative.so",
                "lib/arm64-v8a/libpaddle_light_api(_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/armeabi-v7a/libhiai.so",
                "lib/armeabi-v7a/libhiai_ir.so",
                "lib/armeabi-v7a/libhiai_ir_build.so",
                "lib/armeabi-v7a/libNative.so",
                "lib/armeabi-v7a/libpaddle_light_api(_shared.so"
            )
        )
    }
    namespace = "org.autojs.autoxjs.inrt"


}

android.applicationVariants.all {
    val variant = this
    if (variant.flavorName == "template" || variant.flavorName == "suncV7") {
        val isVseven = variant.flavorName == "suncV7"
        mergeAssetsProvider.configure {
            doLast {
                delete(
                    fileTree(outputDir) {
                        include(
                            "models/**/*",
                            "mlkit-google-ocr-models/**/*",
                            "project/**/*"
                        )
                        // 提权 ELF 明摆在 assets 里，是分发侧扫描的命中点；vseven 模板不带
                        if (isVseven) include("binary/root_automator")
                    }
                )
            }
        }
    }

}


dependencies {
    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.androidx.annotation)
    implementation(libs.preference.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    androidTestImplementation(libs.espresso.core)
    implementation("com.dhh:websocket2:2.1.4")
    implementation("com.github.SenhLinsh:Utils-Everywhere:3.0.0")
    testImplementation(libs.junit)
    implementation(project(":automator"))
    implementation(project(":common"))
    implementation(project(":autojs"))
}