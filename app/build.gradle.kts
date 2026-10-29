import com.android.build.gradle.internal.tasks.factory.dependsOn
import java.io.FileNotFoundException
import java.util.Base64

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.jakewharton.butterknife")
    id("kotlin-kapt")//Deprecated!!
    id("com.google.devtools.ksp")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(versions.javaVersionInt))
    }
}

// 品牌变体（vseven/sseven/goqi/asrseven）共用的加固模板：由 :inrt 的 suncV7 风味现造，产物落在构建目录、不入库
val brandFlavors = setOf("suncV7", "sseven", "goqi", "asrseven", "zlwseven", "rbtseven")
val brandTemplateAssets = layout.buildDirectory.dir("brandTemplateAssets")
val syncBrandTemplateApk = tasks.register("syncBrandTemplateApk") {
    group = "build"
    description = "构建 :inrt:assembleSuncV7Release 并把它作为品牌变体的 assets/template.apk"
    dependsOn(":inrt:assembleSuncV7Release")
    val outDir = File(rootDir, "inrt/build/outputs/apk/suncV7/release")
    doLast {
        val apk = outDir.listFiles { f: File -> f.isFile && f.name.endsWith(".apk") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("未找到 vseven 模板产物，检查 $outDir（:inrt 的 suncV7 风味是否构建成功）")
        copy {
            from(apk)
            into(brandTemplateAssets.get().asFile)
            rename(apk.name, "template.apk")
        }
        logger.info("vseven 模板: ${apk.name} -> assets/template.apk")
    }
}

android {
    namespace = "org.autojs.autoxjs"
    compileSdk = versions.compile
    defaultConfig {
        applicationId = "org.autojs.autoxjs"
        minSdk = versions.mini
        targetSdk = versions.target
        versionCode = versions.appVersionCode
        versionName = versions.appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
//        multiDexEnabled = true
        buildConfigField("boolean", "isMarket", "false")

        resourceConfigurations.addAll(
            listOf("zh-rCN", "en", "es", "ar", "ja", "zh_TW", "fr", "de", "it", "ko", "ru", "tr", "lt")
        )
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = compose_version
    }
    lint {
        abortOnError = false
        disable.addAll(listOf("MissingTranslation", "ExtraTranslation"))
    }

    splits {
        // Configures multiple APKs based on ABI.
        abi {
            // Enables building multiple APKs per ABI.
            isEnable = true
            // By default all ABIs are included, so use reset() and include to specify that we only
            // want APKs for x86 and x86_64.
            // Resets the list of ABIs that Gradle should create APKs for to none.
            reset()
            // Specifies a list of ABIs that Gradle should create APKs for.
            // 单 ABI 构建：-Pabis=arm64-v8a（只产出 arm64，省掉 x86_64 的打包时间）
            include(
                *((project.findProperty("abis") as String?)?.split(",")?.filter { it.isNotBlank() }
                    ?.toTypedArray() ?: arrayOf("arm64-v8a", "x86_64"))
            )
            // Specifies that we do not want to also generate a universal APK that includes all ABIs.
            isUniversalApk = false
        }
    }
    val localSigning =
        if (project.hasProperty("SIGNING_STORE_FILE") && project.hasProperty("SIGNING_STORE_PASSWORD")) {
            signingConfigs.create("localRelease") {
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                storeFile = file(project.property("SIGNING_STORE_FILE") as String)
                storePassword = project.property("SIGNING_STORE_PASSWORD") as String
                keyAlias = project.property("SIGNING_KEY_ALIAS") as String
                keyPassword = project.property("SIGNING_KEY_PASSWORD") as String
            }
        } else null
    val signing =
        if (localSigning != null) {
            localSigning
        } else if (System.getenv("CI") == "true" && !System.getenv("KEYSTORE_BASE64").isNullOrEmpty()) {
            val file = File.createTempFile("key", "jks")
            val bytes = Base64.getDecoder().decode(System.getenv("KEYSTORE_BASE64"))
            file.writeBytes(bytes)
            signingConfigs.create("release") {
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                storeFile = file
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        } else null

    buildTypes {
        named("debug") {
            isShrinkResources = false
            isMinifyEnabled = false
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"
                )
            )
        }
        named("release") {
            if (signing != null) {
                signingConfig = signing
            }
            isShrinkResources = false
            isMinifyEnabled = false
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"
                )
            )
        }
    }

    flavorDimensions.add("channel")
    productFlavors {
        create("common") {
            applicationIdSuffix = ".common"
            versionCode = versions.appVersionCode
            versionName = versions.appVersionName
            buildConfigField("String", "CHANNEL", "\"common\"")
            buildConfigField("int", "DEFAULT_SERVER_PORT", "29317")
            manifestPlaceholders.putAll(mapOf("appName" to "@string/app_name"))
        }
        create("v7") {
            applicationIdSuffix = ".v7"
            versionCode = versions.devVersionCode
            versionName = versions.devVersionName
            buildConfigField("String", "CHANNEL", "\"v7\"")
            buildConfigField("int", "DEFAULT_SERVER_PORT", "29317")
            manifestPlaceholders.putAll(mapOf("appName" to "Autox.js v7"))
        }
        create("v7_mini") {
            applicationIdSuffix = ".v7"
            buildConfigField("String", "CHANNEL", "\"v7\"")
            buildConfigField("int", "DEFAULT_SERVER_PORT", "29317")
            manifestPlaceholders.putAll(mapOf("appName" to "Autox.js v7"))
        }
        create("suncV7") {
            applicationId = "com.sunc.vseven"
            versionCode = 1000
            versionName = "1.0.0"
            buildConfigField("String", "CHANNEL", "\"suncV7\"")
            buildConfigField("int", "DEFAULT_SERVER_PORT", "29317")
            manifestPlaceholders.putAll(mapOf("appName" to "vseven"))
        }
        // 共存版：包名不同即可同机安装；默认端口/脚本目录也各自独立，避免几个实例同时跑时互相抢
        create("sseven") {
            applicationId = "com.sunc.sseven"
            versionCode = 1000
            versionName = "1.0.0"
            buildConfigField("String", "CHANNEL", "\"sseven\"")
            buildConfigField("int", "DEFAULT_SERVER_PORT", "29318")
            manifestPlaceholders.putAll(mapOf("appName" to "sseven"))
        }
        create("goqi") {
            applicationId = "com.rumy.goqi"
            versionCode = 1000
            versionName = "1.0.0"
            buildConfigField("String", "CHANNEL", "\"goqi\"")
            buildConfigField("int", "DEFAULT_SERVER_PORT", "29319")
            manifestPlaceholders.putAll(mapOf("appName" to "goqi"))
        }
        create("asrseven") {
            applicationId = "com.sunc.asrseven"
            versionCode = 1000
            versionName = "1.0.0"
            buildConfigField("String", "CHANNEL", "\"asrseven\"")
            buildConfigField("int", "DEFAULT_SERVER_PORT", "29320")
            manifestPlaceholders.putAll(mapOf("appName" to "Asrseven"))
        }
        create("zlwseven") {
            applicationId = "com.sunc.zlwseven"
            versionCode = 1000
            versionName = "1.0.0"
            buildConfigField("String", "CHANNEL", "\"zlwseven\"")
            buildConfigField("int", "DEFAULT_SERVER_PORT", "29321")
            manifestPlaceholders.putAll(mapOf("appName" to "zlwseven"))
        }
        create("rbtseven") {
            applicationId = "com.sunc.rbtseven"
            versionCode = 1000
            versionName = "1.0.0"
            buildConfigField("String", "CHANNEL", "\"rbtseven\"")
            buildConfigField("int", "DEFAULT_SERVER_PORT", "29322")
            manifestPlaceholders.putAll(mapOf("appName" to "rbtseven"))
        }
    }
    applicationVariants.all {
        val variant = this
        if (variant.flavorName in brandFlavors) {
            // 模板必须在合并 assets 前就位：这里不像老逻辑那样「文件在就不重建」，不会有陈旧副本
            mergeAssetsProvider.configure { dependsOn(syncBrandTemplateApk) }
        }
        if (variant.flavorName == "v7_mini") {
            mergeAssetsProvider.configure {
                doLast {
                    delete(
                        fileTree(outputDir) {
                            include(
                                "codeeditor/**/*", "template.apk"
                            )
                        })
                }
            }
        }
        if (variant.flavorName in brandFlavors && variant.buildType.name == "release") {
            mergeAssetsProvider.configure {
                doLast {
                    // assets 里的明文样本会被分发侧静态内容扫描直接读走，命中病毒/键盘监听/仿冒品牌类规则
                    // 注意：template.apk 与 autox-default.jks 不在这里删——打包成子 APK 的链路要保留，
                    // 前者由 syncBrandTemplateApk 换成加固过的 :inrt suncV7 模板，后者是签名用的默认库
                    delete(
                        fileTree(outputDir) {
                            include(
                                "binary/root_automator",
                                "sample/v6/复杂界面/WannaCry(仅为娱乐).js",
                                "sample/v6/事件与按键、触摸监听/按键监听.js",
                                "sample/v6/事件与按键、触摸监听/通知监听.js",
                                "sample/v6/多媒体/免root屏幕录制.js",
                                "sample/v6/图片与图色处理/找到QQ红点位置.js",
                                "sample/v6/应用/应用工具.js"
                            )
                        })
                }
            }
        }
    }
    testOptions {
        unitTests {
            // android.jar 方法在本地 JVM 单测中返回默认值（而非抛 "not mocked"），
            // 便于对 DevPluginResponseHandler 的 router 分发/connect_compute 逻辑做纯逻辑测试
            isReturnDefaultValues = true
            all { it.maxHeapSize = "512m" }
        }
    }

    sourceSets {
        getByName("main") {
            res.srcDirs("src/main/res", "src/main/res-i18n")
            aidl {
                srcDirs("src/main/aidl", "src/main/java")
            }
        }
        // flavor 的 assets 优先级高于 main，同名 template.apk 会盖掉 src/main/assets 里那份开源模板
        brandFlavors.forEach { flavor ->
            getByName(flavor) {
                assets.srcDir(brandTemplateAssets)
            }
        }
    }
    configurations.all {
        resolutionStrategy.force("com.google.code.findbugs:jsr305:3.0.1")
        exclude(group = "org.jetbrains", module = "annotations-java5")
//        exclude(group = "com.atlassian.commonmark",) module = "commonmark"
        exclude(group = "com.github.atlassian.commonmark-java", module = "commonmark")
    }

    packaging {
        //ktor netty implementation("io.ktor:ktor-server-netty:2.0.1")
        resources.pickFirsts.addAll(
            listOf(
                "META-INF/io.netty.versions.properties", "META-INF/INDEX.LIST"
            )
        )
    }

}

dependencies {

    implementation(platform(libs.compose.bom))
    // Deprecated!!
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.mlkit.common)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)

    androidTestImplementation(libs.espresso.core)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Kotlin携程
    implementation(libs.kotlinx.coroutines.android)
    // ButterKnife Deprecated!!
    implementation("com.jakewharton:butterknife:10.2.1")
    kapt("com.jakewharton:butterknife-compiler:10.2.3")
    // Android supports
    implementation(libs.preference.ktx)
    implementation(libs.appcompat) //

    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    // Personal libraries  Deprecated!!
    implementation("com.github.hyb1996:MutableTheme:1.0.0")
    // Material Dialogs  Deprecated!!
    implementation("com.afollestad.material-dialogs:core:0.9.2.3")
    // Common Markdown
    implementation("com.github.atlassian:commonmark-java:commonmark-parent-0.9.0")
    // Android issue reporter (a github issue reporter)
    implementation("com.heinrichreimersoftware:android-issue-reporter:1.3.1")
    //MultiLevelListView
    implementation("com.github.hyb1996:android-multi-level-listview:1.1")
    //Licenses Dialog  Deprecated!!
    implementation("de.psdev.licensesdialog:licensesdialog:2.2.0")
    //Expandable RecyclerView
    implementation("com.bignerdranch.android:expandablerecyclerview:3.0.0-RC1")
    //FlexibleDivider
    implementation("com.yqritc:recyclerview-flexibledivider:1.4.0")

    // RxJava  Deprecated!!
    implementation(libs.rxjava2)
    implementation(libs.rxjava2.rxandroid)
    // Retrofit
    implementation(libs.retrofit2.retrofit)
    implementation(libs.retrofit2.converter.gson)
    debugImplementation(libs.leakcanary.android)
    //Glide
    implementation(libs.glide)
    ksp(libs.glide.ksp)
    //joda time
    implementation("net.danlew:android.joda:2.10.14")
    // Tasker Plugin
    implementation("com.twofortyfouram:android-plugin-client-sdk-for-locale:4.0.3")
    // MaterialDialogCommon
    implementation("com.afollestad.material-dialogs:commons:0.9.2.3")
    // WorkManager
    implementation(libs.androidx.work)
    // Optional, if you use support library fragments:
    implementation(project(":autojs"))
    implementation(project(":apkbuilder"))
    implementation(project(":codeeditor"))

    // ViewModel
    implementation(libs.lifecycle.viewmodel.ktx)
    // ViewModel utilities for Compose
    implementation(libs.lifecycle.viewmodel.compose)
    // Lifecycles only (without ViewModel or LiveData)
    implementation(libs.lifecycle.runtime.ktx)
    // Saved state module for ViewModel
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.lifecycle.service)
    // Annotation processor
    ksp(libs.lifecycle.compiler)

    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.savedstate)

    implementation(libs.bundles.ktor)
    //qr scan
    implementation(libs.quickie.bundled)
    //Fab button with menu, please do not upgrade, download dependencies will be error after upgrade
    //noinspection GradleDependency
    implementation("com.leinardi.android:speed-dial.compose:1.0.0-alpha03")
    //TextView markdown
    implementation("io.noties.markwon:core:4.6.2")
    implementation(libs.androidx.viewpager2)
    implementation(libs.coil.compose)
}

fun copyTemplateToAPP(isDebug: Boolean, to: File) {
    val outName = if (isDebug) "template-debug" else "template-release"
    val outFile = project(":inrt").buildOutputs.named(outName).get().outputFile
//    logger.error("buildTemplate from: $outFile")
    copy {
        from(outFile)
        into(to)
        delete(File(to, "template.apk"))
        rename(outFile.name, "template.apk")
    }
    logger.info("buildTemplate success, debugMode: $isDebug")
}

val assetsDir = File(projectDir, "src/main/assets")
if (!File(assetsDir, "template.apk").isFile) {
    tasks.named("preBuild").dependsOn("buildTemplateApp")
}

tasks.register("buildTemplateApp") {
    group = "build"
    dependsOn(":inrt:assembleTemplateRelease")
    doFirst {
        copyTemplateToAPP(false, assetsDir)
    }
}
tasks.register("buildDebugTemplateApp") {
    group = "build"
    dependsOn(":inrt:assembleTemplateDebug")
    doFirst {
        copyTemplateToAPP(true, assetsDir)
    }
}
tasks.named("clean").configure {
    doFirst {
        delete(File(assetsDir, "template.apk"))
    }
}
tasks.register("buildDocs") {
    group = "build"
    doLast {
        val v2DocDir = File(rootProject.projectDir, "docs/v2")
        val jsApiDir = File(rootProject.projectDir, "autojs/src/main/js/v7-api")
        if (!v2DocDir.isDirectory) {
            logger.error("run command: `git submodule update --init --recursive` install docs/v2")
            throw FileNotFoundException("${v2DocDir.path} not found")
        }
        val buildFile = File.createTempFile("buildJs", ".mjs")
        exec {
            workingDir(jsApiDir)
            buildFile.writeText(
                """
                import { execSync } from 'child_process'
                execSync('npm install', { stdio: 'inherit' })
                execSync('npm run docs', { stdio: 'inherit' })
            """.trimIndent()
            )
            execCommand("node " + buildFile.path)
        }
        copy {
            from(File(jsApiDir, "docs"))
            delete(File(v2DocDir, "docs/nodejs/modules"))
            into(File(v2DocDir, "docs/nodejs/modules"))
        }
        exec {
            workingDir(v2DocDir)
            buildFile.writeText(
                """
                import { execSync } from 'child_process'
                execSync('npm install', { stdio: 'inherit' })
                execSync('npm run build', { stdio: 'inherit' })
            """.trimIndent()
            )
            execCommand("node " + buildFile.path)
        }
        copy {
            from(File(v2DocDir, "build"))
            into(File(projectDir, "src/main/assets/docs/v2"))
        }
        buildFile.delete()
    }
}